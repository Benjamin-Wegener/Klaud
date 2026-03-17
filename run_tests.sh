#!/bin/bash
set -e

# 1. SDK Pfad auflösen
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
echo "--- Klaud Environment Setup ---"
echo "Using SDK: $ANDROID_SDK_ROOT"

if [ ! -d "$ANDROID_SDK_ROOT" ]; then
    echo "ERROR: SDK directory not found at $ANDROID_SDK_ROOT"
    exit 1
fi

# 2. Suche nach Binaries
find_it() {
    local name="$1"
    # Suche im SDK (ohne -type f, falls es ein Symlink ist)
    local found=$(find "$ANDROID_SDK_ROOT" -name "$name" 2>/dev/null | grep -v "sdk-patch" | head -n 1)
    if [ -n "$found" ]; then
        echo "$found"
        return 0
    fi
    # Check current PATH
    if command -v "$name" > /dev/null 2>&1; then
        command -v "$name"
        return 0
    fi
    return 1
}

AVDMANAGER=$(find_it avdmanager) || {
    echo "ERROR: avdmanager not found."
    echo "Versuche: ls -R $ANDROID_SDK_ROOT | grep avdmanager"
    exit 1
}
ADB=$(find_it adb) || { echo "ERROR: adb not found"; exit 1; }
EMULATOR=$(find_it emulator) || { echo "ERROR: emulator not found"; exit 1; }

echo "Executables found:"
echo "  ADB: $ADB"
echo "  AVD: $AVDMANAGER"
echo "  EMU: $EMULATOR"

# 3. AVD Setup
echo "Preparing AVDs..."
for i in 1 2 3; do
    "$AVDMANAGER" create avd -n klaud_test_$i -k "system-images;android-34;google_apis;x86_64" --force <<< "no" || true
done

# 4. Launch Emulators
echo "Launching Emulators (headless)..."
for i in 1 2 3; do
    "$EMULATOR" -avd klaud_test_$i -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect > /dev/null 2>&1 &
done

# 5. Wait for Boot
echo "Waiting for devices to appear..."
sleep 15
SERIALS=($("$ADB" devices | grep emulator | awk '{print $1}'))

if [ ${#SERIALS[@]} -eq 0 ]; then
    echo "ERROR: No emulators started. Check if 'system-images;android-34;google_apis;x86_64' is installed."
    exit 1
fi

for S in "${SERIALS[@]}"; do
    echo "Waiting for $S to finish booting..."
    count=0
    until "$ADB" -s $S shell getprop sys.boot_completed | grep -q 1 || [ $count -gt 40 ]; do
        sleep 5
        count=$((count+1))
    done
    echo "Device $S ready."
done

# 6. Build & Deploy
echo "Building APKs..."
./gradlew assembleDebug assembleAndroidTest

for S in "${SERIALS[@]}"; do
    echo "Installing on $S..."
    "$ADB" -s $S install -r app/build/outputs/apk/debug/app-debug.apk
    "$ADB" -s $S install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
done

# 7. Pairing & Tests
echo "Running pairing sequence..."
for S in "${SERIALS[@]}"; do
    "$ADB" -s $S shell am start -n org.klaud/.MainActivity
done
sleep 8

PKG="org.klaud"
FILE="/sdcard/Android/data/$PKG/files/pairing.json"
for SENDER in "${SERIALS[@]}"; do
    if "$ADB" -s $SENDER shell ls "$FILE" &> /dev/null; then
        DATA=$("$ADB" -s $SENDER shell cat "$FILE")
        for RECEIVER in "${SERIALS[@]}"; do
            [ "$SENDER" = "$RECEIVER" ] && continue
            "$ADB" -s $RECEIVER shell am broadcast -a org.klaud.QR_SCANNED --es qr_data "$DATA"
        done
    fi
done

echo "Starting instrumented tests..."
mkdir -p test_results
for i in "${!SERIALS[@]}"; do
    "$ADB" -s "${SERIALS[$i]}" shell am instrument -w org.klaud.test/androidx.test.runner.AndroidJUnitRunner > test_results/emu$i.txt 2>&1 &
done
wait

echo "--- FINAL RESULTS ---"
for i in "${!SERIALS[@]}"; do
    echo "EMU $((i+1)): $(grep -E "OK|FAILURES" test_results/emu$i.txt || echo "Crash/Error")"
done
echo "Test run finished."
