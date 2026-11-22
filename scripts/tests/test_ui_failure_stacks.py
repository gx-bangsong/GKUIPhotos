# Verify complete sanitized UI exception stacks survive evidence packaging.
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
import json
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class FailureStacksTest(unittest.TestCase):
    def test_missing_reports_are_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            result = subprocess.check_output(['python3', str(ROOT / 'scripts/ui-failure-stacks.py'),
                '--log', str(path / 'missing.log'), '--tests', str(path / '*.xml')], text=True)
            self.assertIn('No JUnit failure or Gradle exception stack was captured.', result)

    def test_full_stack_is_sanitized_and_packaged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            evidence = path / 'evidence' / 'ui-baseline-dark'
            results = evidence / 'test-results'
            results.mkdir(parents=True)
            suite = ET.Element('testsuite')
            case = ET.SubElement(suite, 'testcase', classname='example.Test', name='fails')
            failure = ET.SubElement(case, 'failure', message='SecurityException content://media/private/123')
            frames = '\n'.join(f'    at example.Frame{i}.run(Frame.java:{i})' for i in range(120))
            failure.text = 'SecurityException content://media/private/123\n' + frames + '\nCaused by: error token=private-value\n    at example.Final.run(Final.java:1)'
            ET.ElementTree(suite).write(results / 'results.xml', encoding='utf-8')
            log = path / 'gradle.log'
            log.write_text('FAILURE\n* Exception is:\njava.lang.Error\n    at gradle.First.run(First.java:1)\n    at gradle.Last.run(Last.java:2)\n')
            stack = subprocess.check_output(['python3', str(ROOT / 'scripts/ui-failure-stacks.py'),
                '--log', str(log), '--tests', str(results / '*.xml')], text=True)
            self.assertIn('at example.Frame0.run', stack)
            self.assertIn('at example.Frame119.run', stack)
            self.assertIn('at example.Final.run', stack)
            self.assertIn('at gradle.Last.run', stack)
            self.assertNotIn('content://media/private/123', stack)
            self.assertNotIn('private-value', stack)
            (evidence / 'failure-stacks.txt').write_text(stack)
            jobs = path / 'jobs.json'; jobs.write_text(json.dumps({'jobs': []}))
            out = path / 'out'
            subprocess.run(['python3', str(ROOT / 'scripts/prepare-ui-return.py'), '--evidence', str(path / 'evidence'),
                '--jobs', str(jobs), '--out', str(out)], check=True)
            with zipfile.ZipFile(out / 'ui-evidence.zip') as archive:
                packaged = archive.read('ui-baseline-dark/failure-stacks.txt').decode()
            self.assertEqual(stack, packaged)


if __name__ == '__main__':
    unittest.main()
