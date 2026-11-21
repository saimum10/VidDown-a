import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import summarize_junit as sj  # noqa: E402

OK = '<testsuite name="s" tests="2"><testcase classname="a.B" name="one"/><testcase classname="a.B" name="two"/></testsuite>'
BAD = '<testsuite name="s" tests="2"><testcase classname="a.B" name="one"/><testcase classname="a.B" name="two"><failure message="boom"/></testcase></testsuite>'
SKIP = '<testsuite name="s" tests="1"><testcase classname="a.B" name="one"><skipped/></testcase></testsuite>'


def run(xml):
    d = Path(tempfile.mkdtemp())
    if xml is not None:
        (d / "TEST-x.xml").write_text(xml)
    return sj.summarize(d, "t")


class T(unittest.TestCase):
    def test_all_pass(self):
        text, ok = run(OK)
        self.assertTrue(ok)
        self.assertIn("2 tests, 0 failed", text)

    def test_failure_fails(self):
        text, ok = run(BAD)
        self.assertFalse(ok)
        self.assertIn("FAIL `a.B.two`: boom", text)

    def test_no_results_is_failure(self):
        text, ok = run(None)
        self.assertFalse(ok)
        self.assertIn("NO TEST RESULTS", text)

    def test_several_directories_are_combined(self):
        a, b = Path(tempfile.mkdtemp()), Path(tempfile.mkdtemp())
        (a / "TEST-a.xml").write_text(OK)
        (b / "TEST-b.xml").write_text(OK)
        text, ok = sj.summarize([a, b], "t")
        self.assertTrue(ok)
        self.assertIn("4 tests, 0 failed", text)

    def test_skipped_is_not_a_pass(self):
        self.assertFalse(run(SKIP)[1])


if __name__ == "__main__":
    unittest.main()
