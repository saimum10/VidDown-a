#!/usr/bin/env python3
"""Fetch and verify the pinned VidDown native bundle.

The bundle repository releases EVERY TOOL ON ITS OWN, under a tag that is the tool's own version:

    yt-dlp-v<version>    viddown-native-yt-dlp-<version>.zip              no ABI (pure Python)
    cpython-v<version>   viddown-native-cpython-<abi>-<version>.zip       CPython + OpenSSL + libffi + stdlib
    ffmpeg-v<version>    viddown-native-ffmpeg-<abi>-<version>.zip        FFmpeg + FFprobe

native/bundle.lock.json (schema 2) pins one release per tool: tag, tag commit, build commit, content id and, for every
zip, URL + size + SHA-256 + the SHA-256 of the files the app packages. This script downloads exactly those zips,
verifies them, and merges them into ONE tree per ABI, the layout the Gradle build and the app expect:

    build/native/<abi>/jniLibs/<abi>/...   (cpython + ffmpeg)
    build/native/<abi>/assets/...          (python stdlib zip, yt-dlp site-packages zip, ...)
    build/native/<abi>/metadata/ licenses/
    build/native/<abi>/provenance/<tool>/  BUILD_INFO.json, THIRD_PARTY_NOTICES.md, LICENSE, SHA256SUMS of each zip

The NewPipe helper that the bundle also releases (newpipe-v<version>) is for the DESKTOP app. It is not part of the Android
bundle and a manifest that pins it is rejected. The Android app keeps importing NewPipe Extractor the way it always did
(scripts/fetch_newpipe.py).

The ONLY source is the designated repository (https://github.com/saimumhabib/viddown-native-bundle) at the pinned tags.
There is deliberately NO fallback to upstream projects, PyPI, mirrors or any other host, and no moving ref (the rolling
`index` release is for discovery only, never for pinning). The only recovery path is a previously verified cached copy of
the exact same artifact (same file name, same SHA-256), and it is re-verified every time it is used.

Used identically on a developer machine and in GitHub Actions.

Exit codes: 0 = everything verified, 2 = failure (message on stderr).
Standard library only.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import http.client
import json
import re
import shutil
import struct
import sys
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

SCRIPT_VERSION = "2"
SCHEMA_VERSION = 2
DESIGNATED_REPOSITORY = "https://github.com/saimumhabib/viddown-native-bundle"
FORBIDDEN_REFS = {"main", "master", "latest", "head", "index"}
SUPPORTED_ABIS = {
    "arm64-v8a": (183, 64),   # EM_AARCH64
    "armeabi-v7a": (40, 32),  # EM_ARM
    "x86": (3, 32),           # EM_386
    "x86_64": (62, 64),       # EM_X86_64
}
REQUIRED_ABIS = ("arm64-v8a", "x86_64")        # the bundle's CI requires these two; the others are experimental
# The tools of the ANDROID bundle, in the order their zips are merged. True = one zip per ABI.
PER_ABI = {"cpython": True, "ffmpeg": True, "yt-dlp": False}
ANDROID_TOOLS = tuple(PER_ABI)
DESKTOP_ONLY_TOOLS = {"newpipe"}                # released by the bundle repo, never used by the Android app
PAYLOAD_DIRS = ("jniLibs", "assets", "metadata", "licenses")   # merged into one tree per ABI
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
RETRYABLE_HTTP = {408, 425, 429, 500, 502, 503, 504}
CHUNK = 1024 * 256
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX12 = re.compile(r"^[0-9a-f]{12}$")


class BundleError(Exception):
    """A verification or retrieval failure. The message is meant for humans."""


# --------------------------------------------------------------------------- naming
def tag_regex(tool: str) -> re.Pattern:
    return re.compile(rf"^{re.escape(tool)}-v(\d+(?:\.\d+)*)$")


def version_of_tag(tool: str, tag: str) -> str:
    """'cpython-v3.14.10.2' -> '3.14.10.2' (the part that goes into the zip names)."""
    m = tag_regex(tool).match(tag)
    if not m:
        raise BundleError(f"tag {tag!r} is not a release tag of tool '{tool}' (expected {tool}-v<version>)")
    return m.group(1)


def artifact_name(tool: str, abi: str | None, version: str) -> str:
    return f"viddown-native-{tool}-{abi + '-' if abi else ''}{version}.zip"


def entries_of(lock: dict, tool: str) -> dict:
    """{abi: artifact entry} for a per-ABI tool, {None: artifact entry} for yt-dlp."""
    t = lock["tools"][tool]
    return dict(t["abis"]) if PER_ABI[tool] else {None: t["artifact"]}


def key_files(tool: str, abi: str | None) -> list[str]:
    """Files without which the app cannot use the zip. They must be in the lock's required_files AND in the zip."""
    if tool == "yt-dlp":
        return ["assets/ytdlp-site-packages.zip"]
    if tool == "cpython":
        return [f"jniLibs/{abi}/libviddown_python.so"]
    return [f"jniLibs/{abi}/libffmpeg.so", f"jniLibs/{abi}/libffprobe.so"]


# --------------------------------------------------------------------------- helpers
def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def _check_entry(tool: str, abi: str | None, entry: dict, tool_lock: dict, repo: str) -> None:
    label = f"tool '{tool}'" + (f" ABI '{abi}'" if abi else "")
    for key in ("artifact", "url", "sha256", "size", "required_files") + (("elf_machine",) if abi else ()):
        if key not in entry:
            raise BundleError(f"manifest {label} is missing '{key}'")
    version = tool_lock["version"]
    want = artifact_name(tool, abi, version)
    if entry["artifact"] != want:
        raise BundleError(f"manifest {label}: artifact is {entry['artifact']!r}, expected {want!r}")
    prefix = f"{repo.rstrip('/')}/releases/download/{tool_lock['release_tag']}/"
    if entry["url"] != prefix + entry["artifact"]:
        raise BundleError(f"manifest URL for {label} is outside the pinned release: {entry['url']}")
    if not isinstance(entry["sha256"], str) or not HEX64.match(entry["sha256"]):
        raise BundleError(f"manifest sha256 for {label} is not a lowercase SHA-256 hex digest")
    if not isinstance(entry["size"], int) or entry["size"] <= 0:
        raise BundleError(f"manifest size for {label} must be a positive whole number")
    req = entry["required_files"]
    if not isinstance(req, dict) or not req:
        raise BundleError(f"manifest {label} has no required_files")
    for rel, meta in req.items():
        if not isinstance(meta, dict) or not HEX64.match(str(meta.get("sha256", ""))) or not isinstance(meta.get("size"), int):
            raise BundleError(f"manifest {label}: required file {rel} needs a sha256 and a size")
        if not rel.startswith(("jniLibs/", "assets/")):
            raise BundleError(f"manifest {label}: required file {rel} must be under jniLibs/ or assets/")
        if abi is None and rel.startswith("jniLibs/"):
            raise BundleError(f"manifest {label}: an ABI-independent tool must not ship jniLibs ({rel})")
        if abi and rel.startswith("jniLibs/") and not rel.startswith(f"jniLibs/{abi}/"):
            raise BundleError(f"manifest {label}: {rel} is not in the jniLibs/{abi}/ directory")
    for need in key_files(tool, abi):
        if need not in req:
            raise BundleError(f"manifest {label}: required file missing from the lock: {need}")
    if tool == "cpython" and not any(fnmatch.fnmatchcase(n, f"assets/python-lib-*-{abi}.zip") for n in req):
        raise BundleError(f"manifest {label}: the Python standard library zip (assets/python-lib-*-{abi}.zip) is missing")
    if abi:
        if abi not in SUPPORTED_ABIS:
            raise BundleError(f"unsupported ABI '{abi}' (supported: {', '.join(sorted(SUPPORTED_ABIS))})")
        if (entry["elf_machine"].get("value"), entry["elf_machine"].get("bits")) != SUPPORTED_ABIS[abi]:
            raise BundleError(f"manifest ELF mapping for {label} disagrees with the built-in ABI table")


def load_lock(path: Path) -> dict:
    try:
        lock = json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise BundleError(f"manifest not found: {path}")
    except json.JSONDecodeError as e:
        raise BundleError(f"manifest is not valid JSON ({path}): {e}")
    if lock.get("schema_version") != SCHEMA_VERSION:
        raise BundleError(
            f"manifest schema_version is {lock.get('schema_version')!r}; this script needs {SCHEMA_VERSION}. The bundle repository now "
            "releases every tool on its own (yt-dlp-v*, cpython-v*, ffmpeg-v*) instead of one native-v* zip per ABI. "
            "Run: python3 scripts/update_native_bundle.py propose, read the diff, then copy the proposal over the manifest."
        )
    if str(lock.get("status", "")).upper().startswith("UNPINNED") or not lock.get("tools"):
        raise BundleError(
            "manifest is UNPINNED: no tool release has been pinned yet. "
            "Run: python3 scripts/update_native_bundle.py propose, read the diff, then copy the proposal over the manifest."
        )
    for key in ("repository", "tools", "abis"):
        if key not in lock:
            raise BundleError(f"manifest is missing required key '{key}'")
    if lock["repository"].rstrip("/") != DESIGNATED_REPOSITORY:
        raise BundleError(f"manifest repository is {lock['repository']!r}; only {DESIGNATED_REPOSITORY} is allowed")
    for tool in lock["tools"]:
        if tool in DESKTOP_ONLY_TOOLS:
            raise BundleError(
                f"manifest pins '{tool}', which is the desktop helper. The Android app does not use it "
                "(NewPipe Extractor is imported by scripts/fetch_newpipe.py); remove it from the manifest"
            )
        if tool not in PER_ABI:
            raise BundleError(f"manifest pins an unknown tool '{tool}' (known: {', '.join(ANDROID_TOOLS)})")
    for tool in ANDROID_TOOLS:
        if tool not in lock["tools"]:
            raise BundleError(f"manifest does not pin the tool '{tool}'")
    abis = set(lock["abis"])
    unknown = abis - set(SUPPORTED_ABIS)
    if unknown:
        raise BundleError(f"manifest lists unsupported ABI(s): {', '.join(sorted(unknown))}")
    for tool in ANDROID_TOOLS:
        t = lock["tools"][tool]
        for key in ("version", "release_tag", "release_tag_commit", "build_commit", "content_id"):
            if key not in t:
                raise BundleError(f"manifest tool '{tool}' is missing required key '{key}'")
        if t["release_tag"].lower() in FORBIDDEN_REFS or not tag_regex(tool).match(t["release_tag"]):
            raise BundleError(f"manifest tool '{tool}' pins {t['release_tag']!r}: pin an immutable release tag "
                              f"{tool}-v<version>, never a moving ref")
        if version_of_tag(tool, t["release_tag"]) != t["version"]:
            raise BundleError(f"manifest tool '{tool}': version {t['version']!r} does not match the tag {t['release_tag']!r}")
        for key in ("release_tag_commit", "build_commit"):
            if not HEX40.match(str(t[key])):
                raise BundleError(f"manifest {tool} {key} must be a full 40-hex commit SHA")
        if not HEX12.match(str(t["content_id"])):
            raise BundleError(f"manifest {tool} content_id must be 12 hex characters")
        if PER_ABI[tool]:
            if "abis" not in t:
                raise BundleError(f"manifest tool '{tool}' is missing 'abis'")
            if set(t["abis"]) != abis:
                raise BundleError(f"manifest tool '{tool}' has ABIs {sorted(t['abis'])} but the manifest ABIs are {sorted(abis)}")
        elif "artifact" not in t:
            raise BundleError(f"manifest tool '{tool}' is missing 'artifact'")
        for abi, entry in entries_of(lock, tool).items():
            _check_entry(tool, abi, entry, t, lock["repository"])
    return lock


def effective_url(entry: dict, test_base_url: str | None) -> str:
    """Return the download URL. A test base URL is accepted ONLY for loopback hosts."""
    if not test_base_url:
        return entry["url"]
    host = urlparse(test_base_url).hostname
    if host not in LOOPBACK_HOSTS:
        raise BundleError("--test-base-url is only allowed for loopback hosts (tests only)")
    return test_base_url.rstrip("/") + "/" + entry["artifact"]


# --------------------------------------------------------------------------- download
class _Transient(Exception):
    pass


def download(url: str, dest: Path, expected_size: int | None, timeout: float, retries: int, backoff: float,
             log) -> None:
    """Download url to dest with retries for transient failures. Raises BundleError."""
    last = "no attempt made"
    attempts = retries + 1
    for attempt in range(1, attempts + 1):
        part = dest.with_suffix(dest.suffix + ".part")
        try:
            log(f"  download attempt {attempt}/{attempts}: {url}")
            req = urllib.request.Request(url, headers={"User-Agent": "viddown-fetch-native-bundle/" + SCRIPT_VERSION})
            with urllib.request.urlopen(req, timeout=timeout) as resp, open(part, "wb") as out:
                declared = resp.headers.get("Content-Length")
                got = 0
                for chunk in iter(lambda: resp.read(CHUNK), b""):
                    out.write(chunk)
                    got += len(chunk)
            if declared is not None and int(declared) != got:
                raise _Transient(f"truncated download: got {got} of {declared} bytes")
            if expected_size is not None and got != expected_size:
                # Right size is part of the pin. A different size is corruption or a different file.
                raise _Transient(f"size {got} != pinned size {expected_size}") if got < expected_size else \
                    BundleError(f"downloaded size {got} is larger than the pinned size {expected_size}")
            part.replace(dest)
            return
        except BundleError:
            part.unlink(missing_ok=True)
            raise
        except urllib.error.HTTPError as e:
            part.unlink(missing_ok=True)
            last = f"HTTP {e.code} {e.reason}"
            if e.code not in RETRYABLE_HTTP:
                raise BundleError(f"download failed, not retrying: {last} for {url}")
        except _Transient as e:
            part.unlink(missing_ok=True)
            last = str(e)
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError, http.client.HTTPException) as e:
            part.unlink(missing_ok=True)
            reason = getattr(e, "reason", e)
            last = f"{type(reason).__name__}: {reason}"
        if attempt < attempts:
            time.sleep(backoff * (2 ** (attempt - 1)))
    raise BundleError(f"all {attempts} download attempts failed for {url}; last error: {last}")


# --------------------------------------------------------------------------- validation
def elf_machine(data: bytes) -> tuple[int, int]:
    """Return (e_machine, bits) from an ELF header, or raise BundleError."""
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise BundleError("not an ELF file")
    ei_class, ei_data = data[4], data[5]
    if ei_class not in (1, 2) or ei_data != 1:
        raise BundleError("unsupported ELF class/endianness")
    (machine,) = struct.unpack_from("<H", data, 18)
    return machine, 32 if ei_class == 1 else 64


def validate_archive(path: Path, tool: str, abi: str | None, entry: dict, lock: dict) -> None:
    """Strict structural validation of a zip that already passed the SHA-256 check."""
    t = lock["tools"][tool]
    if abi is not None and abi not in SUPPORTED_ABIS:
        raise BundleError(f"unsupported ABI '{abi}' (supported: {', '.join(sorted(SUPPORTED_ABIS))})")
    if not zipfile.is_zipfile(path):
        raise BundleError(f"{path.name} is not a valid zip archive")
    with zipfile.ZipFile(path) as z:
        bad = z.testzip()
        if bad is not None:
            raise BundleError(f"{path.name}: corrupt member {bad}")
        names = set(z.namelist())
        for n in names:
            if n.startswith("/") or ".." in Path(n).parts:
                raise BundleError(f"{path.name}: unsafe member path {n!r}")
        for rel, meta in entry["required_files"].items():
            if rel not in names:
                raise BundleError(f"{path.name}: required file missing: {rel}")
            data = z.read(rel)
            if len(data) != meta["size"]:
                raise BundleError(f"{path.name}: {rel} has size {len(data)}, pinned {meta['size']}")
            if hashlib.sha256(data).hexdigest() != meta["sha256"]:
                raise BundleError(f"{path.name}: {rel} SHA-256 does not match the manifest")
            if abi and rel.startswith("jniLibs/"):
                try:
                    machine, bits = elf_machine(data)
                except BundleError as e:
                    raise BundleError(f"{path.name}: {rel}: {e}")
                want_machine, want_bits = SUPPORTED_ABIS[abi]
                if (machine, bits) != (want_machine, want_bits):
                    raise BundleError(
                        f"{path.name}: {rel} is ELF machine {machine}/{bits}-bit, expected "
                        f"{want_machine}/{want_bits}-bit for ABI {abi}"
                    )
        libs = [n for n in names if n.startswith("jniLibs/") and not n.endswith("/")]
        if abi is None and libs:
            raise BundleError(f"{path.name}: {tool} is ABI-independent but the zip contains native libraries: {libs[0]}")
        foreign = [n for n in libs if abi and not n.startswith(f"jniLibs/{abi}/")]
        if foreign:
            raise BundleError(f"{path.name}: contains libraries for another ABI: {foreign[0]}")
        if "BUILD_INFO.json" not in names:
            raise BundleError(f"{path.name}: BUILD_INFO.json missing")
        try:
            info = json.loads(z.read("BUILD_INFO.json"))
        except json.JSONDecodeError:
            raise BundleError(f"{path.name}: BUILD_INFO.json is not valid JSON")
        checks = (
            ("tool", tool, "BUILD_INFO says tool"),
            ("release_tag", t["release_tag"], "BUILD_INFO release tag"),
            ("version_tag", t["version"], "BUILD_INFO tag"),
            ("abi", abi, "BUILD_INFO says ABI"),
            ("repo_commit", t["build_commit"], "BUILD_INFO commit"),
            ("content_id", t["content_id"], "BUILD_INFO content id"),
        )
        for key, want, label in checks:
            if info.get(key) != want:
                raise BundleError(f"{path.name}: {label} {info.get(key)!r} != pinned {want!r}")


def verify_file(path: Path, tool: str, abi: str | None, entry: dict, lock: dict) -> None:
    if not path.is_file():
        raise BundleError(f"missing artifact: {path}")
    if path.stat().st_size != entry["size"]:
        raise BundleError(f"{path.name}: size {path.stat().st_size} != pinned {entry['size']}")
    actual = sha256_file(path)
    if actual != entry["sha256"]:
        raise BundleError(f"{path.name}: SHA-256 mismatch (expected {entry['sha256']}, got {actual})")
    validate_archive(path, tool, abi, entry, lock)


# --------------------------------------------------------------------------- extract
def extract_into(path: Path, dest: Path, tool: str, claimed: dict) -> None:
    """Merge one tool zip into the ABI tree. Payload dirs are shared; each zip's own metadata goes to provenance/<tool>/.

    Two tools writing the same payload path is an error: the three Android zips are meant to extract into one tree.
    """
    root = dest.resolve()
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            name = info.filename
            if "/" in name and name.split("/", 1)[0] in PAYLOAD_DIRS:
                rel = name
                other = claimed.get(rel)
                if other is not None and other != tool:
                    raise BundleError(f"both '{other}' and '{tool}' provide {rel}; the tools must not overlap")
                claimed[rel] = tool
            else:
                rel = f"provenance/{tool}/{name}"
            target = (dest / rel).resolve()
            if root not in target.parents:
                raise BundleError(f"refusing to extract outside the destination: {info.filename}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)


# --------------------------------------------------------------------------- main flow
STATIC_CHECKS = ["sha256", "zip integrity", "inner file sha256",
                 "BUILD_INFO tool/release tag/commit/content id match the manifest"]


def obtain(lock: dict, tool: str, abi: str | None, cache_dir: Path, args, log) -> tuple[Path, dict]:
    """Return a verified local copy of one zip (from the cache if it re-verifies, else downloaded) and its record."""
    entry = entries_of(lock, tool)[abi]
    label = f"{tool}/{abi}" if abi else tool
    cached = cache_dir / tool / (abi or "any") / entry["artifact"]
    origin = None
    if cached.exists():
        try:
            verify_file(cached, tool, abi, entry, lock)
            origin = "cache (re-verified)"
            log(f"[{label}] using verified cache {cached}")
        except BundleError as e:
            log(f"[{label}] cached copy rejected and deleted: {e}")
            cached.unlink()
    if origin is None:
        cached.parent.mkdir(parents=True, exist_ok=True)
        url = effective_url(entry, args.test_base_url)
        log(f"[{label}] fetching {entry['artifact']}")
        tmp = cached.with_suffix(".download")
        try:
            download(url, tmp, entry["size"], args.timeout, args.retries, args.backoff, log)
            verify_file(tmp, tool, abi, entry, lock)
        except BundleError:
            tmp.unlink(missing_ok=True)
            raise
        tmp.replace(cached)
        origin = "downloaded from the designated repository"
    record = {
        "tool": tool,
        "abi": abi,
        "release_tag": lock["tools"][tool]["release_tag"],
        "artifact": entry["artifact"],
        "sha256": entry["sha256"],
        "size": entry["size"],
        "url": entry["url"],
        "obtained": origin,
        "static_checks": STATIC_CHECKS + (["ELF machine matches ABI"] if abi else []),
    }
    return cached, record


def fetch_abis(lock: dict, abis: list[str], cache_dir: Path, out_dir: Path, args, log) -> list[dict]:
    """Fetch every artifact once, then merge cpython + ffmpeg (this ABI) + yt-dlp into out_dir/<abi>/."""
    for abi in abis:
        if abi not in SUPPORTED_ABIS:
            raise BundleError(f"unsupported ABI '{abi}' (supported: {', '.join(sorted(SUPPORTED_ABIS))})")
        if abi not in lock["abis"]:
            raise BundleError(f"ABI '{abi}' is not present in the manifest (missing artifact)")
    verified: dict[tuple, Path] = {}
    records: dict[tuple, dict] = {}

    def get(tool: str, abi: str | None) -> Path:
        key = (tool, abi)
        if key not in verified:
            verified[key], records[key] = obtain(lock, tool, abi, cache_dir, args, log)
        return verified[key]

    for abi in abis:
        dest = out_dir / abi
        if dest.exists():
            shutil.rmtree(dest)
        dest.mkdir(parents=True)
        claimed: dict[str, str] = {}
        for tool in ANDROID_TOOLS:
            extract_into(get(tool, abi if PER_ABI[tool] else None), dest, tool, claimed)
    return list(records.values())


def write_build_info(lock: dict, results: list[dict], abis: list[str], out_dir: Path) -> Path:
    tools = {}
    for tool in ANDROID_TOOLS:
        t = lock["tools"][tool]
        tools[tool] = {k: t[k] for k in ("version", "release_tag", "release_tag_commit", "build_commit", "content_id")}
    info = {
        "generated_by": f"scripts/fetch_native_bundle.py v{SCRIPT_VERSION}",
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "native_bundle_repository": lock["repository"],
        "tools": tools,
        "components": [{"name": c["name"], "version": c["version"], "license": c["license"]}
                       for c in lock.get("components", [])],
        "artifacts": results,
        "abis": {abi: {"runtime_verification_from_manifest": lock["abis"][abi].get("verification", {}).get(
            "runtime_on_device_or_emulator", "NOT VERIFIED")} for abi in abis},
        "integrity_model": lock.get("integrity_model", {}),
    }
    path = out_dir / "BUILD_INFO.native.json"
    path.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    return path


def is_unpinned(path: Path) -> bool:
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return raw.get("schema_version") == SCHEMA_VERSION and (
        str(raw.get("status", "")).upper().startswith("UNPINNED") or not raw.get("tools"))


def cmd_fetch(args) -> int:
    log = (lambda m: print(m, file=sys.stderr)) if not args.quiet else (lambda m: None)
    if is_unpinned(Path(args.lock)):
        # Nothing pinned in the repository: take the newest release of every tool now, verify it like a proposal, and write the
        # pins over the manifest (commit that file to freeze them). Nothing for a person to run.
        import update_native_bundle as unb
        log("manifest is UNPINNED: resolving the newest release of yt-dlp, cpython and ffmpeg")
        unb.resolve_unpinned(Path(args.lock), Path(args.cache_dir), args.timeout, log)
    lock = load_lock(Path(args.lock))
    abis = args.abi or sorted(lock["abis"])
    out_dir, cache_dir = Path(args.out), Path(args.cache_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    results = fetch_abis(lock, abis, cache_dir, out_dir, args, log)
    path = write_build_info(lock, results, abis, out_dir)
    print(f"OK: {len(abis)} ABI(s) from {len(results)} verified zip(s); provenance written to {path}")
    return 0


def cmd_check(args) -> int:
    """Source health: is every pinned artifact still reachable with the pinned size? No download."""
    if is_unpinned(Path(args.lock)):
        print("manifest is UNPINNED: nothing is pinned, so there is nothing to check (fetch resolves the newest releases at build time)")
        return 0
    lock = load_lock(Path(args.lock))
    problems = []
    for tool in ANDROID_TOOLS:
        for abi, entry in sorted(entries_of(lock, tool).items(), key=lambda kv: kv[0] or ""):
            label = f"{tool}/{abi}" if abi else tool
            url = effective_url(entry, args.test_base_url)
            try:
                req = urllib.request.Request(url, method="HEAD",
                                             headers={"User-Agent": "viddown-fetch-native-bundle/" + SCRIPT_VERSION})
                with urllib.request.urlopen(req, timeout=args.timeout) as resp:
                    length = resp.headers.get("Content-Length")
                    if length is not None and int(length) != entry["size"]:
                        problems.append(f"{label}: size changed ({length} != pinned {entry['size']})")
                    else:
                        print(f"{label}: reachable")
            except urllib.error.HTTPError as e:
                problems.append(f"{label}: HTTP {e.code} {e.reason}")
            except (urllib.error.URLError, TimeoutError, OSError) as e:
                problems.append(f"{label}: {getattr(e, 'reason', e)}")
    if problems:
        raise BundleError("source health check failed:\n  " + "\n  ".join(problems))
    print("OK: all pinned artifacts reachable")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    for name, fn in (("fetch", cmd_fetch), ("check", cmd_check)):
        s = sub.add_parser(name)
        s.add_argument("--lock", default="native/bundle.lock.json")
        s.add_argument("--test-base-url", default=None, help="loopback URL prefix, for tests only")
        s.add_argument("--timeout", type=float, default=60.0)
        s.set_defaults(func=fn)
        if name == "fetch":
            s.add_argument("--abi", action="append", help="repeatable; default: every ABI in the manifest")
            s.add_argument("--cache-dir", default=".cache/native-bundle")
            s.add_argument("--out", default="build/native")
            s.add_argument("--retries", type=int, default=3)
            s.add_argument("--backoff", type=float, default=2.0)
            s.add_argument("--quiet", action="store_true")
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except BundleError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    # update_native_bundle.py does `import fetch_native_bundle`; without this it would load a second copy of this file whose
    # BundleError is a different class, and errors raised while pinning would escape as a traceback instead of "ERROR: ...".
    sys.modules.setdefault("fetch_native_bundle", sys.modules["__main__"])
    sys.exit(main())
