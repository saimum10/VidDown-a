#!/usr/bin/env python3
"""Fetch the LATEST NewPipeExtractor (and the nanojson commit it needs) at build time.

NewPipeExtractor is no longer stored in this repository (no submodule, no pinned commit). Every build asks GitHub for the newest
stable release tag, clones that tag into third_party/NewPipeExtractor and the nanojson commit named by the extractor's own
gradle/libs.versions.toml into third_party/nanojson. Gradle then builds both from source as composite builds (settings.gradle.kts),
so JitPack is still never contacted. The exact result is written to third_party/newpipe.resolved.json (git-ignored) and is copied
into the APK (About screen) and into the release metadata.

  python3 scripts/fetch_newpipe.py ensure              make third_party/ match the newest stable release (what Gradle runs)
  python3 scripts/fetch_newpipe.py ensure --ref v0.26.5   pin a tag, branch or commit instead of "latest" (reproducible rebuilds)
  python3 scripts/fetch_newpipe.py ensure --update never  never ask the network when sources already exist (offline work)
  python3 scripts/fetch_newpipe.py latest              print the newest stable tag and exit
  python3 scripts/fetch_newpipe.py status              print what is currently checked out

Environment (the same knobs, for CI): NEWPIPE_REF, NEWPIPE_UPDATE (auto|never), NEWPIPE_EXTRACTOR_URL, NEWPIPE_NANOJSON_URL.
Needs only git and Python 3.11+ (tomllib).
"""
import argparse
import datetime
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EXTRACTOR_URL = "https://github.com/TeamNewPipe/NewPipeExtractor.git"
DEFAULT_NANOJSON_URL = "https://github.com/TeamNewPipe/nanojson.git"
STABLE_TAG = re.compile(r"^v(\d+(?:\.\d+)+)$")
NANOJSON_VERSION_KEY = "teamnewpipe-nanojson"
GIT_TIMEOUT_SECONDS = 300


class FetchError(Exception):
    """A readable failure: the message is shown to the person running the build."""


class NetworkError(FetchError):
    """The remote could not be reached (so an existing checkout may still be used)."""


# ------------------------------------------------------------------------------------------------ pure helpers

def stable_tags(tag_names) -> list:
    """Only plain release tags like v0.27.1; anything else (rc, beta, nightly, branch-like names) is ignored."""
    return [t for t in tag_names if STABLE_TAG.match(t)]


def tag_key(tag: str) -> tuple:
    return tuple(int(part) for part in STABLE_TAG.match(tag).group(1).split("."))


def latest_stable_tag(tag_names) -> str:
    tags = stable_tags(tag_names)
    if not tags:
        raise FetchError("The extractor repository lists no stable release tag (expected names like v0.27.1).")
    return max(tags, key=tag_key)


def parse_ls_remote_tags(output: str) -> list:
    """`git ls-remote --tags --refs` lines are '<sha>\\trefs/tags/<name>'."""
    names = []
    for line in output.splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and parts[1].startswith("refs/tags/"):
            names.append(parts[1][len("refs/tags/"):])
    return names


def nanojson_commit_from_catalog(catalog_text: str) -> str:
    """The extractor names the nanojson commit it needs in its own version catalog; we build exactly that one."""
    versions = tomllib.loads(catalog_text).get("versions", {})
    commit = versions.get(NANOJSON_VERSION_KEY)
    if not commit or not re.fullmatch(r"[0-9a-f]{7,40}", str(commit)):
        raise FetchError(
            f"gradle/libs.versions.toml of the extractor has no usable [{NANOJSON_VERSION_KEY}] version (got {commit!r}). "
            "Upstream probably changed how it depends on nanojson: update scripts/fetch_newpipe.py and third_party/nanojson-build."
        )
    return str(commit)


# ------------------------------------------------------------------------------------------------ git plumbing

def _git(args, cwd=None, check=True):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    try:
        proc = subprocess.run(["git", *args], cwd=cwd, env=env, capture_output=True, text=True, timeout=GIT_TIMEOUT_SECONDS)
    except FileNotFoundError as e:
        raise FetchError("git was not found on PATH. Install git: it is needed to fetch NewPipeExtractor at build time.") from e
    except subprocess.TimeoutExpired as e:
        raise NetworkError(f"git {' '.join(args)} timed out after {GIT_TIMEOUT_SECONDS}s") from e
    if check and proc.returncode != 0:
        message = (proc.stderr or proc.stdout).strip()
        error = NetworkError if _looks_like_network_failure(message) else FetchError
        raise error(f"git {' '.join(args)} failed: {message}")
    return proc


def _looks_like_network_failure(message: str) -> bool:
    markers = ("Could not resolve host", "unable to access", "Connection timed out", "Connection refused", "Network is unreachable",
               "early EOF", "RPC failed", "Failed to connect", "TLS", "SSL", "timed out")
    return any(m.lower() in message.lower() for m in markers)


def remote_tags(url: str) -> list:
    return parse_ls_remote_tags(_git(["ls-remote", "--tags", "--refs", url, "v*"]).stdout)


def clone_shallow(url: str, ref: str, dest: Path) -> str:
    """Shallow checkout of one tag, branch or full commit into dest (a real .git directory: the extractor's Gradle build runs git there)."""
    dest.mkdir(parents=True, exist_ok=True)
    _git(["init", "--quiet"], cwd=dest)
    _git(["remote", "add", "origin", url], cwd=dest)
    _git(["fetch", "--quiet", "--depth", "1", "origin", ref], cwd=dest)
    _git(["-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", "FETCH_HEAD"], cwd=dest)
    return _git(["rev-parse", "HEAD"], cwd=dest).stdout.strip()


def head_commit(path: Path):
    if not (path / ".git").exists():
        return None
    proc = _git(["rev-parse", "HEAD"], cwd=path, check=False)
    return proc.stdout.strip() if proc.returncode == 0 else None


def _force_remove(func, path, _exc):
    os.chmod(path, stat.S_IWRITE)  # git's object files are read-only (matters on Windows)
    func(path)


def remove_tree(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path, onexc=_force_remove) if sys.version_info >= (3, 12) else shutil.rmtree(path, onerror=_force_remove)


# ------------------------------------------------------------------------------------------------ main logic

def resolved_path(root: Path) -> Path:
    return root / "third_party" / "newpipe.resolved.json"


def read_resolved(root: Path):
    path = resolved_path(root)
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def sources_present(root: Path) -> bool:
    tp = root / "third_party"
    return (tp / "NewPipeExtractor" / "extractor" / "build.gradle.kts").is_file() and (tp / "nanojson" / "src" / "main" / "java").is_dir()


def checkout_matches(root: Path, resolved) -> bool:
    """True when the directories on disk are exactly what newpipe.resolved.json says (so nothing needs downloading)."""
    if not resolved or not sources_present(root):
        return False
    tp = root / "third_party"
    return (head_commit(tp / "NewPipeExtractor") == resolved["extractor"]["commit"]
            and head_commit(tp / "nanojson") == resolved["nanojson"]["commit"])


def ensure(root: Path, ref: str = "latest", update: str = "auto", extractor_url: str = None, nanojson_url: str = None, log=print) -> dict:
    extractor_url = extractor_url or os.environ.get("NEWPIPE_EXTRACTOR_URL") or DEFAULT_EXTRACTOR_URL
    nanojson_url = nanojson_url or os.environ.get("NEWPIPE_NANOJSON_URL") or DEFAULT_NANOJSON_URL
    ref = (ref or "latest").strip() or "latest"
    if update not in ("auto", "never"):
        raise FetchError(f"--update must be auto or never, not {update!r}")
    current = read_resolved(root)

    # 1. decide which ref we want
    if update == "never" and checkout_matches(root, current):
        log(f"NewPipeExtractor {current['extractor']['tag']} already present; update check skipped (NEWPIPE_UPDATE=never).")
        return current
    wanted = ref
    if ref == "latest":
        try:
            wanted = latest_stable_tag(remote_tags(extractor_url))
        except NetworkError as e:
            if checkout_matches(root, current):
                log(f"WARNING: could not reach {extractor_url} ({e}). Building with the copy already on disk: "
                    f"NewPipeExtractor {current['extractor']['tag']}, which may NOT be the latest release.")
                return current
            raise FetchError(f"Cannot find the latest NewPipeExtractor release and no earlier copy exists in third_party/. "
                             f"Check the network connection (or set NEWPIPE_REF=<tag> once online). Cause: {e}") from e
        log(f"Latest NewPipeExtractor release: {wanted}")
    else:
        log(f"NewPipeExtractor pinned by request: {wanted}")

    # 2. nothing to do when the disk already holds exactly that ref
    if checkout_matches(root, current) and current["extractor"].get("requested_ref") == wanted:
        log(f"NewPipeExtractor {current['extractor']['tag']} ({current['extractor']['commit'][:10]}) is up to date.")
        return current

    # 3. fetch into a temporary folder first, so a failed download never leaves a half-replaced third_party/
    third_party = root / "third_party"
    third_party.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".newpipe-fetch-", dir=third_party))
    try:
        log(f"Downloading NewPipeExtractor {wanted} from {extractor_url} ...")
        extractor_commit = clone_shallow(extractor_url, wanted, work / "NewPipeExtractor")
        if not (work / "NewPipeExtractor" / "extractor" / "build.gradle.kts").is_file():
            raise FetchError("The downloaded NewPipeExtractor has no extractor/build.gradle.kts: upstream changed its layout, "
                             "settings.gradle.kts must be adapted.")
        catalog = work / "NewPipeExtractor" / "gradle" / "libs.versions.toml"
        if not catalog.is_file():
            raise FetchError("The downloaded NewPipeExtractor has no gradle/libs.versions.toml, so the nanojson commit it needs is unknown.")
        nanojson_commit = nanojson_commit_from_catalog(catalog.read_text(encoding="utf-8"))
        log(f"It needs nanojson {nanojson_commit[:10]}; downloading from {nanojson_url} ...")
        got = clone_shallow(nanojson_url, nanojson_commit, work / "nanojson")
        if not (work / "nanojson" / "src" / "main" / "java").is_dir():
            raise FetchError("The downloaded nanojson has no src/main/java: third_party/nanojson-build must be adapted.")
        tag = wanted if STABLE_TAG.match(wanted) else None
        record = {
            "schema_version": 1,
            "note": "Written by scripts/fetch_newpipe.py at build time. Not committed. Records exactly what this build compiled.",
            "extractor": {
                "repository": extractor_url,
                "requested_ref": wanted,
                "tag": tag or wanted,
                "commit": extractor_commit,
            },
            "nanojson": {"repository": nanojson_url, "commit": got},
            "fetched_at_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        # 4. swap in
        for name in ("NewPipeExtractor", "nanojson"):
            remove_tree(third_party / name)
            shutil.move(str(work / name), str(third_party / name))
        resolved_path(root).write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    finally:
        remove_tree(work)
    log(f"NewPipeExtractor {record['extractor']['tag']} ({extractor_commit[:10]}) + nanojson {got[:10]} are ready in third_party/.")
    return record


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p_ensure = sub.add_parser("ensure", help="make third_party/ hold the wanted NewPipeExtractor")
    p_ensure.add_argument("--ref", default=os.environ.get("NEWPIPE_REF", "latest"), help='"latest" (default), or a tag/branch/commit')
    p_ensure.add_argument("--update", default=os.environ.get("NEWPIPE_UPDATE", "auto"), choices=["auto", "never"])
    p_ensure.add_argument("--root", default=str(ROOT))
    sub.add_parser("latest", help="print the newest stable release tag")
    p_status = sub.add_parser("status", help="print the recorded checkout")
    p_status.add_argument("--root", default=str(ROOT))
    args = parser.parse_args(argv)
    try:
        if args.command == "latest":
            print(latest_stable_tag(remote_tags(os.environ.get("NEWPIPE_EXTRACTOR_URL") or DEFAULT_EXTRACTOR_URL)))
        elif args.command == "status":
            record = read_resolved(Path(args.root))
            print(json.dumps(record, indent=2) if record else "nothing fetched yet")
        else:
            ensure(Path(args.root), ref=args.ref, update=args.update)
    except FetchError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
