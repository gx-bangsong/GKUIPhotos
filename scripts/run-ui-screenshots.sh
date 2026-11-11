#!/usr/bin/env bash
# Run emulator screenshots and preserve evidence even when a scenario fails.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
set -Eeuo pipefail
mkdir -p ui-evidence
collect() {
  adb shell wm size > ui-evidence/wm-size.txt || true
  adb shell wm density > ui-evidence/wm-density.txt || true
  adb shell settings get system font_scale > ui-evidence/font-scale.txt || true
  adb shell cmd uimode night > ui-evidence/night-mode.txt || true
  adb pull /sdcard/Android/data/org.lineageos.glimpse.dev/files/ui-screenshots/. ui-evidence/ || true
  if [[ -d app/build/outputs/androidTest-results/connected ]]; then
    cp -R app/build/outputs/androidTest-results/connected ui-evidence/test-results
  fi
}
trap collect EXIT
adb shell wm size 720x1520
adb shell settings put system font_scale 1.3
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
if [[ "$UI_MODE" == dark ]]; then
  adb shell cmd uimode night yes
else
  adb shell cmd uimode night no
fi
set +e
./gradlew :app:connectedDebugAndroidTest --stacktrace 2>&1 | tee "${RUNNER_TEMP}/ui-gradle.log"
status=${PIPESTATUS[0]}
set -e
if (( status != 0 )); then
  python3 scripts/report-ui-diagnostics.py --log "${RUNNER_TEMP}/ui-gradle.log" \
    --tests 'app/build/outputs/androidTest-results/connected/**/*.xml'
fi
exit "$status"
