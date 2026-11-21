"""Tests for scripts/fetch_native_bundle.py (one pinned release PER TOOL: yt-dlp, cpython, ffmpeg).

All network behaviour is simulated by a local loopback HTTP server that can refuse connections, return 404/500,
time out, truncate the body, or serve a wrong/corrupt file. No real network is used.
Run:  python3 -m unittest discover -s scripts/tests -v
"""
import io
import json
import shutil
import socket
import sys
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle_fixtures as bf  # noqa: E402
import fetch_native_bundle as fnb  # noqa: E402

fake_elf = bf.fake_elf  # re-exported for older imports


class Server:
    """Loopback server. `mode` maps file name -> behaviour; `body` maps file name -> bytes served."""

    def __init__(self):
        self.mode, self.body, self.hits = {}, {}, {}
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _serve(self, head):
                name = self.path.rsplit("/", 1)[-1]
                outer.hits[name] = outer.hits.get(name, 0) + 1
                mode = outer.mode.get(name, "ok")
                data = outer.body.get(name)
                if data is None or mode == "404":
                    self.send_error(404)
                    return
                if mode == "500":
                    self.send_error(500)
                    return
                if mode == "timeout":
                    time.sleep(1.5)
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if head:
                    return
                if mode == "truncated":
                    self.wfile.write(data[: len(data) // 2])
                    self.wfile.flush()
                    self.close_connection = True
                    return
                self.wfile.write(data)

            def do_GET(self):
                self._serve(False)

            def do_HEAD(self):
                self._serve(True)

        class Quiet(ThreadingHTTPServer):
            def handle_error(self, request, client_address):  # client hang-ups are expected in these tests
                pass

        self.httpd = Quiet(("127.0.0.1", 0), H)
        self.httpd.daemon_threads = True
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def free_closed_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Base(unittest.TestCase):
    ABI = "x86_64"

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.tmp, ignore_errors=True))
        self.srv = Server()
        self.addCleanup(self.srv.stop)
        self.set_releases(bf.default_releases((self.ABI,)))

    def set_releases(self, releases, serve=None):
        """Install the fake releases: the manifest pins them, the server serves them (or `serve`: {name: bytes})."""
        self.releases = releases
        self.lock = bf.make_lock(releases)
        self.srv.body.clear()
        for tool, blobs in releases.items():
            for abi, (data, _) in blobs.items():
                self.srv.body[fnb.artifact_name(tool, abi, bf.VERSIONS[tool])] = data
        self.srv.body.update(serve or {})
        self.write_lock()

    def write_lock(self):
        self.lock_path = self.tmp / "bundle.lock.json"
        self.lock_path.write_text(json.dumps(self.lock))

    def name(self, tool, abi="__default__"):
        abi = (self.ABI if fnb.PER_ABI[tool] else None) if abi == "__default__" else abi
        return fnb.artifact_name(tool, abi, bf.VERSIONS[tool])

    def cache_path(self, tool):
        return self.tmp / "cache" / tool / (self.ABI if fnb.PER_ABI[tool] else "any") / self.name(tool)

    def run_fetch(self, *extra, base=None, abis=None):
        argv = ["fetch", "--lock", str(self.lock_path), "--cache-dir", str(self.tmp / "cache"),
                "--out", str(self.tmp / "out"), "--test-base-url", base or self.srv.url,
                "--timeout", "0.4", "--retries", "2", "--backoff", "0", "--quiet"]
        for a in (abis or [self.ABI]):
            argv += ["--abi", a]
        argv += list(extra)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = fnb.main(argv)
        return rc, out.getvalue(), err.getvalue()


class TestSuccess(Base):
    def test_fetch_ok_merges_the_three_tools_into_one_tree(self):
        rc, out, err = self.run_fetch()
        self.assertEqual(rc, 0, err)
        tree = self.tmp / "out" / self.ABI
        self.assertTrue((tree / "jniLibs" / self.ABI / "libviddown_python.so").is_file())   # cpython
        self.assertTrue((tree / "jniLibs" / self.ABI / "libffmpeg.so").is_file())           # ffmpeg
        self.assertTrue((tree / "jniLibs" / self.ABI / "libffprobe.so").is_file())
        self.assertTrue((tree / "assets" / f"python-lib-3.14-{self.ABI}.zip").is_file())    # cpython
        self.assertTrue((tree / "assets" / "ytdlp-site-packages.zip").is_file())            # yt-dlp (no ABI)
        self.assertTrue((tree / "licenses" / "py-idna" / "LICENSE").is_file())

    def test_each_zip_keeps_its_own_provenance_files(self):
        self.assertEqual(self.run_fetch()[0], 0)
        tree = self.tmp / "out" / self.ABI
        for tool in fnb.ANDROID_TOOLS:
            info = json.loads((tree / "provenance" / tool / "BUILD_INFO.json").read_text())
            self.assertEqual(info["tool"], tool)
            self.assertTrue((tree / "provenance" / tool / "SHA256SUMS").is_file())
        self.assertFalse((tree / "BUILD_INFO.json").exists())    # no tool may overwrite another's

    def test_provenance_lists_every_tool_and_zip(self):
        self.assertEqual(self.run_fetch()[0], 0)
        info = json.loads((self.tmp / "out" / "BUILD_INFO.native.json").read_text())
        self.assertEqual(set(info["tools"]), set(fnb.ANDROID_TOOLS))
        for tool in fnb.ANDROID_TOOLS:
            self.assertEqual(info["tools"][tool]["release_tag_commit"], bf.TAG_COMMIT[tool])
            self.assertEqual(info["tools"][tool]["release_tag"], f"{tool}-v{bf.VERSIONS[tool]}")
        sha = {a["tool"]: a["sha256"] for a in info["artifacts"]}
        for tool in fnb.ANDROID_TOOLS:
            self.assertEqual(sha[tool], fnb.entries_of(self.lock, tool)[self.ABI if fnb.PER_ABI[tool] else None]["sha256"])
        self.assertIn("NOT VERIFIED", info["abis"][self.ABI]["runtime_verification_from_manifest"])

    def test_yt_dlp_is_downloaded_once_for_several_abis(self):
        self.ABI = "x86_64"
        self.set_releases(bf.default_releases(("x86_64", "arm64-v8a")))
        rc, _, err = self.run_fetch(abis=["x86_64", "arm64-v8a"])
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.srv.hits[self.name("yt-dlp")], 1)
        for abi in ("x86_64", "arm64-v8a"):
            self.assertTrue((self.tmp / "out" / abi / "assets" / "ytdlp-site-packages.zip").is_file())
            self.assertTrue((self.tmp / "out" / abi / "jniLibs" / abi / "libffmpeg.so").is_file())

    def test_a_tool_not_in_the_request_is_not_downloaded(self):
        # only the manifest's own tools are fetched; the desktop NewPipe helper is never requested
        self.assertEqual(self.run_fetch()[0], 0)
        self.assertFalse([n for n in self.srv.hits if "newpipe" in n])


class TestFailures(Base):
    def test_connection_refused(self):
        rc, _, err = self.run_fetch(base=f"http://127.0.0.1:{free_closed_port()}")
        self.assertEqual(rc, 2)
        self.assertIn("all 3 download attempts failed", err)

    def test_http_404_not_retried(self):
        self.srv.mode[self.name("ffmpeg")] = "404"
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("HTTP 404", err)
        self.assertEqual(self.srv.hits[self.name("ffmpeg")], 1)

    def test_http_500_retried_then_fails(self):
        self.srv.mode[self.name("cpython")] = "500"
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertEqual(self.srv.hits[self.name("cpython")], 3)
        self.assertIn("HTTP 500", err)

    def test_timeout(self):
        self.srv.mode[self.name("yt-dlp")] = "timeout"
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("all 3 download attempts failed", err)

    def test_truncated_artifact_rejected(self):
        self.srv.mode[self.name("cpython")] = "truncated"
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertRegex(err, "truncated|size")
        self.assertFalse(self.cache_path("cpython").exists())

    def test_wrong_sha256_rejected_and_not_cached(self):
        self.lock["tools"]["yt-dlp"]["artifact"]["sha256"] = "0" * 64
        self.write_lock()
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("SHA-256 mismatch", err)
        self.assertFalse(self.cache_path("yt-dlp").exists())

    def test_corrupted_same_size_rejected(self):
        name = self.name("ffmpeg")
        data = bytearray(self.srv.body[name])
        data[len(data) // 2] ^= 0xFF
        self.srv.body[name] = bytes(data)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("SHA-256 mismatch", err)

    def test_missing_abi_in_manifest(self):
        rc, _, err = self.run_fetch(abis=["arm64-v8a"])
        self.assertEqual(rc, 2)
        self.assertIn("not present in the manifest", err)

    def test_unsupported_abi(self):
        rc, _, err = self.run_fetch(abis=["riscv64"])
        self.assertEqual(rc, 2)
        self.assertIn("unsupported ABI", err)

    def _replace(self, tool, data, required=None):
        """Serve different bytes for one tool and re-pin the manifest to them (the zip itself is what is wrong)."""
        abi = self.ABI if fnb.PER_ABI[tool] else None
        required = required if required is not None else self.releases[tool][abi][1]
        self.releases[tool][abi] = (data, required)
        keep = bf.default_releases((self.ABI,))
        keep[tool] = self.releases[tool]
        self.set_releases(keep)

    def test_invalid_archive_with_matching_sha(self):
        self._replace("ffmpeg", b"this is not a zip file" * 50, {"jniLibs/x86_64/libffmpeg.so": {"sha256": "1" * 64, "size": 1},
                                                                   "jniLibs/x86_64/libffprobe.so": {"sha256": "1" * 64, "size": 1}})
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("not a valid zip", err)

    def test_required_file_missing_in_zip(self):
        full = bf.make_zip("cpython", self.ABI)[1]
        data, _ = bf.make_zip("cpython", self.ABI, drop="assets/python-version.txt")
        self._replace("cpython", data, full)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("required file missing", err)

    def test_wrong_architecture_rejected(self):
        data, req = bf.make_zip("ffmpeg", self.ABI, machine=183, bits=64)  # arm64 code inside the x86_64 zip
        self._replace("ffmpeg", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("expected 62/64-bit", err)

    def test_foreign_abi_library_rejected(self):
        extra = {"jniLibs/arm64-v8a/libextra.so": fake_elf(183, 64)}
        data, req = bf.make_zip("cpython", self.ABI, extra=extra)
        self._replace("cpython", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("another ABI", err)

    def test_yt_dlp_zip_with_native_libraries_rejected(self):
        data, req = bf.make_zip("yt-dlp", None, extra={"jniLibs/x86_64/libx.so": fake_elf(62, 64)})
        self._replace("yt-dlp", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("ABI-independent", err)

    def test_build_info_commit_mismatch_rejected(self):
        data, req = bf.make_zip("cpython", self.ABI, build_info={"repo_commit": bf.TAG_COMMIT["cpython"]})
        self._replace("cpython", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("BUILD_INFO commit", err)

    def test_zip_of_another_tool_rejected(self):
        data, req = bf.make_zip("ffmpeg", self.ABI, build_info={"tool": "cpython"})
        self._replace("ffmpeg", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("BUILD_INFO says tool", err)

    def test_build_info_release_tag_mismatch_rejected(self):
        data, req = bf.make_zip("yt-dlp", None, build_info={"release_tag": "yt-dlp-v2000.1.1"})
        self._replace("yt-dlp", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("BUILD_INFO release tag", err)

    def test_build_info_content_id_mismatch_rejected(self):
        data, req = bf.make_zip("ffmpeg", self.ABI, build_info={"content_id": "ffffffffffff"})
        self._replace("ffmpeg", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("content id", err)

    def test_zip_slip_member_rejected(self):
        data, req = bf.make_zip("yt-dlp", None, extra={"../evil.txt": b"x"})
        self._replace("yt-dlp", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("unsafe member path", err)
        self.assertFalse((self.tmp / "evil.txt").exists())

    def test_two_tools_providing_the_same_file_rejected(self):
        # ffmpeg zip also ships a file cpython ships: the merged tree would be ambiguous
        clash = f"assets/python-version.txt"
        data, req = bf.make_zip("ffmpeg", self.ABI, extra={clash: b"3.14.10\n"})
        req = dict(req)
        self._replace("ffmpeg", data, req)
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("must not overlap", err)


class TestCache(Base):
    def test_cache_used_when_server_is_down_and_reverified(self):
        self.assertEqual(self.run_fetch()[0], 0)
        rc, _, err = self.run_fetch(base=f"http://127.0.0.1:{free_closed_port()}")
        self.assertEqual(rc, 0, err)
        info = json.loads((self.tmp / "out" / "BUILD_INFO.native.json").read_text())
        self.assertTrue(all("cache" in a["obtained"] for a in info["artifacts"]))

    def test_tampered_cache_rejected_and_refetched(self):
        self.assertEqual(self.run_fetch()[0], 0)
        cached = self.cache_path("ffmpeg")
        b = bytearray(cached.read_bytes())
        b[10] ^= 0xFF
        cached.write_bytes(bytes(b))
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 0, err)
        self.assertEqual(fnb.sha256_file(cached), self.lock["tools"]["ffmpeg"]["abis"][self.ABI]["sha256"])

    def test_tampered_cache_and_server_down_fails(self):
        self.assertEqual(self.run_fetch()[0], 0)
        cached = self.cache_path("yt-dlp")
        cached.write_bytes(b"corrupt")
        rc, _, err = self.run_fetch(base=f"http://127.0.0.1:{free_closed_port()}")
        self.assertEqual(rc, 2)
        self.assertFalse(cached.exists())

    def test_updating_one_tool_downloads_only_that_tool(self):
        """The point of per-tool releases: a new yt-dlp does not re-download CPython or FFmpeg."""
        self.assertEqual(self.run_fetch()[0], 0)
        self.srv.hits.clear()
        new = bf.make_release("yt-dlp", extra={"assets/extra.txt": b"x"})
        rel = dict(self.releases)
        rel["yt-dlp"] = new
        self.lock = bf.make_lock(rel)
        self.srv.body[self.name("yt-dlp")] = new[None][0]
        self.write_lock()
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 0, err)
        self.assertEqual(set(self.srv.hits), {self.name("yt-dlp")})


class TestManifestRules(Base):
    def _bad(self, mutate, needle):
        mutate(self.lock)
        self.write_lock()
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn(needle, err)

    def test_old_single_zip_manifest_explains_what_to_do(self):
        self._bad(lambda l: l.update(schema_version=1), "propose")

    def test_loader_refuses_an_unpinned_manifest(self):
        # `fetch` resolves an UNPINNED manifest itself first (see test_auto_resolve.py); the strict loader never accepts one
        self.lock.update(status="UNPINNED", tools={})
        self.write_lock()
        with self.assertRaisesRegex(fnb.BundleError, "UNPINNED"):
            fnb.load_lock(self.lock_path)

    def test_moving_ref_rejected(self):
        self._bad(lambda l: l["tools"]["yt-dlp"].update(release_tag="main"), "moving ref")

    def test_rolling_index_cannot_be_pinned(self):
        self._bad(lambda l: l["tools"]["ffmpeg"].update(release_tag="index"), "moving ref")

    def test_tag_of_another_tool_rejected(self):
        self._bad(lambda l: l["tools"]["ffmpeg"].update(release_tag="cpython-v3.14.10"), "moving ref")

    def test_version_must_match_tag(self):
        self._bad(lambda l: l["tools"]["cpython"].update(version="3.14.11"), "does not match the tag")

    def test_foreign_repository_rejected(self):
        self._bad(lambda l: l.update(repository="https://github.com/someone/else"), "only")

    def test_short_commit_rejected(self):
        self._bad(lambda l: l["tools"]["cpython"].update(release_tag_commit="abc123"), "40-hex")

    def test_missing_build_commit_rejected(self):
        self._bad(lambda l: l["tools"]["ffmpeg"].pop("build_commit"), "build_commit")

    def test_url_outside_pinned_release_rejected(self):
        def m(l):
            l["tools"]["ffmpeg"]["abis"][self.ABI]["url"] = "https://example.com/evil.zip"
        self._bad(m, "outside the pinned release")

    def test_artifact_name_must_follow_the_pattern(self):
        def m(l):
            e = l["tools"]["yt-dlp"]["artifact"]
            e["artifact"] = "viddown-native-something-else.zip"
            e["url"] = f"{bf.REPO}/releases/download/{bf.tag_of('yt-dlp')}/viddown-native-something-else.zip"
        self._bad(m, "expected")

    def test_desktop_newpipe_helper_is_rejected(self):
        def m(l):
            l["tools"]["newpipe"] = {"version": "0.27.1", "release_tag": "newpipe-v0.27.1"}
        self._bad(m, "desktop helper")

    def test_missing_tool_rejected(self):
        self._bad(lambda l: l["tools"].pop("ffmpeg"), "does not pin the tool 'ffmpeg'")

    def test_tool_abis_must_match_manifest_abis(self):
        self._bad(lambda l: l["abis"].update({"arm64-v8a": {"verification": {}}}), "has ABIs")

    def test_key_file_must_be_pinned(self):
        self._bad(lambda l: l["tools"]["ffmpeg"]["abis"][self.ABI]["required_files"].pop("jniLibs/x86_64/libffprobe.so"),
                  "required file missing from the lock")

    def test_yt_dlp_cannot_pin_native_libraries(self):
        def m(l):
            l["tools"]["yt-dlp"]["artifact"]["required_files"]["jniLibs/x86_64/libx.so"] = {"sha256": "1" * 64, "size": 1}
        self._bad(m, "ABI-independent")

    def test_test_base_url_must_be_loopback(self):
        rc, _, err = self.run_fetch(base="http://example.com")
        self.assertEqual(rc, 2)
        self.assertIn("loopback", err)

    def test_missing_manifest(self):
        self.lock_path.unlink()
        rc, _, err = self.run_fetch()
        self.assertEqual(rc, 2)
        self.assertIn("manifest not found", err)


class TestHealthCheck(Base):
    def _check(self, base=None):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = fnb.main(["check", "--lock", str(self.lock_path), "--test-base-url", base or self.srv.url,
                           "--timeout", "0.4"])
        return rc, out.getvalue(), err.getvalue()

    def test_ok_checks_every_tool(self):
        rc, out, _ = self._check()
        self.assertEqual(rc, 0)
        for label in ("yt-dlp: reachable", f"cpython/{self.ABI}: reachable", f"ffmpeg/{self.ABI}: reachable"):
            self.assertIn(label, out)

    def test_404_reported(self):
        self.srv.mode[self.name("ffmpeg")] = "404"
        rc, _, err = self._check()
        self.assertEqual(rc, 2)
        self.assertIn("HTTP 404", err)


class TestRealLock(unittest.TestCase):
    """The committed manifest must itself satisfy the loader rules (no network)."""

    LOCK = Path(__file__).resolve().parents[2] / "native" / "bundle.lock.json"

    def test_committed_lock_is_valid_or_explicitly_unpinned(self):
        raw = json.loads(self.LOCK.read_text())
        if str(raw.get("status", "")).startswith("UNPINNED"):
            self.skipTest("native/bundle.lock.json is UNPINNED: run scripts/update_native_bundle.py propose against the "
                          "bundle repository's per-tool releases, review the diff, copy the proposal over the manifest")
        lock = fnb.load_lock(self.LOCK)
        self.assertEqual(set(lock["tools"]), set(fnb.ANDROID_TOOLS))
        for tool in fnb.ANDROID_TOOLS:
            for abi, e in fnb.entries_of(lock, tool).items():
                if abi:
                    self.assertEqual((e["elf_machine"]["value"], e["elf_machine"]["bits"]), fnb.SUPPORTED_ABIS[abi])

    def test_committed_lock_never_pins_the_desktop_helper(self):
        raw = json.loads(self.LOCK.read_text())
        self.assertFalse(set(raw.get("tools", {})) & fnb.DESKTOP_ONLY_TOOLS)


if __name__ == "__main__":
    unittest.main()
