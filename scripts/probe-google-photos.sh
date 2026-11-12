#!/usr/bin/env bash
# Inspect the preinstalled Photos app on an isolated CI emulator without signing in or uploading.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
set -Eeuo pipefail
out=ui-evidence
mkdir -p "$out"
status="$out/google-photos-status.txt"
{
  echo 'Android release:'; adb shell getprop ro.build.version.release
  echo 'API:'; adb shell getprop ro.build.version.sdk
  echo 'Device size:'; adb shell wm size
  echo 'Density:'; adb shell wm density
  echo 'Font scale:'; adb shell settings get system font_scale
  echo 'System locales:'; adb shell getprop persist.sys.locale
  echo 'Night mode:'; adb shell cmd uimode night
  echo 'Navigation mode overlay:'; adb shell cmd overlay list com.android.internal.systemui.navbar 2>/dev/null || true
  echo 'Package:'; adb shell pm list packages com.google.android.apps.photos
} > "$status"
if ! adb shell pm list packages com.google.android.apps.photos | tr -d '\r' | grep -Fxq 'package:com.google.android.apps.photos'; then
  echo 'Result: Google Photos package not installed; no reference UI captured.' >> "$status"
  exit 0
fi
adb shell dumpsys package com.google.android.apps.photos | grep -m 1 'versionName=' >> "$status" || true
restore() {
  adb shell am force-stop com.google.android.apps.photos >/dev/null 2>&1 || true
  adb shell rm -f /sdcard/Pictures/GlimpseReferenceFixture-portrait.png /sdcard/Pictures/GlimpseReferenceFixture-landscape.png >/dev/null 2>&1 || true
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
}
trap restore EXIT
# Refuse to proceed if offline mode cannot be established; no account or sync flow is used.
if ! adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 ||
   ! adb shell svc wifi disable >/dev/null 2>&1 ||
   ! adb shell svc data disable >/dev/null 2>&1 ||
   [[ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" != 1 ]]; then
  echo 'Result: could not establish offline mode; stopped before launch.' >> "$status"
  exit 0
fi
adb push app/src/androidTest/assets/synthetic_portrait.png /sdcard/Pictures/GlimpseReferenceFixture-portrait.png >/dev/null
adb push app/src/androidTest/assets/synthetic_landscape.png /sdcard/Pictures/GlimpseReferenceFixture-landscape.png >/dev/null
for image in portrait landscape; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Pictures/GlimpseReferenceFixture-${image}.png" >/dev/null
done
if ! adb shell monkey -p com.google.android.apps.photos -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; then
  echo 'Result: package installed but launcher failed.' >> "$status"
  exit 0
fi
sleep 8
capture() {
  local name=$1 stable=0
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/window-google.xml >/dev/null
    adb pull /sdcard/window-google.xml "$out/google-photos-$name.xml" >/dev/null
    adb exec-out screencap -p > "$out/google-photos-$name.png"
    sleep 2
    adb shell uiautomator dump /sdcard/window-google-probe.xml >/dev/null
    adb pull /sdcard/window-google-probe.xml "$out/google-photos-probe.xml" >/dev/null
    adb exec-out screencap -p > "$out/google-photos-probe.png"
    if cmp -s "$out/google-photos-$name.xml" "$out/google-photos-probe.xml" && cmp -s "$out/google-photos-$name.png" "$out/google-photos-probe.png"; then
      stable=1; break
    fi
  done
  echo "Capture $name: stable=$stable (PNG and XML compared twice)" >> "$status"
  rm -f "$out/google-photos-probe.xml" "$out/google-photos-probe.png"
}
capture start
# Only dismiss the backup prompt with Back; never click sign-in or backup.
adb shell input keyevent KEYCODE_BACK
sleep 4
capture after-back
# Try a direct VIEW intent for the same synthetic fixture via local MediaStore.
query=$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name 2>/dev/null | tr -d '\r' || true)
id=$(printf '%s\n' "$query" | grep 'GlimpseReferenceFixture-portrait.png' | sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p' | head -1)
if [[ -z "$id" ]]; then
  echo 'Result: fixture not present in MediaStore; no editor attempted.' >> "$status"
  exit 0
fi
if adb shell am start -a android.intent.action.VIEW -d "content://media/external/images/media/$id" -t image/png -p com.google.android.apps.photos --grant-read-uri-permission >/dev/null 2>&1; then
  sleep 7
  capture photo-view
  echo 'Result: attempted local fixture VIEW after Back; no sign-in or backup action performed. Further navigation requires manual inspection.' >> "$status"
else
  echo 'Result: Google Photos did not accept local fixture VIEW intent.' >> "$status"
fi
