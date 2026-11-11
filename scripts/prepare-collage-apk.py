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
package_line = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
def field(name):
    match = re.search(r"(?:^| )" + re.escape(name) + r"='([^']+)'", package_line)
    if not match:
        raise SystemExit('APK badging missing ' + name)
    return match.group(1)
application_id = field('name')
version_code = field('versionCode')
version_name = field('versionName')
cert = re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)', signer, re.M)
if application_id != 'org.lineageos.glimpse.dev' or not version_code.isdigit() or not cert:
    raise SystemExit('APK package, version, or signer verification failed')
metadata = {'commit': commit, 'applicationId': application_id, 'versionCode': int(version_code),
            'versionName': version_name, 'apkSha256': hashlib.sha256(apk).hexdigest(),
            'signerSha256': cert.group(1).replace(':', '').lower()}
(out / 'apk-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False))
(out / 'apk-blob-payload.json').write_text(json.dumps({'encoding': 'base64', 'content': base64.b64encode(apk).decode('ascii')}))
