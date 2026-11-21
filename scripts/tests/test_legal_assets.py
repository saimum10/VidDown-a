import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sync_legal_assets as sla  # noqa: E402


class T(unittest.TestCase):
    def test_in_app_legal_documents_match_the_repository(self):
        self.assertEqual(sla.stale(), [], "run: python3 scripts/sync_legal_assets.py")


if __name__ == "__main__":
    unittest.main()
