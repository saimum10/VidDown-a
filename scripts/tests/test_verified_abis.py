import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verified_abis as va  # noqa: E402


class T(unittest.TestCase):
    def test_only_pass_counts(self):
        lock = {"abis": {
            "x86_64": {"verification": {"runtime_on_device_or_emulator": "PASS (emulator API 34)"}},
            "arm64-v8a": {"verification": {"runtime_on_device_or_emulator": "NOT VERIFIED"}},
            "x86": {"verification": {}},
            "armeabi-v7a": {},
        }}
        self.assertEqual(va.verified_abis(lock), ["x86_64"])

    def test_committed_manifest_has_no_verified_abi_yet(self):
        lock = json.loads((Path(__file__).resolve().parents[2] / "native" / "bundle.lock.json").read_text())
        self.assertEqual(va.verified_abis(lock), [])  # update this test when a real runtime test passes

    def test_unreadable_manifest(self):
        self.assertEqual(va.main([str(Path(tempfile.mkdtemp()) / "missing.json")]), 2)


if __name__ == "__main__":
    unittest.main()
