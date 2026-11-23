#!/usr/bin/env python3
# Report why a detached evidence blob upload failed: HTTP status and sanitized gh stderr.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import re
import sys
from pathlib import Path

label, stderr_path, exit_code = sys.argv[1], Path(sys.argv[2]), sys.argv[3]
raw = stderr_path.read_text(errors='replace') if stderr_path.is_file() else ''
# gh api prints "gh: <message> (HTTP <status>)" for non-2xx responses; transport errors have none.
statuses = re.findall(r'\(HTTP (\d{3})\)', raw)
http = statuses[-1] if statuses else 'none (no HTTP response in stderr)'
text = re.sub(r'https?://\S+|file://\S+', '[url]', raw)
text = re.sub(r'\bgh[pousr]_[A-Za-z0-9_]+', '[redacted]', text)
text = re.sub(r'(?i)(bearer|token|password|secret|signature|sig|authorization)\s*[:= ]\s*\S+', r'\1=[redacted]', text)
text = text.strip()[:1000] or '(empty)'
message = f'{label} failed: exit={exit_code}; http={http}; stderr={text}'
print('::error::' + message.replace('%', '%25').replace('\r', ' ').replace('\n', ' '), file=sys.stderr)
