#!/usr/bin/env python3
# Extract bounded, redacted failure lines without copying a full CI log.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import re
import sys
from pathlib import Path

log = Path(sys.argv[1])
if not log.is_file():
    print('No Gradle log was captured.')
    raise SystemExit(0)
patterns = re.compile(r'(^> Task .* FAILED|^FAILURE:|^e: |^\s*error:|AssertionError|^\s*\* What went wrong:|^\s*> Execution failed for task|^\s*Caused by:)', re.I)
lines = [line.strip() for line in log.read_text(errors='replace').splitlines() if patterns.search(line)]
for line in lines[-15:]:
    line = re.sub(r'https?://\S+|file://\S+', '[url]', line, flags=re.I)
    line = re.sub(r'(?i)(bearer|token|password|secret|signature|sig|authorization)\s*[:= ]\s*\S+', r'\1=[redacted]', line)
    print(line[:500])
if not lines:
    print('No matching failure lines; see the Actions job log if the job failed.')
