import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "collect_reports.py"


def run(root, out):
    return subprocess.run([sys.executable, str(SCRIPT), "--root", str(root), "--out", str(out)], capture_output=True, text=True)


class CollectReportsTest(unittest.TestCase):
    def test_collects_module_reports_and_skips_third_party(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            (root / "app/build/test-results/testDebugUnitTest").mkdir(parents=True)
            (root / "app/build/test-results/testDebugUnitTest/a.xml").write_text("<x/>")
            (root / "core/build/reports/lint").mkdir(parents=True)
            (root / "core/build/reports/lint/l.html").write_text("lint")
            (root / "third_party/X/build/reports").mkdir(parents=True)
            (root / "third_party/X/build/reports/no.txt").write_text("no")
            out = root / "dist/r.zip"
            p = run(root, out)
            self.assertEqual(p.returncode, 0, p.stderr)
            names = set(zipfile.ZipFile(out).namelist())
            self.assertEqual(names, {"app/build/test-results/testDebugUnitTest/a.xml", "core/build/reports/lint/l.html"})

    def test_fails_and_writes_nothing_when_there_are_no_reports(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            (root / "app/build").mkdir(parents=True)
            out = root / "dist/r.zip"
            p = run(root, out)
            self.assertEqual(p.returncode, 1)
            self.assertIn("no test or lint reports", p.stderr)
            self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
