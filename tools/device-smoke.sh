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
adb shell settings put global hide_error_dialogs 1
adb logcat -G 16M
adb logcat -c
sleep 30

SIZE=$(adb shell wm size | tail -1 | awk '{print $3}' | tr -d '\r')
W=${SIZE%x*}; H=${SIZE#*x}
DENSITY=$(adb shell wm density | tail -1 | awk '{print $3}' | tr -d '\r')
dp() { echo $(( $1 * DENSITY / 160 )); }
tap() { adb shell input tap "$1" "$2"; }
note "screen ${W}x${H} density $DENSITY"

# Waits for a new file in the Kamera folder; software GL on the emulator develops slowly.
wait_for_new() {
  local before=$1 count waited=0
  while [ $waited -lt 180 ]; do
    count=$(adb shell "ls /sdcard/DCIM/Kamera 2>/dev/null | wc -l" | tr -d '\r')
    if [ "${count:-0}" -gt "$before" ]; then
      echo "new file after ${waited}s"
      sleep 4
      return 0
    fi
    sleep 3; waited=$((waited + 3))
  done
  return 1
}

# Taps the on-screen element with this exact text, wherever the layout has put it.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local bounds
  bounds=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -z "$bounds" ] && { fail "could not find \"$1\" on screen"; return 1; }
  set -- $bounds
  tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

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
  wait_for_new "${BEFORE:-0}" || fail "no photo saved on $STOCK"
  NEWEST=$(adb shell "ls -t /sdcard/DCIM/Kamera | head -1" | tr -d '\r')
  [ -n "$NEWEST" ] && adb pull "/sdcard/DCIM/Kamera/$NEWEST" "$OUT/photo-$STOCK.jpg" >/dev/null
done

note "film cabinet and settings"
tap $(( W / 2 )) "$(dp 64)"; sleep 3; shot 10-film-cabinet
adb shell input keyevent KEYCODE_BACK; sleep 2

note "video"
tap_text VIDEO; sleep 8; shot 20-video-mode
BEFORE=$(adb shell "ls /sdcard/DCIM/Kamera 2>/dev/null | wc -l" | tr -d '\r')
adb shell input keyevent KEYCODE_VOLUME_UP; sleep 10; shot 21-recording
adb shell input keyevent KEYCODE_VOLUME_UP
wait_for_new "${BEFORE:-0}" || true
if adb shell "ls /sdcard/DCIM/Kamera" | grep -q '\.mp4'; then
  CLIP=$(adb shell "ls -t /sdcard/DCIM/Kamera | grep mp4 | head -1" | tr -d '\r')
  adb pull "/sdcard/DCIM/Kamera/$CLIP" "$OUT/clip.mp4" >/dev/null
else
  fail "no video saved"
fi

note "gallery, viewer and darkroom"
top() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | sed -E 's/.*(app\.kamera[^ ]*).*/\1/' | cut -c1-90; }
expect() {
  local where; where=$(top); echo "on screen: $where"
  case "$where" in *"$1"*) ;; *) fail "expected $1 but found $where" ;; esac
}
tap "$(dp 52)" $(( H - $(dp 88) )); sleep 8; shot 30-gallery; expect GalleryActivity
tap $(( W / 2 )) "$(dp 200)"; sleep 10; shot 31-viewer; expect ViewerActivity
tap $(( W / 2 )) $(( H - $(dp 58) )); sleep 15; shot 32-darkroom; expect EditorActivity
tap_text LIGHT; sleep 4; shot 34-darkroom-light
tap $(( W - $(dp 23) )) "$(dp 66)"; sleep 25; shot 33-after-save; expect ViewerActivity

adb logcat -d > "$OUT/logcat.txt"
note "app log"
grep -E " [IWE] Kamera| [EF] (AndroidRuntime|CameraDevice|MediaCodec|MediaMuxer|AudioRecord|BufferQueue|ImageReader|GLConsumer|SurfaceTexture)|GLConsumer|ANR in|Shader compile|Program link" "$OUT/logcat.txt" | grep -v "Can.t dequeue" | cut -c1-260 | tail -80
if grep -q "ANR in $PKG" "$OUT/logcat.txt"; then
  fail "the app stopped responding"
  grep -A12 "ANR in $PKG" "$OUT/logcat.txt" | cut -c1-240 | head -40
fi
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  fail "the app crashed"
  grep -A25 "FATAL EXCEPTION" "$OUT/logcat.txt" | cut -c1-240 | head -70
fi

python3 -m pip install --quiet --break-system-packages pillow numpy 2>/dev/null || python3 -m pip install --quiet pillow numpy
python3 tools/smoke_report.py "$OUT" "$W" "$H" "$DENSITY" || fail "picture checks failed"
ls -la "$OUT"
exit $FAILED
