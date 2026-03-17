#!/bin/bash
ADB=/home/user/Android/Sdk/platform-tools/adb
EMU1=emulator-5554
EMU2=emulator-5556
PKG=org.klaud

echo "=== Lese echte Onion-Adressen aus Logcat ==="
ONION1=$($ADB -s $EMU1 shell logcat -d -s TorHiddenService 2>/dev/null \
    | grep "Real Tor hidden service ready" \
    | tail -1 \
    | grep -oP '[a-z2-7]{56}\.onion')
PORT1=$($ADB -s $EMU1 shell logcat -d -s TorHiddenService 2>/dev/null \
    | grep "Real Tor hidden service ready" \
    | tail -1 \
    | grep -oP '(?<=:)\d+$')

ONION2=$($ADB -s $EMU2 shell logcat -d -s TorHiddenService 2>/dev/null \
    | grep "Real Tor hidden service ready" \
    | tail -1 \
    | grep -oP '[a-z2-7]{56}\.onion')
PORT2=$($ADB -s $EMU2 shell logcat -d -s TorHiddenService 2>/dev/null \
    | grep "Real Tor hidden service ready" \
    | tail -1 \
    | grep -oP '(?<=:)\d+$')

KEY1=$($ADB -s $EMU1 shell logcat -d -s KyberKeyManager 2>/dev/null \
    | grep "Kyber Public Key Hash" | tail -1 | grep -oP '[0-9a-f]{64}')
KEY2=$($ADB -s $EMU2 shell logcat -d -s KyberKeyManager 2>/dev/null \
    | grep "Kyber Public Key Hash" | tail -1 | grep -oP '[0-9a-f]{64}')

echo "EMU1: $ONION1:$PORT1  key=$KEY1"
echo "EMU2: $ONION2:$PORT2  key=$KEY2"

if [ -z "$ONION1" ] || [ -z "$ONION2" ]; then
    echo "❌ Onion-Adressen nicht gefunden — Tor läuft noch nicht"
    exit 1
fi

echo "=== Schreibe korrekte pairing.json ==="
# Direkt als Datei auf Emulator pushen (kein Shell-Escaping-Problem)
printf '{"onion":"%s","port":%s,"key":"%s"}' "$ONION1" "$PORT1" "$KEY1" > /tmp/p1.json
printf '{"onion":"%s","port":%s,"key":"%s"}' "$ONION2" "$PORT2" "$KEY2" > /tmp/p2.json

$ADB -s $EMU1 push /tmp/p1.json /sdcard/Android/data/$PKG/files/pairing.json
$ADB -s $EMU2 push /tmp/p2.json /sdcard/Android/data/$PKG/files/pairing.json

echo "=== Pairing via adb broadcast (Datei-basiert) ==="
$ADB -s $EMU1 push /tmp/p2.json /sdcard/Android/data/$PKG/files/pairing_in.json
$ADB -s $EMU2 push /tmp/p1.json /sdcard/Android/data/$PKG/files/pairing_in.json

$ADB -s $EMU1 shell am broadcast -a org.klaud.QR_SCANNED \
    --es qrfile "/sdcard/Android/data/$PKG/files/pairing_in.json" \
    --receiver-include-background
$ADB -s $EMU2 shell am broadcast -a org.klaud.QR_SCANNED \
    --es qrfile "/sdcard/Android/data/$PKG/files/pairing_in.json" \
    --receiver-include-background

sleep 10
echo "=== Gepairte Geräte ==="
$ADB -s $EMU1 shell run-as $PKG cat /data/data/$PKG/shared_prefs/klauddevices.xml
$ADB -s $EMU2 shell run-as $PKG cat /data/data/$PKG/shared_prefs/klauddevices.xml

echo "=== Sync Test ==="
TESTFILE="sync_$(date +%s).txt"
$ADB -s $EMU1 shell "echo 'Hello from EMU1' > /sdcard/Android/data/$PKG/files/Klaud/$TESTFILE"
echo "Warte 45s..."
sleep 45
RESULT=$($ADB -s $EMU2 shell ls /sdcard/Android/data/$PKG/files/Klaud/ 2>/dev/null)
if echo "$RESULT" | grep -q "$TESTFILE"; then
    echo "✅ SYNC ERFOLGREICH!"
else
    echo "❌ SYNC FEHLGESCHLAGEN"
    echo "=== FileSyncService Log EMU1 ==="
    $ADB -s $EMU1 shell logcat -d -s FileSyncService DeviceStatusChecker 2>/dev/null | tail -30
fi
