#!/usr/bin/env bash
# Installs the APK on a running emulator or device, launches it three ways and saves screenshots:
# the title screen, a seeded match ready for a human, and a whole match played by the computer.
# Fails if the app crashes or is not running at the end.   Usage: tools/android_smoke.sh APK OUT_DIR
set -euo pipefail
apk=$1; out=$2
mkdir -p "$out"
pkg=com.supremacy.game
activity=$pkg/com.supremacy.android.AndroidLauncher
shot() { adb exec-out screencap -p > "$out/$1.png"; echo "saved $1.png"; }
alive() { [ -n "$(adb shell pidof $pkg | tr -d '\r')" ]; }
# On failure, put the evidence in the job log itself (artifacts are not always reachable).
fail() {
  echo "FAILED: $1"
  adb logcat -d > "$out/logcat.txt" || true
  echo "---- crash ----"; grep -A40 "FATAL EXCEPTION" "$out/logcat.txt" | head -80 || true
  echo "---- app and libGDX log ----"; grep -iE "AndroidRuntime|supremacy|libgdx|GdxRuntime|AndroidGraphics|EGL|OpenGL" "$out/logcat.txt" | tail -60 || true
  exit 1
}

adb install -r "$apk"
adb logcat -c
adb shell am start -W -n "$activity"
sleep 10; shot 01-title
alive || fail "app is not running after launch"

# Android can recreate the activity at any moment, even before its first frame (rotation, display
# density and other configuration changes). Force that right after a cold start, twice, and check
# the game survives. A density change always recreates the activity.
for d in 360 480; do
  adb shell am force-stop $pkg
  adb shell am start -n "$activity" > /dev/null
  sleep 0.4; adb shell wm density $d; sleep 5
done
adb shell wm density reset; sleep 8
shot 01b-after-recreate
adb logcat -d > "$out/logcat.txt"
grep -q "FATAL EXCEPTION" "$out/logcat.txt" && fail "crash while the activity was being recreated"
alive || fail "app is not running after being recreated"

adb shell am force-stop $pkg
adb shell am start -W -n "$activity" --es demo play
sleep 10; shot 02-new-match
alive || fail "app is not running after starting a match"
# Tap the middle of the map area a few times; taps on land place armies or open territory details.
read -r w h < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
long=$(( w > h ? w : h )); short=$(( w > h ? h : w ))
for x in 20 30 40; do adb shell input tap $(( long * x / 100 )) $(( short / 2 )); sleep 1; done
shot 03-after-taps

adb shell am force-stop $pkg
adb shell am start -W -n "$activity" --es demo autoplay
for i in 1 2 3 4 5 6; do sleep 15; shot "04-autoplay-$i"; alive || break; done

adb logcat -d > "$out/logcat.txt"
grep -q "FATAL EXCEPTION" "$out/logcat.txt" && fail "crash found in logcat"
alive || fail "app stopped during autoplay"
echo "android smoke test passed"
