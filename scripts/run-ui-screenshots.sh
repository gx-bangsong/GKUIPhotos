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
# Use only the user-provided reference; do not launch Google Photos or transfer
# a reference fixture into it during this run.
echo 'Result: external reference probe disabled; supplied screenshots only.' > ui-evidence/google-photos-status.txt
# Candidate-only OFL test fixture, pinned to a source commit and checksum. The
# app itself never downloads fonts; this file is not committed to the repository.
if [[ "$UI_VERSION" == candidate ]]; then
  font="$RUNNER_TEMP/SourceSans3-Regular.otf"
  curl -fsSL --max-time 60 --max-filesize 1000000 \
    'https://github.com/adobe-fonts/source-sans/raw/87b37a2daaed80fcb8e8ccb0085c4d72ddade12e/OTF/SourceSans3-Regular.otf' -o "$font"
  echo '08df266400933d3178d081a45f94a08814c3e55b4b7dd2e0ff69cb1329f13ab6  '"$font" | sha256sum -c -
  # Install first so Android assigns the package's scoped external directory.
  ./gradlew :app:installDebug --stacktrace
  adb shell mkdir -p /sdcard/Android/data/org.lineageos.glimpse.dev/files
  adb push "$font" /sdcard/Android/data/org.lineageos.glimpse.dev/files/markup-test-source-sans.otf
fi
set +e
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.expectedTheme="$UI_MODE" -Pandroid.testInstrumentationRunnerArguments.uiVersion="$UI_VERSION" -Pandroid.testInstrumentationRunnerArguments.sourceSha="$(git rev-parse HEAD)" --stacktrace 2>&1 | tee "${RUNNER_TEMP}/ui-gradle.log"
status=${PIPESTATUS[0]}
set -e
python3 scripts/ui-safe-tail.py "${RUNNER_TEMP}/ui-gradle.log" > ui-evidence/failure-tail.txt
if (( status != 0 )); then
  python3 scripts/ui-failure-stacks.py --log "${RUNNER_TEMP}/ui-gradle.log" \
    --tests 'app/build/outputs/androidTest-results/connected/**/*.xml' > ui-evidence/failure-stacks.txt
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
  for name in markup-idle markup-idle-zh markup-shapes markup-density markup-brush markup-brush-scroll markup-brush-small markup-brush-small-scroll markup-mosaic markup-text-selected markup-text-black markup-sticker collage-two collage-four collage-four-layouts collage-six collage-selected collage-small collage-landscape markup-brush-zh markup-shapes-zh markup-text-zh markup-mosaic-zh markup-sticker-zh collage-four-zh collage-four-layouts-zh collage-two-layouts collage-six-layouts; do
    for extension in png xml; do
      if [[ ! -s "ui-evidence/$name.$extension" ]]; then
        echo "::error::Missing screenshot evidence: $name.$extension"
        echo "Missing screenshot evidence: $name.$extension" >> ui-evidence/failure-tail.txt
        status=1
      fi
    done
  done
fi
if (( status == 0 )) && [[ "$UI_VERSION" == candidate ]]; then
  for name in markup-dock-text-edit markup-dock-text-color markup-dock-text-background markup-dock-brush markup-dock-shapes markup-dock-mosaic-effect markup-dock-mosaic-width markup-dock-sticker markup-dock-text-restored markup-dock-non-markup markup-dock-categories markup-dock-undo markup-dock-redo markup-dock-close markup-dock-done markup-dock-narrow-large-font; do
    for extension in png xml; do
      if [[ ! -s "ui-evidence/$name.$extension" ]]; then
        echo "::error::Missing dock evidence: $name.$extension"
        status=1
      fi
    done
  done
fi
exit "$status"
