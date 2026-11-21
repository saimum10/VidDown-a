import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle_fixtures as bf  # noqa: E402
import release_notes as rn  # noqa: E402

ALL = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
LOCK = bf.make_lock(bf.default_releases(tuple(ALL)))


class T(unittest.TestCase):
    def test_unsigned_and_unverified_are_stated_plainly(self):
        text = rn.render(LOCK, "v9.9.9", False, "abc123", ALL)
        self.assertIn("UNSIGNED build", text)
        self.assertIn("Included without a passed runtime check: arm64-v8a, armeabi-v7a, x86, x86_64", text)
        self.assertNotIn("| PASS", text)

    def test_signed_build_has_no_unsigned_banner(self):
        self.assertNotIn("UNSIGNED", rn.render(LOCK, "v1", True, "abc", ALL))

    def test_verified_abi_is_not_listed_as_unverified(self):
        lock = json.loads(json.dumps(LOCK))
        lock["abis"]["x86_64"]["verification"]["runtime_on_device_or_emulator"] = "PASS (test)"
        text = rn.render(lock, "v1", True, "abc", ["x86_64", "x86"])
        self.assertIn("Included without a passed runtime check: x86.", text)

    def test_every_tool_release_and_zip_hash_is_listed(self):
        text = rn.render(LOCK, "v1", True, "abc", ["x86_64"])
        for tool in ("yt-dlp", "cpython", "ffmpeg"):
            t = LOCK["tools"][tool]
            self.assertIn(t["release_tag"], text)
            self.assertIn(t["release_tag_commit"], text)
            self.assertIn(t["build_commit"], text)
        self.assertIn(LOCK["tools"]["yt-dlp"]["artifact"]["sha256"], text)
        self.assertIn(LOCK["tools"]["cpython"]["abis"]["x86_64"]["sha256"], text)
        self.assertIn(LOCK["tools"]["ffmpeg"]["abis"]["x86_64"]["sha256"], text)

    def test_only_the_zips_of_the_built_abis_are_listed(self):
        text = rn.render(LOCK, "v1", True, "abc", ["x86_64"])
        self.assertNotIn(LOCK["tools"]["cpython"]["abis"]["arm64-v8a"]["sha256"], text)

    def test_newpipe_is_not_mentioned_as_a_native_tool(self):
        self.assertNotIn("newpipe", rn.render(LOCK, "v1", True, "abc", ALL).lower())

    def test_unpinned_manifest_says_so_instead_of_inventing_hashes(self):
        lock = {"repository": bf.REPO, "tools": {}, "components": [],
                "abis": {"x86_64": {"verification": {"runtime_on_device_or_emulator": "NOT VERIFIED"}}}}
        text = rn.render(lock, "v1", True, "abc", ["x86_64"])
        self.assertIn("UNPINNED", text)
        self.assertIsNone(re.search(r"[0-9a-f]{64}", text))

    def test_unknown_abi_rejected(self):
        self.assertEqual(rn.main(["--version", "v1", "--signed", "true", "--commit", "x", "--abis", "mips"]), 2)


if __name__ == "__main__":
    unittest.main()
