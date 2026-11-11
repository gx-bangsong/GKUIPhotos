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
  local pull_status=0
  adb pull /sdcard/Download/GlimpseUiTest/. ui-evidence/ || pull_status=$?
  if [[ -d app/build/outputs/androidTest-results/connected ]]; then
    cp -R app/build/outputs/androidTest-results/connected ui-evidence/test-results
  fi
  return "$pull_status"
}
trap 'collect || true' EXIT
adb shell rm -rf /sdcard/Download/GlimpseUiTest
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
python3 scripts/ui-safe-tail.py "${RUNNER_TEMP}/ui-gradle.log" > ui-evidence/failure-tail.txt
if (( status != 0 )); then
  python3 scripts/report-ui-diagnostics.py --log "${RUNNER_TEMP}/ui-gradle.log" \
    --tests 'app/build/outputs/androidTest-results/connected/**/*.xml'
fi
# A green instrumentation result is not sufficient if evidence could not be retrieved.
if collect; then
  pull_status=0
else
  pull_status=$?
fi
trap - EXIT
if (( pull_status != 0 )); then
  echo '::error::Failed to pull screenshots from the emulator.'
  echo 'Screenshot collection from emulator failed.' >> ui-evidence/failure-tail.txt
  status=1
fi
if (( status == 0 )); then
  for name in markup-idle markup-brush markup-brush-scroll markup-mosaic markup-text-selected markup-text-black markup-sticker collage-two collage-six collage-selected; do
    for extension in png xml; do
      if [[ ! -s "ui-evidence/$name.$extension" ]]; then
        echo "::error::Missing screenshot evidence: $name.$extension"
        echo "Missing screenshot evidence: $name.$extension" >> ui-evidence/failure-tail.txt
        status=1
      fi
    done
  done
fi
exit "$status"
