"""Offline tests for scripts/fetch_newpipe.py: real git, but only local fixture repositories (no network)."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_newpipe as fn  # noqa: E402


def git(cwd, *args):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t.invalid", *args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout.strip()


def make_nanojson(path: Path) -> str:
    (path / "src/main/java/com/grack/nanojson").mkdir(parents=True)
    (path / "src/main/java/com/grack/nanojson/A.java").write_text("class A {}\n")
    git(path, "init", "-q")
    git(path, "add", "-A")
    git(path, "commit", "-q", "-m", "n")
    return git(path, "rev-parse", "HEAD")


def make_extractor(path: Path, nanojson_commit: str, tags) -> None:
    (path / "extractor").mkdir(parents=True)
    (path / "gradle").mkdir()
    (path / "extractor/build.gradle.kts").write_text("// x\n")
    git(path, "init", "-q")
    for i, tag in enumerate(tags):
        (path / "gradle/libs.versions.toml").write_text(f'[versions]\nteamnewpipe-nanojson = "{nanojson_commit}"\nmarker = "{tag}"\n')
        git(path, "add", "-A")
        git(path, "commit", "-q", "-m", tag)
        git(path, "tag", tag)


class PureHelpers(unittest.TestCase):
    def test_latest_is_numeric_not_alphabetic(self):
        self.assertEqual(fn.latest_stable_tag(["v0.9.0", "v0.10.0", "v0.2.9"]), "v0.10.0")
        self.assertEqual(fn.latest_stable_tag(["v0.26.5", "v0.27.1", "v0.27.0"]), "v0.27.1")

    def test_unstable_tags_are_ignored(self):
        self.assertEqual(fn.latest_stable_tag(["v0.27.1", "v0.28.0-rc1", "nightly", "v0.28.0-beta", "latest"]), "v0.27.1")

    def test_no_stable_tag_is_an_error(self):
        with self.assertRaises(fn.FetchError):
            fn.latest_stable_tag(["nightly", "v1-rc"])

    def test_parse_ls_remote(self):
        out = "aaa\trefs/tags/v0.1.0\nbbb\trefs/tags/v0.2.0\ngarbage\n"
        self.assertEqual(fn.parse_ls_remote_tags(out), ["v0.1.0", "v0.2.0"])

    def test_nanojson_commit_from_catalog(self):
        sha = "e9d656ddb49a412a5a0a5d5ef20ca7ef09549996"
        self.assertEqual(fn.nanojson_commit_from_catalog(f'[versions]\nteamnewpipe-nanojson = "{sha}"\n'), sha)

    def test_missing_or_odd_nanojson_version_is_an_error(self):
        for text in ("[versions]\njsoup = \"1\"\n", '[versions]\nteamnewpipe-nanojson = "1.2.3-final"\n'):
            with self.assertRaises(fn.FetchError):
                fn.nanojson_commit_from_catalog(text)


class EnsureWithLocalRepos(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.nano = base / "nanojson-remote"
        self.nano.mkdir()
        self.nano_sha = make_nanojson(self.nano)
        self.ext = base / "extractor-remote"
        self.ext.mkdir()
        make_extractor(self.ext, self.nano_sha, ["v0.26.5", "v0.27.1"])
        self.root = base / "project"
        (self.root / "third_party").mkdir(parents=True)
        self.logs = []

    def tearDown(self):
        self.tmp.cleanup()

    def run_ensure(self, **kw):
        return fn.ensure(self.root, extractor_url=str(self.ext), nanojson_url=str(self.nano), log=self.logs.append, **kw)

    def test_fetches_latest_and_records_it(self):
        rec = self.run_ensure()
        self.assertEqual(rec["extractor"]["tag"], "v0.27.1")
        self.assertEqual(rec["nanojson"]["commit"], self.nano_sha)
        self.assertTrue((self.root / "third_party/NewPipeExtractor/extractor/build.gradle.kts").is_file())
        self.assertTrue((self.root / "third_party/nanojson/src/main/java/com/grack/nanojson/A.java").is_file())
        self.assertTrue((self.root / "third_party/NewPipeExtractor/.git").is_dir())  # a real .git: the extractor's build runs git there
        on_disk = json.loads((self.root / "third_party/newpipe.resolved.json").read_text())
        self.assertEqual(on_disk["extractor"]["commit"], git(self.ext, "rev-parse", "v0.27.1^{commit}"))
        self.assertEqual([p.name for p in (self.root / "third_party").iterdir() if p.name.startswith(".newpipe-fetch")], [])

    def test_second_run_is_a_no_op(self):
        self.run_ensure()
        marker = self.root / "third_party/NewPipeExtractor/marker.txt"
        marker.write_text("untracked file must survive a no-op run")
        self.run_ensure()
        self.assertTrue(marker.exists())
        self.assertTrue(any("up to date" in line for line in self.logs))

    def test_new_upstream_release_replaces_the_old_checkout(self):
        self.run_ensure()
        (self.ext / "extractor/extra.txt").write_text("v0.27.2")
        git(self.ext, "add", "-A")
        git(self.ext, "commit", "-q", "-m", "next")
        git(self.ext, "tag", "v0.27.2")
        rec = self.run_ensure()
        self.assertEqual(rec["extractor"]["tag"], "v0.27.2")
        self.assertTrue((self.root / "third_party/NewPipeExtractor/extractor/extra.txt").is_file())

    def test_explicit_ref_pins_an_older_release(self):
        rec = self.run_ensure(ref="v0.26.5")
        self.assertEqual(rec["extractor"]["tag"], "v0.26.5")
        self.assertEqual(rec["extractor"]["requested_ref"], "v0.26.5")

    def test_never_skips_the_network_when_sources_exist(self):
        self.run_ensure()
        broken = fn.ensure(self.root, update="never", extractor_url="/nonexistent/remote", nanojson_url="/nonexistent/n", log=self.logs.append)
        self.assertEqual(broken["extractor"]["tag"], "v0.27.1")

    def test_unreachable_remote_with_existing_copy_warns_and_continues(self):
        self.run_ensure()
        rec = fn.ensure(self.root, extractor_url="https://invalid.invalid/none.git", nanojson_url=str(self.nano), log=self.logs.append)
        self.assertEqual(rec["extractor"]["tag"], "v0.27.1")
        self.assertTrue(any("WARNING" in line for line in self.logs))

    def test_unreachable_remote_without_copy_fails_clearly(self):
        with self.assertRaises(fn.FetchError) as ctx:
            fn.ensure(self.root, extractor_url="https://invalid.invalid/none.git", nanojson_url=str(self.nano), log=self.logs.append)
        self.assertIn("no earlier copy", str(ctx.exception))
        self.assertFalse((self.root / "third_party/NewPipeExtractor").exists())

    def test_failed_download_leaves_existing_checkout_untouched(self):
        self.run_ensure()
        before = (self.root / "third_party/newpipe.resolved.json").read_text()
        (self.ext / "gradle/libs.versions.toml").write_text('[versions]\nteamnewpipe-nanojson = "not-a-sha"\n')
        git(self.ext, "add", "-A")
        git(self.ext, "commit", "-q", "-m", "broken")
        git(self.ext, "tag", "v0.28.0")
        with self.assertRaises(fn.FetchError):
            self.run_ensure()
        self.assertEqual((self.root / "third_party/newpipe.resolved.json").read_text(), before)
        self.assertTrue((self.root / "third_party/NewPipeExtractor/extractor/build.gradle.kts").is_file())


if __name__ == "__main__":
    unittest.main()
