import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gen_dependency_inventory as gdi  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


class T(unittest.TestCase):
    def setUp(self):
        self.catalog = gdi.load_catalog()
        self.lock = json.loads((ROOT / "native/bundle.lock.json").read_text())

    def test_every_catalog_entry_has_licence_metadata(self):
        self.assertEqual(gdi.missing_metadata(self.catalog), [], "add the new dependency to META/PLUGINS in scripts/gen_dependency_inventory.py")

    def test_committed_inventory_is_up_to_date(self):
        committed = (ROOT / "docs/dependencies.md").read_text(encoding="utf-8")
        self.assertEqual(committed, gdi.render(self.catalog, self.lock), "run: python3 scripts/gen_dependency_inventory.py")

    def test_inventory_lists_every_native_hash_and_flags_unverified_licences(self):
        text = gdi.render(self.catalog, self.lock)
        for t in self.lock["tools"].values():       # empty while the manifest is UNPINNED
            for e in (t["abis"].values() if "abis" in t else [t["artifact"]]):
                self.assertIn(e["sha256"], text)
        self.assertIn("**NO**", text)  # the Python dependency licences are not verified yet

    def test_pinned_manifest_lists_every_tool_and_zip(self):
        import bundle_fixtures as bf
        lock = bf.make_lock(bf.default_releases(("x86_64",)))
        text = gdi.render(self.catalog, lock)
        for tool, t in lock["tools"].items():
            self.assertIn(t["release_tag"], text)
        self.assertIn(lock["tools"]["yt-dlp"]["artifact"]["sha256"], text)
        self.assertIn(lock["tools"]["ffmpeg"]["abis"]["x86_64"]["sha256"], text)
        self.assertNotIn("UNPINNED", text)


if __name__ == "__main__":
    unittest.main()
