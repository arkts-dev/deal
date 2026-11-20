"""Executable coverage launcher/merge/failure controls; no JaCoCo install needed."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("coverage_report", ROOT / "tools/coverage-report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class CoverageControls(unittest.TestCase):
    def test_bounded_merge_and_rejections(self):
        with tempfile.TemporaryDirectory(prefix="coverage merge ") as temporary:
            build = Path(temporary)
            inputs = build / "jacoco-processes"
            inputs.mkdir()
            for i in range(260):
                (inputs / f"process-{i}.exec").write_text(json.dumps([i]))
            (inputs / "process-killed.exec").touch()
            (build / "jacoco.exec").write_text("stale")
            calls = []

            def merge(command, check):
                self.assertTrue(check)
                self.assertEqual(command[:4], ["java", "-jar", "cli", "merge"])
                files = command[4:-2]
                self.assertLessEqual(len(files), 128)
                output = command[-1]
                self.assertNotIn(output, files)
                values = set()
                for file in files:
                    values.update(json.loads(Path(file).read_text()))
                Path(output).write_text(json.dumps(sorted(values)))
                calls.append(command)

            with patch.object(report.subprocess, "run", side_effect=merge):
                merged = report.merge_coverage("java", "cli", build)
            self.assertEqual(json.loads(merged.read_text()), list(range(260)))
            self.assertEqual(len(calls), 4)
            self.assertFalse(list(build.glob("jacoco-merge-*")))
            (inputs / "process-bad.exec").write_text("corrupt")
            with patch.object(report.subprocess, "run", side_effect=merge):
                with self.assertRaises(json.JSONDecodeError):
                    report.merge_coverage("java", "cli", build)
            self.assertFalse(merged.exists())
            for file in inputs.iterdir():
                file.unlink()
            with self.assertRaises(SystemExit):
                report.merge_coverage("java", "cli", build)

    def test_unrounded_thresholds(self):
        def counters(line, branch):
            return ET.fromstring(f'<report><counter type="LINE" covered="{line}" missed="{10000-line}"/>'
                                 f'<counter type="BRANCH" covered="{branch}" missed="{10000-branch}"/></report>')
        report.check_coverage(counters(8500, 7500))
        for line, branch in [(8499, 7500), (8500, 7499)]:
            with self.assertRaises(SystemExit):
                report.check_coverage(counters(line, branch))
        with self.assertRaises(SystemExit):
            report.check_coverage(ET.fromstring("<report/>"))

    def test_actual_launcher_nested_children_and_failure(self):
        with tempfile.TemporaryDirectory(prefix="coverage launcher ") as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            (root / "bin").mkdir()
            (root / "jars").mkdir()
            for name in ["jacocoagent.jar", "jacococli.jar"]:
                (root / "jars" / name).touch()
            shutil.copy(ROOT / "coverage.sh", root / "coverage.sh")
            shutil.copy(ROOT / "tools/coverage-report.py", root / "tools/coverage-report.py")
            java = root / "bin/java"
            java.write_text('''#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys
args = sys.argv[1:]
if args[0].startswith('-javaagent:'):
    options = dict(pair.split('=', 1) for pair in args.pop(0).split('=', 1)[1].split(','))
    assert options['append'] == 'false' and options['includes'] == 'deal.*'
    data = pathlib.Path(options['destfile'])
    assert data.is_absolute() and data.stat().st_size == 0
    name = args[0]
    if name.startswith('parent'):
        subprocess.run(['java', 'child-' + name], cwd='child', check=True)
    data.write_text(json.dumps([name]))
    if name == 'fail': sys.exit(7)
elif args[2] == 'version':
    print('fixture version')
elif args[2] == 'merge':
    values = set()
    for file in args[3:-2]: values.update(json.loads(pathlib.Path(file).read_text()))
    pathlib.Path(args[-1]).write_text(json.dumps(sorted(values)))
elif args[2] == 'report':
    values = json.loads(pathlib.Path(args[3]).read_text())
    assert values == ['child-parent-a', 'child-parent-b', 'parent-a', 'parent-b'], values
    xml = args[args.index('--xml') + 1]
    pathlib.Path(xml).write_text('<report><counter type="LINE" covered="85" missed="15"/><counter type="BRANCH" covered="75" missed="25"/></report>')
else:
    raise AssertionError(args)
''')
            java.chmod(0o755)
            runner = root / "run_tests.sh"
            runner.write_text('''#!/bin/bash
set -e
[ "$1" = --jobs ] && [ "$2" = 2 ]
rm -rf build
mkdir -p build/deal child
printf class > build/deal/Fixture.class
java parent-a & a=$!
java parent-b & b=$!
wait "$a"
wait "$b"
''')
            runner.chmod(0o755)
            env = dict(os.environ, PATH=str(root / "bin") + ":" + os.environ["PATH"],
                       JACOCO_DIR=str(root / "jars"))
            def run():
                return subprocess.run(["/bin/bash", "coverage.sh", "--jobs", "2"], cwd=root,
                                      env=env, capture_output=True, text=True)
            result = run()
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("Coverage passed", result.stdout)
            self.assertEqual(len(list((root / "build/jacoco-processes").glob("*.exec"))), 4)
            # A fresh invocation must discard old data rather than append to it.
            (root / "build/jacoco-processes/process-stale.exec").write_text('"stale"')
            self.assertEqual(run().returncode, 0)
            runner.write_text('#!/bin/bash\nrm -rf build\njava fail\n')
            result = run()
            self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
            self.assertNotIn("Coverage passed", result.stdout)
            self.assertFalse((root / "build/jacoco.exec").exists())
            (root / "jars/jacocoagent.jar").unlink()
            result = run()
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing required file", result.stderr)


if __name__ == "__main__":
    unittest.main()
