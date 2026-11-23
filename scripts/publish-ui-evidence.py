#!/usr/bin/env python3
# Upload the complete UI evidence in bounded Git blobs with a verifiable manifest.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(os.environ['UI_RETURN_DIR'])
repo = os.environ['GITHUB_REPOSITORY']
archive = root / 'ui-evidence.zip'
chunk_size = 4 * 1024 * 1024
parts = []
whole = hashlib.sha256()


def publish(data):
    payload = json.dumps({'encoding': 'base64', 'content': base64.b64encode(data).decode('ascii')})
    result = subprocess.run(['gh', 'api', '--method', 'POST', f'repos/{repo}/git/blobs', '--input', '-', '--jq', '.sha'],
                            input=payload, text=True, capture_output=True)
    if result.returncode:
        message = re.sub(r'https?://\S+|file://\S+', '[url]', result.stderr[:1000])
        message = re.sub(r'(?i)(bearer|token|password|secret|signature|sig|authorization)\s*[:= ]\s*\S+', r'\1=[redacted]', message)
        print('::error::Evidence blob upload failed: ' + message.replace('%', '%25').replace('\r', ' ').replace('\n', ' '), file=sys.stderr)
        raise SystemExit(result.returncode)
    sha = result.stdout.strip()
    expected = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
    if sha != expected:
        raise SystemExit('Uploaded evidence blob hash did not match local data')
    return sha

with archive.open('rb') as source:
    while True:
        chunk = source.read(chunk_size)
        if not chunk:
            break
        whole.update(chunk)
        parts.append({'git_blob': publish(chunk), 'size': len(chunk), 'sha256': hashlib.sha256(chunk).hexdigest()})
if not parts:
    raise SystemExit('Evidence archive is empty')
manifest = {'format': 'gkui-ui-evidence-chunks-v1', 'size': archive.stat().st_size,
            'sha256': whole.hexdigest(), 'parts': parts}
manifest_bytes = (json.dumps(manifest, indent=2) + '\n').encode()
(root / 'evidence-manifest.json').write_bytes(manifest_bytes)
(root / 'blob-sha.txt').write_text(publish(manifest_bytes) + '\n')
