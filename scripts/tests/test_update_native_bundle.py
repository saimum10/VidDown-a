"""Tests for scripts/update_native_bundle.py (pure functions only; no network)."""
import io
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bundle_fixtures as bf  # noqa: E402
import fetch_native_bundle as fnb  # noqa: E402
import update_native_bundle as unb  # noqa: E402


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.tmp, ignore_errors=True))

    def zips(self, tool, abis=("x86_64", "arm64-v8a"), **kw):
        """Write the zips of one fake release to disk: {abi or None: Path}."""
        out = {}
        for abi, (data, _) in bf.make_release(tool, abis, **kw).items():
            path = self.tmp / fnb.artifact_name(tool, abi, kw.get("version") or bf.VERSIONS[tool])
            path.write_bytes(data)
            out[abi] = path
        return out

    def pin(self, tool, abis=("x86_64", "arm64-v8a"), tag=None, **kw):
        tag = tag or bf.tag_of(tool, kw.get("version"))
        return unb.tool_pin(tool, tag, bf.TAG_COMMIT[tool], self.zips(tool, abis, **kw))

    def proposal(self, abis=("x86_64", "arm64-v8a"), current=None, only=None):
        pinned = {t: self.pin(t, abis) for t in (only or fnb.ANDROID_TOOLS)}
        return unb.build_lock(pinned, current)


class TestHelpers(unittest.TestCase):
    def test_parse_sha256sums(self):
        out = unb.parse_sha256sums(f"{'a'*64}  one.zip\n{'b'*64} *two.zip\n")
        self.assertEqual(out, {"one.zip": "a" * 64, "two.zip": "b" * 64})
        with self.assertRaises(fnb.BundleError):
            unb.parse_sha256sums("short  x.zip")

    def test_tool_of_tag(self):
        self.assertEqual(unb.tool_of_tag("yt-dlp-v2026.10.27"), "yt-dlp")
        self.assertEqual(unb.tool_of_tag("cpython-v3.14.10.2"), "cpython")
        self.assertEqual(unb.tool_of_tag("ffmpeg-v8.1.3"), "ffmpeg")

    def test_moving_refs_and_junk_refused(self):
        for bad in ("main", "latest", "index", "native-v2026.10.28", "yt-dlp-main", "yt-dlp-v"):
            with self.assertRaises(fnb.BundleError, msg=bad):
                unb.tool_of_tag(bad)

    def test_desktop_helper_tag_refused(self):
        with self.assertRaisesRegex(fnb.BundleError, "desktop helper"):
            unb.tool_of_tag("newpipe-v0.27.1")

    def test_newest_tags_compares_numbers_not_text(self):
        names = ["yt-dlp-v2026.9.1", "yt-dlp-v2026.10.26", "cpython-v3.14.9", "cpython-v3.14.10", "cpython-v3.14.10.2",
                 "ffmpeg-v8.1.3", "newpipe-v0.27.1", "index", "native-v2026.10.28"]
        self.assertEqual(unb.newest_tags(names), {"yt-dlp": "yt-dlp-v2026.10.26", "cpython": "cpython-v3.14.10.2",
                                                  "ffmpeg": "ffmpeg-v8.1.3"})

    def _index(self, **tools):
        base = f"{bf.REPO}/releases/download/"
        return json.dumps({"schema": 1, "tools": {
            t: {"tag": tag, "version": tag.split("-v")[1], "base_url": base + tag + "/", "asset": "x"} for t, tag in tools.items()}})

    def test_index_gives_the_android_tools_and_ignores_newpipe(self):
        text = self._index(**{"yt-dlp": "yt-dlp-v1.2.3", "cpython": "cpython-v3.14.10", "ffmpeg": "ffmpeg-v8.1.3",
                              "newpipe": "newpipe-v0.27.1"})
        self.assertEqual(unb.parse_index(text), {"yt-dlp": "yt-dlp-v1.2.3", "cpython": "cpython-v3.14.10",
                                                 "ffmpeg": "ffmpeg-v8.1.3"})

    def test_index_with_a_tag_of_another_tool_is_rejected(self):
        with self.assertRaises(fnb.BundleError):
            unb.parse_index(self._index(ffmpeg="cpython-v3.14.10"))

    def test_index_pointing_outside_the_repository_is_rejected(self):
        bad = json.dumps({"tools": {"ffmpeg": {"tag": "ffmpeg-v8.1.3", "base_url": "https://example.com/x/"}}})
        with self.assertRaisesRegex(fnb.BundleError, "outside the designated repository"):
            unb.parse_index(bad)

    def test_index_that_is_not_json(self):
        with self.assertRaises(fnb.BundleError):
            unb.parse_index("<html>")


class TestToolPin(Base):
    def test_yt_dlp_pin(self):
        t, comps = self.pin("yt-dlp")
        self.assertEqual(t["release_tag"], "yt-dlp-v2099.1.1")
        self.assertEqual(t["version"], "2099.1.1")
        self.assertEqual(t["release_tag_commit"], bf.TAG_COMMIT["yt-dlp"])
        self.assertEqual(t["build_commit"], bf.BUILD["yt-dlp"])
        self.assertEqual(t["content_id"], bf.CONTENT["yt-dlp"])
        self.assertNotIn("abis", t)
        self.assertIn("assets/ytdlp-site-packages.zip", t["artifact"]["required_files"])
        self.assertEqual({c["name"] for c in comps}, {"yt-dlp", "py-idna"})

    def test_per_abi_pins_carry_elf_info_and_executables(self):
        t, _ = self.pin("ffmpeg")
        self.assertEqual(set(t["abis"]), {"x86_64", "arm64-v8a"})
        e = t["abis"]["arm64-v8a"]
        self.assertEqual(e["elf_machine"], {"value": 183, "name": "EM_AARCH64", "bits": 64})
        self.assertEqual(e["executables"], ["jniLibs/arm64-v8a/libffmpeg.so", "jniLibs/arm64-v8a/libffprobe.so"])
        self.assertEqual(t["built_with"], {"android_ndk": "27.3", "android_api_level": 24})

    def test_required_files_only_cover_what_the_app_packages(self):
        t, _ = self.pin("cpython", ("x86_64",))
        keys = set(t["abis"]["x86_64"]["required_files"])
        self.assertTrue(all(k.startswith(("jniLibs/", "assets/")) for k in keys))
        self.assertIn("assets/python-lib-3.14-x86_64.zip", keys)

    def test_re_release_tag_with_suffix_is_accepted(self):
        zips = {}
        data, _ = bf.make_zip("cpython", "x86_64", version="3.14.10.2")
        p = self.tmp / "viddown-native-cpython-x86_64-3.14.10.2.zip"
        p.write_bytes(data)
        zips["x86_64"] = p
        t, _ = unb.tool_pin("cpython", "cpython-v3.14.10.2", "e" * 40, zips)
        self.assertEqual(t["version"], "3.14.10.2")

    def test_zip_of_another_release_rejected(self):
        zips = self.zips("ffmpeg")
        with self.assertRaisesRegex(fnb.BundleError, "disagrees with the request"):
            unb.tool_pin("ffmpeg", "ffmpeg-v8.1.4", "f" * 40, zips)

    def test_component_version_must_match_the_tag(self):
        data, _ = bf.make_zip("ffmpeg", "x86_64", version="9.0.0", build_info={"tool": "ffmpeg"})
        p = self.tmp / "viddown-native-ffmpeg-x86_64-9.0.0.zip"
        p.write_bytes(data)
        with self.assertRaisesRegex(fnb.BundleError, "does not match the ffmpeg version"):
            unb.tool_pin("ffmpeg", "ffmpeg-v9.0.0", "f" * 40, {"x86_64": p})

    def test_inner_checksum_mismatch_rejected(self):
        import io as _io
        import zipfile
        data, _ = bf.make_zip("yt-dlp", None)
        src = zipfile.ZipFile(_io.BytesIO(data))
        buf = _io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            for n in src.namelist():
                z.writestr(n, b"tampered" if n == "assets/ytdlp-site-packages.zip" else src.read(n))
        p = self.tmp / "viddown-native-yt-dlp-2099.1.1.zip"
        p.write_bytes(buf.getvalue())
        with self.assertRaisesRegex(fnb.BundleError, "inner checksum"):
            unb.tool_pin("yt-dlp", "yt-dlp-v2099.1.1", "d" * 40, {None: p})

    def test_wrong_architecture_rejected(self):
        data, _ = bf.make_zip("ffmpeg", "x86_64", machine=183, bits=64)
        p = self.tmp / "viddown-native-ffmpeg-x86_64-8.1.3.zip"
        p.write_bytes(data)
        with self.assertRaisesRegex(fnb.BundleError, "wrong ELF"):
            unb.tool_pin("ffmpeg", "ffmpeg-v8.1.3", "f" * 40, {"x86_64": p})

    def test_abis_with_different_components_rejected(self):
        zips = self.zips("ffmpeg", ("x86_64",))
        data, _ = bf.make_zip("ffmpeg", "x86", build_info={"content_id": "ffffffffffff"})
        p = self.tmp / "viddown-native-ffmpeg-x86-8.1.3.zip"
        p.write_bytes(data)
        zips["x86"] = p
        with self.assertRaisesRegex(fnb.BundleError, "differs from the other ABIs"):
            unb.tool_pin("ffmpeg", "ffmpeg-v8.1.3", "f" * 40, zips)

    def test_abi_independent_tool_given_an_abi_zip_rejected(self):
        zips = self.zips("ffmpeg", ("x86_64",))
        with self.assertRaisesRegex(fnb.BundleError, "built per ABI|ABI-independent"):
            unb.tool_pin("yt-dlp", "yt-dlp-v2099.1.1", "d" * 40, {"x86_64": zips["x86_64"]})

    def test_per_abi_tool_without_abi_rejected(self):
        data, _ = bf.make_zip("yt-dlp", None)
        p = self.tmp / "x.zip"
        p.write_bytes(data)
        with self.assertRaisesRegex(fnb.BundleError, "built per ABI"):
            unb.tool_pin("cpython", "cpython-v3.14.10", "e" * 40, {None: p})


class TestBuildLock(Base):
    def test_full_proposal_is_a_valid_manifest(self):
        new = self.proposal()
        path = self.tmp / "lock.json"
        path.write_text(json.dumps(new))
        lock = fnb.load_lock(path)           # the loader accepts what the updater writes
        self.assertEqual(set(lock["tools"]), set(fnb.ANDROID_TOOLS))
        self.assertEqual(set(lock["abis"]), {"x86_64", "arm64-v8a"})

    def test_proposal_never_contains_newpipe(self):
        new = self.proposal()
        self.assertNotIn("newpipe", new["tools"])
        self.assertFalse([c for c in new["components"] if "newpipe" in c["name"]])

    def test_components_are_tagged_with_their_tool(self):
        new = self.proposal()
        by = {c["name"]: c["tool"] for c in new["components"]}
        self.assertEqual(by, {"yt-dlp": "yt-dlp", "py-idna": "yt-dlp", "cpython": "cpython", "openssl": "cpython",
                              "ffmpeg": "ffmpeg"})

    def test_runtime_verification_starts_not_verified(self):
        new = self.proposal()
        for abi in new["abis"].values():
            self.assertIn("NOT VERIFIED", abi["verification"]["runtime_on_device_or_emulator"])

    def test_license_file_in_the_zip_counts_as_verified_otherwise_not(self):
        new = self.proposal()
        self.assertTrue(all(c["license_verified"] for c in new["components"]))
        pinned = {"yt-dlp": unb.tool_pin("yt-dlp", "yt-dlp-v2099.1.1", "d" * 40, self.zips("yt-dlp", licenses=False))}
        for t in ("cpython", "ffmpeg"):
            pinned[t] = self.pin(t)
        new = unb.build_lock(pinned, None)
        by = {c["name"]: c for c in new["components"]}
        self.assertFalse(by["py-idna"]["license_verified"])
        self.assertIn("UNVERIFIED", by["py-idna"]["license_basis"])

    def test_one_tool_update_keeps_the_other_pins(self):
        current = self.proposal()
        pinned = {"yt-dlp": unb.tool_pin("yt-dlp", "yt-dlp-v2099.1.2", "9" * 40, self._new_ytdlp("2099.1.2"))}
        new = unb.build_lock(pinned, current)
        self.assertEqual(new["tools"]["yt-dlp"]["release_tag"], "yt-dlp-v2099.1.2")
        self.assertEqual(new["tools"]["cpython"], current["tools"]["cpython"])
        self.assertEqual(new["tools"]["ffmpeg"], current["tools"]["ffmpeg"])
        self.assertEqual([c for c in new["components"] if c["tool"] == "cpython"],
                         [c for c in current["components"] if c["tool"] == "cpython"])
        diff = unb.render_diff(current, new)
        self.assertNotIn('"release_tag": "cpython', "\n".join(l for l in diff.splitlines() if l.startswith(("+", "-"))))

    def _new_ytdlp(self, version):
        comps = {"yt-dlp": {"version": version, "license": "Unlicense", "commit": "1" * 40},
                 "py-idna": {"version": "3.20", "license": "BSD-3-Clause", "commit": "2" * 40}}
        data, _ = bf.make_zip("yt-dlp", None, version=version, build_info={"components": comps})
        p = self.tmp / f"viddown-native-yt-dlp-{version}.zip"
        p.write_bytes(data)
        return {None: p}

    def test_runtime_pass_survives_only_while_every_zip_is_unchanged(self):
        current = self.proposal()
        current["abis"]["x86_64"]["verification"]["runtime_on_device_or_emulator"] = "PASS (emulator API 34)"
        same = unb.build_lock({t: self.pin(t) for t in fnb.ANDROID_TOOLS}, current)
        self.assertEqual(same["abis"]["x86_64"]["verification"]["runtime_on_device_or_emulator"], "PASS (emulator API 34)")
        moved = unb.build_lock({"yt-dlp": unb.tool_pin("yt-dlp", "yt-dlp-v2099.1.2", "9" * 40, self._new_ytdlp("2099.1.2"))},
                               current)
        self.assertIn("NOT VERIFIED", moved["abis"]["x86_64"]["verification"]["runtime_on_device_or_emulator"])

    def test_reviewed_license_basis_is_carried_over(self):
        current = self.proposal()
        for c in current["components"]:
            if c["name"] == "py-idna":
                c["notes"] = "my note"
        new = unb.build_lock({"yt-dlp": self.pin("yt-dlp")}, current)
        self.assertEqual({c["name"]: c for c in new["components"]}["py-idna"]["notes"], "my note")

    def test_missing_tool_without_a_current_pin_is_an_error(self):
        with self.assertRaisesRegex(fnb.BundleError, "no pin for"):
            unb.build_lock({"yt-dlp": self.pin("yt-dlp")}, None)

    def test_old_single_zip_manifest_counts_as_no_pin(self):
        old = {"schema_version": 1, "release_tag": "native-v2026.10.28", "components": [], "abis": {}}
        with self.assertRaisesRegex(fnb.BundleError, "no pin for"):
            unb.build_lock({"yt-dlp": self.pin("yt-dlp")}, old)

    def test_abi_missing_in_one_tool_is_dropped_and_said_so(self):
        pinned = {"yt-dlp": self.pin("yt-dlp"),
                  "cpython": self.pin("cpython", ("x86_64", "arm64-v8a", "x86")),
                  "ffmpeg": self.pin("ffmpeg", ("x86_64", "arm64-v8a"))}
        new = unb.build_lock(pinned, None)
        self.assertEqual(set(new["abis"]), {"x86_64", "arm64-v8a"})
        self.assertEqual(set(new["tools"]["cpython"]["abis"]), {"x86_64", "arm64-v8a"})
        self.assertIn("x86", new["abis_note"])

    def test_required_abi_missing_is_an_error(self):
        pinned = {t: self.pin(t, ("x86_64",)) for t in fnb.ANDROID_TOOLS}
        with self.assertRaisesRegex(fnb.BundleError, "arm64-v8a"):
            unb.build_lock(pinned, None)

    def test_diff_shows_changes(self):
        old = self.proposal()
        new = json.loads(json.dumps(old))
        new["tools"]["ffmpeg"]["release_tag"] = "ffmpeg-v9.9.9"
        diff = unb.render_diff(old, new)
        self.assertIn('-      "release_tag": "ffmpeg-v8.1.3"', diff)
        self.assertIn('+      "release_tag": "ffmpeg-v9.9.9"', diff)


class TestPropose(Base):
    def run_propose(self, *argv, lock=None):
        lock = lock or self.tmp / "l.json"
        with redirect_stderr(io.StringIO()) as err:
            rc = unb.main(["propose", "--lock", str(lock), "--out", str(self.tmp / "o.json"), *argv])
        return rc, err.getvalue()

    def test_refuses_to_overwrite_manifest(self):
        lock = self.tmp / "bundle.lock.json"
        lock.write_text("{}")
        with redirect_stderr(io.StringIO()) as e:
            rc = unb.main(["propose", "--lock", str(lock), "--out", str(lock)])
        self.assertEqual(rc, 2)
        self.assertIn("refusing to overwrite", e.getvalue())
        self.assertEqual(lock.read_text(), "{}")

    def test_refuses_moving_ref(self):
        for ref in ("main", "index", "latest"):
            rc, err = self.run_propose("--tag", ref)
            self.assertEqual(rc, 2, ref)
            self.assertIn("moving ref", err)

    def test_refuses_desktop_helper_tag_and_tool(self):
        rc, err = self.run_propose("--tag", "newpipe-v0.27.1")
        self.assertEqual(rc, 2)
        self.assertIn("desktop helper", err)
        rc, err = self.run_propose("--tool", "newpipe")
        self.assertEqual(rc, 2)
        self.assertIn("desktop helper", err)

    def test_refuses_two_tags_for_one_tool(self):
        rc, err = self.run_propose("--tag", "yt-dlp-v1.0.0", "--tag", "yt-dlp-v1.0.1")
        self.assertEqual(rc, 2)
        self.assertIn("two tags", err)

    def test_refuses_an_old_style_bundle_tag(self):
        rc, err = self.run_propose("--tag", "native-v2026.10.28")
        self.assertEqual(rc, 2)
        self.assertIn("not a release tag", err)


if __name__ == "__main__":
    unittest.main()
