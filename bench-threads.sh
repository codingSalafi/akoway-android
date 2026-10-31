#!/usr/bin/env bash
# Compares thread counts on a cool phone: waits for the big cores to drop below 45 °C before each run.
ADB=/mnt/c/Users/Dell/AppData/Local/Android/Sdk/platform-tools/adb.exe
cd "$(dirname "$0")"
# Live reading (the first list dumpsys prints is a stale cache)
big_temp() { $ADB shell dumpsys thermalservice | sed -n '/Current temperatures from HAL/,$p' | grep -m1 'mName=BIG' | sed 's/.*mValue=\([0-9]*\).*/\1/'; }
for t in "$@"; do
    until [ "$(big_temp)" -lt 45 ]; do sleep 20; done
    echo "== threads $t (big cores $(big_temp) °C)"
    ./bench.sh "$t" 12 | grep -E "RTF|FAILED"
done
