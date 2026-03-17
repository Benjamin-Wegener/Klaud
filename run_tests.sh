#!/bin/bash
set -e

# Versuche Android SDK Pfad automatisch zu finden
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
if [ ! -d "$ANDROID_SDK_ROOT" ]; then
    # Suche in typischen Pfaden
    PATHS=("/opt/android-sdk" "/usr/lib/android-sdk" "$HOME/Library/Android/sdk")
    for P in "${PATHS[@]}"; do
        if [ -d "$P" ]; then export ANDROID_SDK_ROOT="$P"; break; fi
    done
fi

# Suche dynamisch nach den Binaries
find_binary() {
    local name=$1
    local found=$(find "$ANDROID_SDK_ROOT" -name "$name" -type f -executable 2>/dev/null | head -n 1)
    if [ -z "$found" ]; then
        # Check PATH as fallback
        if command -v "$name" &> /dev/null; then
            echo "$name"
        else
            return 1
        fi
    else
        echo "$found"
    fi
}

ADB=$(find_binary adb) || { echo "ERROR: adb not found"; exit 1; }
EMULATOR=$(find_binary emulator) || { echo "ERROR: emulator not found"; exit 1; }
AVDMANAGER=$(find_binary avdmanager) || { echo "ERROR: avdmanager not found"; exit 1; }

echo "Using Binaries:"
echo "  ADB: $ADB"
echo "  EMULATOR: $EMULATOR"
echo "  AVDMANAGER: $AVDMANAGER"

# AVDs erstellen
for i in 1 2 3; do
    "$AVDMANAGER" create avd -n klaud_test_$i \
        -k "system-images;android-34;google_apis;x86_64" --force <<< "no"
done

# Headless starten
for i in 1 2 3; do
    "$EMULATOR" -avd klaud_test_$i -no-window -no-audio \
        -no-boot-anim -gpu swiftshader_indirect &
done

# Boot abwarten
echo "Waiting for emulators to boot..."
sleep 15
SERIALS=($("$ADB" devices | grep emulator | awk '{print $1}'))
for S in "${SERIALS[@]}"; do
    until "$ADB" -s $S shell getprop sys.boot_completed | grep -q 1; do sleep 5; done
    echo "$S ready"
done

# Bauen & installieren
./gradlew assembleDebug assembleAndroidTest
for S in "${SERIALS[@]}"; do
    "$ADB" -s $S install -r app/build/outputs/apk/debug/app-debug.apk
    "$ADB" -s $S install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    "$ADB" -s $S shell pm grant org.klaud android.permission.READ_EXTERNAL_STORAGE || true
    "$ADB" -s $S shell pm grant org.klaud android.permission.WRITE_EXTERNAL_STORAGE || true
done

# App starten (damit pairing.json geschrieben wird)
for S in "${SERIALS[@]}"; do
    "$ADB" -s $S shell am start -n org.klaud/.MainActivity
done
sleep 6

# QR Pairing via Datei-Austausch
PKG="org.klaud"
DEVICE_PATH="/sdcard/Android/data/$PKG/files/pairing.json"
for SENDER in "${SERIALS[@]}"; do
    # Warte kurz falls Datei noch nicht geschrieben
    MAX_RETRIES=10
    RETRY=0
    until "$ADB" -s $SENDER shell ls $DEVICE_PATH &> /dev/null || [ $RETRY -eq $MAX_RETRIES ]; do
        sleep 2
        let RETRY=RETRY+1
    done

    DATA=$("$ADB" -s $SENDER shell cat $DEVICE_PATH)
    for RECEIVER in "${SERIALS[@]}"; do
        [ "$SENDER" = "$RECEIVER" ] && continue
        "$ADB" -s $RECEIVER shell am broadcast \
            -a org.klaud.QR_SCANNED \
            --es qr_data "$DATA"
    done
done
echo "Pairing complete."
sleep 3

# Tests parallel ausführen
mkdir -p test_results
RUNNER="org.klaud.test/androidx.test.runner.AndroidJUnitRunner"
for i in 0 1 2; do
    if [ ! -z "${SERIALS[$i]}" ]; then
        "$ADB" -s ${SERIALS[$i]} shell am instrument -w $RUNNER \
            > test_results/emu$i.txt 2>&1 &
    fi
done
wait

# Ergebnisse ausgeben
for i in 0 1 2; do
    if [ -f "test_results/emu$i.txt" ]; then
        echo "=== EMU $((i+1)) (${SERIALS[$i]}) ===" && cat test_results/emu$i.txt
    fi
done

# Cleanup
# for S in "${SERIALS[@]}"; do "$ADB" -s $S emu kill; done
echo "Tests finished."
