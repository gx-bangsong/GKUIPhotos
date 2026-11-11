#!/usr/bin/env python3
# Prepare an uncommitted CI debug APK blob and verifiable build metadata.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import base64
import hashlib
import json
import os
import re
from pathlib import Path

root = Path(os.environ['UI_EVIDENCE_DIR']) / 'ui-apk'
out = Path(os.environ['UI_RETURN_DIR'])
apk = (root / 'app-debug.apk').read_bytes()
badging = (root / 'badging.txt').read_text()
signer = (root / 'signer.txt').read_text()
commit = (root / 'commit.txt').read_text().strip()
if commit != os.environ['UI_SOURCE_SHA']:
    raise SystemExit('APK checkout does not match PR source')
match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
cert = re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', signer, re.M)
if not match or not cert or match.group(1) != 'org.lineageos.glimpse.dev':
    raise SystemExit('APK badging or signer verification failed')
metadata = {'commit': commit, 'applicationId': match.group(1), 'versionCode': int(match.group(2)),
            'versionName': match.group(3), 'apkSha256': hashlib.sha256(apk).hexdigest(),
            'signerSha256': cert.group(1).lower()}
(out / 'apk-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False))
(out / 'apk-blob-payload.json').write_text(json.dumps({'encoding': 'base64', 'content': base64.b64encode(apk).decode('ascii')}))
