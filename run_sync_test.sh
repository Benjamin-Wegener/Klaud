#!/bin/bash
ADB=/home/user/Android/Sdk/platform-tools/adb
EMU1=emulator-5554
EMU2=emulator-5556

echo "=== 1. TOR STATUS ==="
$ADB -s $EMU1 shell logcat -d -s TorHiddenService 2>/dev/null | grep -E "ready|onion|SOCKS|ERROR" | tail -5
$ADB -s $EMU2 shell logcat -d -s TorHiddenService 2>/dev/null | grep -E "ready|onion|SOCKS|ERROR" | tail -5

echo "=== 2. ONION ADRESSEN ==="
$ADB -s $EMU1 shell run-as org.klaud cat /data/data/org.klaud/shared_prefs/klaud_onion_port_prefs.xml 2>/dev/null
$ADB -s $EMU2 shell run-as org.klaud cat /data/data/org.klaud/shared_prefs/klaud_onion_port_prefs.xml 2>/dev/null

echo "=== 3. PAIRING.JSON INHALT ==="
$ADB -s $EMU1 shell cat /sdcard/Android/data/org.klaud/files/pairing.json 2>/dev/null || echo "NICHT GEFUNDEN"
$ADB -s $EMU2 shell cat /sdcard/Android/data/org.klaud/files/pairing.json 2>/dev/null || echo "NICHT GEFUNDEN"

echo "=== 4. GEPAIRTE GERAETE ==="
$ADB -s $EMU1 shell run-as org.klaud cat /data/data/org.klaud/shared_prefs/klauddevices.xml 2>/dev/null || echo "LEER"
$ADB -s $EMU2 shell run-as org.klaud cat /data/data/org.klaud/shared_prefs/klauddevices.xml 2>/dev/null || echo "LEER"

echo "=== 5. MANUELLES PAIRING (neu) ==="
# pairing.json lesen und warten bis voll
sleep 5
DATA1=$($ADB -s $EMU1 shell cat /sdcard/Android/data/org.klaud/files/pairing.json 2>/dev/null)
DATA2=$($ADB -s $EMU2 shell cat /sdcard/Android/data/org.klaud/files/pairing.json 2>/dev/null)
echo "EMU1 pairing.json: $DATA1"
echo "EMU2 pairing.json: $DATA2"

# Kreuzt pairen: EMU1 scannt EMU2 und umgekehrt
if [ -n "$DATA1" ] && [ "$DATA1" != "NICHT GEFUNDEN" ]; then
    echo "Sende EMU2 den QR von EMU1..."
    $ADB -s $EMU2 shell am broadcast -a org.klaud.QR_SCANNED \
        --es qr_data "$DATA1"
fi
if [ -n "$DATA2" ] && [ "$DATA2" != "NICHT GEFUNDEN" ]; then
    echo "Sende EMU1 den QR von EMU2..."
    $ADB -s $EMU1 shell am broadcast -a org.klaud.QR_SCANNED \
        --es qr_data "$DATA2"
fi

sleep 5
echo "=== 6. GERAETE NACH PAIRING ==="
$ADB -s $EMU1 shell run-as org.klaud cat /data/data/org.klaud/shared_prefs/klauddevices.xml 2>/dev/null
$ADB -s $EMU2 shell run-as org.klaud cat /data/data/org.klaud/shared_prefs/klauddevices.xml 2>/dev/null

echo "=== 7. SYNC TEST: Datei auf EMU1 erstellen ==="
TESTFILE="klaud_synctest_$(date +%s).txt"
$ADB -s $EMU1 shell "mkdir -p /sdcard/Android/data/org.klaud/files/Klaud"
$ADB -s $EMU1 shell "echo 'Sync Test $(date)' > /sdcard/Android/data/org.klaud/files/Klaud/$TESTFILE"
echo "Datei erstellt: $TESTFILE"
echo "Warte 30s auf Sync..."
sleep 30

echo "=== 8. PRUEFE OB DATEI AUF EMU2 ANGEKOMMEN ==="
RESULT=$($ADB -s $EMU2 shell ls /sdcard/Android/data/org.klaud/files/Klaud/ 2>/dev/null)
echo "EMU2 Klaud-Ordner:"
echo "$RESULT"
if echo "$RESULT" | grep -q "$TESTFILE"; then
    echo "✅ SYNC ERFOLGREICH: $TESTFILE auf EMU2 gefunden!"
else
    echo "❌ SYNC FEHLGESCHLAGEN: $TESTFILE nicht auf EMU2"
    echo ""
    echo "=== SYNC LOGS EMU1 ==="
    $ADB -s $EMU1 shell logcat -d -s FileSyncService SyncContentObserver 2>/dev/null | tail -20
fi
