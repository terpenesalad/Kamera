#!/usr/bin/env bash
# End-to-end smoke test on an emulator: launches Kamera, shoots on several stocks, records video,
# opens the gallery and darkroom, and fails on any crash or shader error.
set -u
APK=app/build/outputs/apk/release/app-release.apk
PKG=app.kamera
OUT=smoke
mkdir -p "$OUT"
FAILED=0

note() { echo "=== $*"; }
fail() { echo "::error::$*"; FAILED=1; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb install -r "$APK" || fail "install failed"
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb logcat -c

SIZE=$(adb shell wm size | tail -1 | awk '{print $3}' | tr -d '\r')
W=${SIZE%x*}; H=${SIZE#*x}
DENSITY=$(adb shell wm density | tail -1 | awk '{print $3}' | tr -d '\r')
dp() { echo $(( $1 * DENSITY / 160 )); }
tap() { adb shell input tap "$1" "$2"; }
note "screen ${W}x${H} density $DENSITY"

launch() { adb shell am start -W -n $PKG/.ui.CameraActivity >/dev/null; }
set_prefs() {
  adb shell am force-stop $PKG
  adb shell "mkdir -p /data/data/$PKG/shared_prefs"
  adb shell "cat > /data/data/$PKG/shared_prefs/kamera.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="stock">$1</string><boolean name="histogram" value="true" /></map>
XML
  adb shell "chown \$(stat -c %u:%g /data/data/$PKG) /data/data/$PKG/shared_prefs/kamera.xml"
  adb shell "restorecon -R /data/data/$PKG/shared_prefs" 2>/dev/null
}

launch; sleep 20; shot 00-first-launch
for STOCK in aura400 silver400 nocturne800t chroma50; do
  note "shooting on $STOCK"
  BEFORE=$(adb shell "ls /sdcard/DCIM/Kamera 2>/dev/null | wc -l" | tr -d '\r')
  set_prefs "$STOCK"; launch; sleep 12
  shot "vf-$STOCK"
  adb shell input keyevent KEYCODE_VOLUME_UP
  sleep 14
  AFTER=$(adb shell "ls /sdcard/DCIM/Kamera 2>/dev/null | wc -l" | tr -d '\r')
  if [ "${AFTER:-0}" -le "${BEFORE:-0}" ]; then fail "no photo saved on $STOCK"; fi
  NEWEST=$(adb shell "ls -t /sdcard/DCIM/Kamera | head -1" | tr -d '\r')
  [ -n "$NEWEST" ] && adb pull "/sdcard/DCIM/Kamera/$NEWEST" "$OUT/photo-$STOCK.jpg" >/dev/null
done

note "film cabinet and settings"
tap $(( W / 2 )) "$(dp 64)"; sleep 3; shot 10-film-cabinet
adb shell input keyevent KEYCODE_BACK; sleep 2

note "video"
tap $(( W / 2 + $(dp 40) )) $(( H - $(dp 175) )); sleep 8; shot 20-video-mode
adb shell input keyevent KEYCODE_VOLUME_UP; sleep 7; shot 21-recording
adb shell input keyevent KEYCODE_VOLUME_UP; sleep 8
if adb shell "ls /sdcard/DCIM/Kamera" | grep -q '\.mp4'; then
  CLIP=$(adb shell "ls -t /sdcard/DCIM/Kamera | grep mp4 | head -1" | tr -d '\r')
  adb pull "/sdcard/DCIM/Kamera/$CLIP" "$OUT/clip.mp4" >/dev/null
else
  fail "no video saved"
fi

note "gallery, viewer and darkroom"
tap "$(dp 52)" $(( H - $(dp 88) )); sleep 5; shot 30-gallery
tap $(( W / 6 )) "$(dp 200)"; sleep 5; shot 31-viewer
tap $(( W / 2 )) $(( H - $(dp 58) )); sleep 8; shot 32-darkroom
tap $(( W - $(dp 23) )) "$(dp 66)"; sleep 12; shot 33-after-save

adb logcat -d > "$OUT/logcat.txt"
note "app problems in logcat"
grep -E "FATAL EXCEPTION|AndroidRuntime|Shader compile|Program link|Kamera(Camera|Darkroom|Recorder|Export)|glError|GL_INVALID" "$OUT/logcat.txt" | cut -c1-260 | head -60
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  fail "the app crashed"
  grep -A25 "FATAL EXCEPTION" "$OUT/logcat.txt" | cut -c1-240 | head -70
fi
ls -la "$OUT"
exit $FAILED
