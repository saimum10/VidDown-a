"""When native/bundle.lock.json is UNPINNED, `fetch` pins the newest release of every tool itself (no manual step)."""
import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle_fixtures as bf  # noqa: E402
import fetch_native_bundle as fnb  # noqa: E402
import update_native_bundle as unb  # noqa: E402

TAGS = {t: bf.tag_of(t) for t in fnb.ANDROID_TOOLS}


class T(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.tmp, ignore_errors=True))
        self.src = self.tmp / "released"
        self.src.mkdir()
        self.lock = self.tmp / "bundle.lock.json"
        self.lock.write_text(json.dumps({"schema_version": 2, "status": "UNPINNED - test", "repository": bf.REPO, "tools": {},
                                         "components": [], "abis": {}}))
        self.downloads = []

    def fake_download(self, tool, tag, cache, timeout, log):
        self.downloads.append(tool)
        out = {}
        for abi, (data, _) in bf.make_release(tool, ("x86_64", "arm64-v8a")).items():
            p = self.src / fnb.artifact_name(tool, abi, bf.VERSIONS[tool])
            p.write_bytes(data)
            out[abi] = p
        return out

    def run_fetch(self, tags=None):
        tags = TAGS if tags is None else tags
        argv = ["fetch", "--lock", str(self.lock), "--cache-dir", str(self.tmp / "cache"), "--out", str(self.tmp / "out"),
                "--quiet", "--abi", "x86_64"]
        with mock.patch.object(unb, "newest_from_remote", lambda timeout: tags), \
                mock.patch.object(unb, "tag_commit", lambda tag: bf.TAG_COMMIT[unb.tool_of_tag(tag)]), \
                mock.patch.object(unb, "_download_release", self.fake_download), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as err:
            rc = fnb.main(argv)
        return rc, err.getvalue()

    def test_unpinned_manifest_is_pinned_and_fetched_in_one_command(self):
        rc, err = self.run_fetch()
        self.assertEqual(rc, 0, err)
        lock = json.loads(self.lock.read_text())
        self.assertNotIn("status", lock)
        self.assertEqual({t: lock["tools"][t]["release_tag"] for t in fnb.ANDROID_TOOLS}, TAGS)
        tree = self.tmp / "out" / "x86_64"
        self.assertTrue((tree / "jniLibs" / "x86_64" / "libffmpeg.so").is_file())
        self.assertTrue((tree / "assets" / "ytdlp-site-packages.zip").is_file())

    def test_resolved_manifest_passes_the_strict_loader(self):
        self.assertEqual(self.run_fetch()[0], 0)
        fnb.load_lock(self.lock)

    def test_every_tool_is_downloaded_once(self):
        self.assertEqual(self.run_fetch()[0], 0)
        self.assertEqual(sorted(self.downloads), sorted(fnb.ANDROID_TOOLS))

    def test_second_fetch_uses_the_pins_and_does_not_resolve_again(self):
        self.assertEqual(self.run_fetch()[0], 0)
        self.downloads.clear()
        self.assertEqual(self.run_fetch()[0], 0)
        self.assertEqual(self.downloads, [])

    def test_missing_release_gives_a_clear_error(self):
        rc, err = self.run_fetch({"yt-dlp": TAGS["yt-dlp"], "cpython": TAGS["cpython"]})
        self.assertEqual(rc, 2)
        self.assertIn("no release yet of: ffmpeg", err)

    def test_nothing_is_written_when_verification_fails(self):
        before = self.lock.read_text()

        good = self.fake_download

        def bad(tool, tag, cache, timeout, log):
            zips = good(tool, tag, cache, timeout, log)
            if tool == "ffmpeg":
                data, _ = bf.make_zip("ffmpeg", "x86_64", machine=183, bits=64)
                zips["x86_64"].write_bytes(data)
            return zips
        self.fake_download = bad
        rc, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertEqual(self.lock.read_text(), before)

    def test_health_check_on_an_unpinned_manifest_does_not_fail(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(fnb.main(["check", "--lock", str(self.lock)]), 0)


class TestRunAsScript(unittest.TestCase):
    def test_errors_while_pinning_are_clean_messages_not_tracebacks(self):
        """CI runs `python3 scripts/fetch_native_bundle.py fetch`; the error class must be the same one main() catches."""
        import os
        import subprocess
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(tmp, ignore_errors=True))
        lock = tmp / "l.json"
        lock.write_text(json.dumps({"schema_version": 2, "status": "UNPINNED", "repository": bf.REPO, "tools": {},
                                    "components": [], "abis": {}}))
        env = dict(os.environ, HTTPS_PROXY="http://127.0.0.1:9", HTTP_PROXY="http://127.0.0.1:9",
                   https_proxy="http://127.0.0.1:9", http_proxy="http://127.0.0.1:9")   # nothing reachable: fail fast, offline
        r = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "fetch_native_bundle.py"), "fetch",
                            "--lock", str(lock), "--cache-dir", str(tmp / "c"), "--out", str(tmp / "o"), "--timeout", "3"],
                           capture_output=True, text=True, env=env, timeout=120)
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("ERROR:", r.stderr)
        self.assertNotIn("Traceback", r.stderr)


if __name__ == "__main__":
    unittest.main()
