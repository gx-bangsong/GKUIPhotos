#!/usr/bin/env python3
# Report bounded lint, compiler, and instrumentation failures as GitHub annotations.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import argparse
import glob
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def safe(value):
    # Never emit URLs, query strings, bearer values, or multiline control sequences.
    value = re.sub(r'https?://\S+|file://\S+', '[url]', value, flags=re.I)
    value = re.sub(r'(?i)(bearer|token|password|secret|signature|sig)\s*[:=]\s*\S+', r'\1=[redacted]', value)
    value = value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    return value[:1200]


def error(message):
    print('::error::' + safe(message))


parser = argparse.ArgumentParser()
parser.add_argument('--log', required=True)
parser.add_argument('--lint', default='')
parser.add_argument('--tests', default='')
args = parser.parse_args()
count = 0
if args.lint and Path(args.lint).exists():
    root = ET.parse(args.lint).getroot()
    for issue in root.findall('issue'):
        if issue.get('severity') not in ('Error', 'Fatal'):
            continue
        loc = issue.find('location')
        location = '' if loc is None else f" {loc.get('file', '')}:{loc.get('line', '')}"
        error(f"Lint {issue.get('id', '')}{location}: {issue.get('message', '')}")
        count += 1
        if count >= 40:
            break
if args.tests:
    for name in sorted(glob.glob(args.tests, recursive=True)):
        try:
            root = ET.parse(name).getroot()
        except ET.ParseError:
            continue
        for case in root.iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                msg = failure.get('message') or (failure.text or '').split('\n', 1)[0]
                error(f"Test {case.get('classname', '')}.{case.get('name', '')}: {msg}")
                count += 1
if Path(args.log).exists():
    for line in Path(args.log).read_text(errors='replace').splitlines():
        # Narrow patterns avoid publishing whole logs or unrelated data.
        if re.search(r'(^e: |^e: file:|^> Task .* FAILED|^FAILURE:|^\s*error:|^\s*\* What went wrong:)', line):
            error(line.strip())
            count += 1
            if count >= 60:
                break
if count == 0:
    print('No structured lint, compiler or test failures found; inspect the Actions job log.')
