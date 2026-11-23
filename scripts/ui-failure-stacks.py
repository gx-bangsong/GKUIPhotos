#!/usr/bin/env python3
# Preserve sanitized, untruncated failure stacks for emulator evidence.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import argparse
import glob
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def sanitize(text):
    # Preserve line breaks and stack frames, but never publish URI values or credentials.
    text = re.sub(r'(?i)\b(?:https?|file|content)://[^\s\"\'<>]+', '[uri]', text)
    text = re.sub(r'(?i)\b(bearer)[ \t]+[^\s]+', r'\1 [redacted]', text)
    text = re.sub(r'(?i)\b(token|password|secret|signature|sig|authorization)[ \t]*[:=][ \t]*[^\s]+', r'\1=[redacted]', text)
    text = re.sub(r'(?<!\w)/(?:home/runner/work|Users|data/user|storage/emulated)/[^\s:]+', '[path]', text)
    return text.replace('\r', '')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--log', required=True)
    parser.add_argument('--tests', required=True)
    args = parser.parse_args()
    sections = []
    for filename in sorted(glob.glob(args.tests, recursive=True)):
        try:
            root = ET.parse(filename).getroot()
        except (ET.ParseError, OSError) as exc:
            sections.append(f'JUnit report could not be read: {type(exc).__name__}')
            continue
        for case in root.iter('testcase'):
            for result in case:
                if result.tag not in ('failure', 'error'):
                    continue
                name = f"{case.get('classname', '')}.{case.get('name', '')}"
                sections.append(f"JUnit failure: {name}\n{result.get('message', '')}\n{result.text or '(stack not provided by JUnit)'}")
    log = Path(args.log)
    if log.is_file():
        text = log.read_text(errors='replace')
        marker = '* Exception is:'
        if marker in text:
            exception = text.split(marker, 1)[1]
            exception = re.split(r'(?m)^BUILD (?:FAILED|SUCCESSFUL)\b', exception, maxsplit=1)[0]
            sections.append('Gradle exception stack:\n' + exception.strip())
    if not sections:
        sections.append('No JUnit failure or Gradle exception stack was captured.')
    print(sanitize('\n\n'.join(sections)))


if __name__ == '__main__':
    main()
