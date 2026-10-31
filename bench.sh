#!/usr/bin/env bash
# Installs the app on the USB-connected phone, copies in Bark 3.0 Clean and the test
# clips, runs the speed + accuracy test and prints the summary.
#   ./bench.sh [threads] [limit]      (from WSL; needs USB debugging on)
set -euo pipefail
ADB=/mnt/c/Users/Dell/AppData/Local/Android/Sdk/platform-tools/adb.exe
PKG=com.akoway.app
DIR=/sdcard/Android/data/$PKG/files
MODEL='D:\akoway-build\model-clean'
CLIPS='D:\akoway-build\clips'
REFERENCE='D:\akoway-build\csharp_clean.tsv'

$ADB install -r 'D:\akoway-android\app\build\outputs\apk\debug\app-debug.apk'
$ADB shell am start -W -n $PKG/.BenchActivity >/dev/null   # creates the app's files folder
$ADB shell am force-stop $PKG
if [ "$($ADB shell "ls $DIR/bark-3.0-clean/encoder.int8.onnx 2>/dev/null" | tr -d '\r')" = "" ]; then
    $ADB push "$MODEL" "$DIR/"
    $ADB shell mv "$DIR/model-clean" "$DIR/bark-3.0-clean"
fi
$ADB push "$CLIPS" "$DIR/" >/dev/null
$ADB push "$REFERENCE" "$DIR/clips/reference.tsv" >/dev/null
$ADB shell rm -f "$DIR/summary.txt"
$ADB shell am force-stop $PKG   # a running copy would swallow the new start
$ADB logcat -c
$ADB shell am start -n $PKG/.BenchActivity ${1:+--ei threads $1} ${2:+--ei limit $2} >/dev/null
echo "Running on the phone (keep the screen on)..."
until $ADB shell "cat $DIR/summary.txt 2>/dev/null" | grep -q .; do
    if $ADB logcat -d -s AkowayBench:E | grep -q "Bench failed"; then $ADB logcat -d -s AkowayBench; exit 1; fi
    sleep 10
done
$ADB logcat -d -s AkowayBench:I | sed 's/^.*AkowayBench: //' | head -${SHOW:-3}
$ADB shell cat "$DIR/summary.txt"
$ADB pull "$DIR/results.tsv" '\\wsl.localhost\Ubuntu-24.04\home\codingsalafi\Dev\my-codes\akoway-android\results.tsv' >/dev/null
