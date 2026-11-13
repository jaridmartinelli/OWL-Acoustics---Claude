#!/usr/bin/env bash
# Installs each debug APK, launches it, screenshots it, sends seeded random taps, and fails on any crash or ANR.
set -u
mkdir -p smoke
echo "## Emulator smoke test" > smoke/summary.md
failed=0

check() { # $1 package, $2 label, $3 out dir
  adb logcat -d > "$3/logcat-$2.txt"
  if grep -E "FATAL EXCEPTION|ANR in $1" -A4 "$3/logcat-$2.txt" | grep -q "$1"; then
    echo "- **$2**: crash or ANR (see logcat-$2.txt)" >> smoke/summary.md
    return 1
  fi
  if [ -z "$(adb shell pidof "$1" | tr -d '\r')" ]; then
    echo "- **$2**: app was not running afterwards" >> smoke/summary.md
    return 1
  fi
  echo "- **$2**: ok" >> smoke/summary.md
}

smoke() { # $1 name, $2 package, $3 apk
  local out="smoke/$1"; mkdir -p "$out"
  echo "### $1" >> smoke/summary.md
  if ! adb install -r -g "$3" > "$out/install.txt" 2>&1; then
    echo "- **install**: failed: $(tail -1 "$out/install.txt")" >> smoke/summary.md
    return 1
  fi
  adb logcat -c
  adb shell monkey -p "$2" -c android.intent.category.LAUNCHER 1 > /dev/null
  sleep 20
  adb exec-out screencap -p > "$out/1-launch.png"
  check "$2" launch "$out" || return 1
  adb logcat -c
  # 400 seeded random touches and swipes, no system keys, so a failure can be replayed with the same seed
  adb shell monkey -p "$2" -s 4242 --throttle 150 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 -v 400 > "$out/monkey.txt" 2>&1
  sleep 3
  adb exec-out screencap -p > "$out/2-after-taps.png"
  check "$2" taps "$out" || return 1
}

for apk in apks/app/build/outputs/apk/debug/*.apk; do smoke app com.owlacoustics.roomplanner.dev "$apk" || failed=1; done
for apk in apks/lab/build/outputs/apk/debug/*.apk; do smoke lab com.owlacoustics.capturelab.dev "$apk" || failed=1; done
cat smoke/summary.md
exit $failed
