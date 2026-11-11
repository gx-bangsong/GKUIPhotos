#!/usr/bin/env python3
# Package sanitized emulator evidence and derive conservative XML-bounds diagnostics.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import argparse
import base64
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from itertools import combinations
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--evidence', required=True)
parser.add_argument('--jobs', required=True)
parser.add_argument('--out', required=True)
args = parser.parse_args()
root = Path(args.evidence)
out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
names = ('ui-baseline-light', 'ui-baseline-dark', 'ui-candidate-light', 'ui-candidate-dark')
expected = ('markup-idle', 'markup-brush', 'markup-brush-scroll', 'markup-brush-small', 'markup-brush-small-scroll', 'markup-mosaic', 'markup-text-selected',
            'markup-text-black', 'markup-sticker', 'collage-two', 'collage-four', 'collage-four-layouts', 'collage-six', 'collage-selected', 'collage-landscape')
pattern = re.compile(r'^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')


def clean(text):
    text = re.sub(r'https?://\S+|file://\S+', '[url]', text, flags=re.I)
    text = re.sub(r'(?i)(bearer|token|password|secret|signature|sig|authorization)\s*[:= ]\s*\S+', r'\1=[redacted]', text)
    return text.replace('\r', ' ').replace('\n', ' ')[:450]


def analyze(path, density):
    tree = ET.parse(path)
    found = []
    def visit(node, ancestry):
        attrs = node.attrib
        match = pattern.fullmatch(attrs.get('bounds', ''))
        if match and attrs.get('package') == 'org.lineageos.glimpse.dev' and attrs.get('clickable') == 'true' and attrs.get('enabled') == 'true':
            x1, y1, x2, y2 = map(int, match.groups())
            found.append({'id': attrs.get('resource-id') or attrs.get('content-desc') or attrs.get('text') or attrs.get('class', ''),
                          'bounds': [x1, y1, x2, y2], 'ancestry': ancestry, 'self': id(node)})
        for child in node:
            visit(child, ancestry + (id(node),))
    visit(tree.getroot(), ())
    minimum = density * 48 / 160
    small = [{'id': clean(x['id']), 'bounds': x['bounds']} for x in found
             if x['bounds'][2] - x['bounds'][0] < minimum or x['bounds'][3] - x['bounds'][1] < minimum]
    overlap = []
    for a, b in combinations(found, 2):
        if a['self'] in b['ancestry'] or b['self'] in a['ancestry']:
            continue
        x1, y1, x2, y2 = a['bounds']
        p1, q1, p2, q2 = b['bounds']
        if min(x2, p2) > max(x1, p1) and min(y2, q2) > max(y1, q1):
            overlap.append({'first': clean(a['id']), 'second': clean(b['id']), 'first_bounds': a['bounds'], 'second_bounds': b['bounds']})
    return {'clickable_nodes': len(found), 'small_candidates': small, 'overlap_candidates': overlap,
            'limitations': 'Visible accessibility bounds only; TouchDelegate, clipping, text truncation, and canvas occlusion require manual review.'}


reports = {}
for name in names:
    folder = root / name
    density_file = folder / 'wm-density.txt'
    density_text = density_file.read_text(errors='replace') if density_file.is_file() else ''
    densities = re.findall(r'(?:Physical|Override) density:\s*(\d+)', density_text)
    density = int(densities[-1]) if densities else None
    for item in expected:
        key = f'{name}/{item}'
        xml = folder / f'{item}.xml'
        png = folder / f'{item}.png'
        if not xml.is_file() or not png.is_file() or not density:
            reports[key] = {'status': 'missing screenshot, XML or density'}
        else:
            try:
                reports[key] = {'status': 'analyzed', 'density_dpi': density, **analyze(xml, density)}
            except (ET.ParseError, ValueError) as error:
                reports[key] = {'status': 'invalid XML', 'error': clean(str(error))}
(out / 'bounds-report.json').write_text(json.dumps(reports, ensure_ascii=False, indent=2))

jobs_json = json.loads(Path(args.jobs).read_text())
job_rows = []
for job in jobs_json.get('jobs', []):
    if job['name'] == 'lint' or job['name'].startswith('screenshots ('):
        job_rows.append({'name': job['name'], 'status': job.get('conclusion') or job.get('status')})
(out / 'job-status.json').write_text(json.dumps(job_rows, indent=2))

lint = root / 'ui-lint-evidence' / 'lint-results-debug.xml'
lint_errors = []
if lint.is_file():
    try:
        for issue in ET.parse(lint).getroot().findall('issue'):
            if issue.get('severity') in ('Error', 'Fatal'):
                loc = issue.find('location')
                lint_errors.append({'id': issue.get('id'), 'message': clean(issue.get('message', '')),
                                    'line': loc.get('line') if loc is not None else None})
    except ET.ParseError:
        lint_errors.append({'id': 'parse-error', 'message': 'Lint report XML is invalid'})
else:
    lint_errors.append({'id': 'unavailable', 'message': 'Lint XML report was not captured'})
(out / 'lint-errors.json').write_text(json.dumps(lint_errors, ensure_ascii=False, indent=2))

with zipfile.ZipFile(out / 'ui-evidence.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for name in names:
        folder = root / name
        for item in expected:
            for extension in ('png', 'xml'):
                file = folder / f'{item}.{extension}'
                if file.is_file():
                    archive.write(file, f'{name}/{file.name}')
        for filename in ('wm-size.txt', 'wm-density.txt', 'font-scale.txt', 'night-mode.txt', 'failure-tail.txt'):
            file = folder / filename
            if file.is_file():
                archive.write(file, f'{name}/{filename}')
    # Public APK identity diagnostics only; never include the binary or signing keys here.
    for filename in ('badging.txt', 'signer.txt', 'commit.txt'):
        file = root / 'ui-apk' / filename
        if file.is_file():
            archive.write(file, 'ui-apk/' + filename)
    lint_tail = root / 'ui-lint-evidence' / 'failure-tail.txt'
    if lint_tail.is_file():
        archive.write(lint_tail, 'ui-lint-evidence/failure-tail.txt')
    for filename in ('bounds-report.json', 'job-status.json', 'lint-errors.json'):
        archive.write(out / filename, filename)

payload = {'encoding': 'base64', 'content': base64.b64encode((out / 'ui-evidence.zip').read_bytes()).decode('ascii')}
(out / 'blob-payload.json').write_text(json.dumps(payload))
