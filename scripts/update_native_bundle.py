#!/usr/bin/env python3
"""Inspect releases of the designated native bundle repository and PROPOSE a manifest update.

The bundle repository releases every tool on its own: yt-dlp-v<version>, cpython-v<version>, ffmpeg-v<version>. A tool can
therefore be updated without touching the others (a yt-dlp update does not change CPython or FFmpeg). The desktop-only
NewPipe helper (newpipe-v<version>) is ignored: the Android app imports NewPipe Extractor with scripts/fetch_newpipe.py.

It never changes native/bundle.lock.json. It writes a proposed file, prints a unified diff against the current manifest,
and stops. (`fetch_native_bundle.py fetch` calls resolve_unpinned() itself while the manifest is UNPINNED.)
A human (or reviewing agent) accepts the change by copying the proposed file over the manifest after reading the
diff. Only the designated repository is contacted. The rolling `index` release is read only to learn which tags are newest;
every pin is taken from the tag's own assets, its SHA256SUMS.txt and the tag commit.

  python3 scripts/update_native_bundle.py discover
  python3 scripts/update_native_bundle.py propose                          newest release of every tool
  python3 scripts/update_native_bundle.py propose --tool yt-dlp            only yt-dlp moves; cpython and ffmpeg keep their pins
  python3 scripts/update_native_bundle.py propose --tag yt-dlp-v2026.10.27 a specific tag (repeatable, one per tool)
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import re
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_native_bundle as fnb  # noqa: E402

REPO = fnb.DESIGNATED_REPOSITORY
INDEX_URL = f"{REPO}/releases/download/index/components.json"
EXEC_NAMES = ("libviddown_python.so", "libffmpeg.so", "libffprobe.so")
ELF_NAMES = {183: "EM_AARCH64", 40: "EM_ARM", 3: "EM_386", 62: "EM_X86_64"}
TAG_COMPONENT = {"yt-dlp": "yt-dlp", "cpython": "cpython", "ffmpeg": "ffmpeg"}   # the component that names the tag
PY_COMPONENT_NOTES = {"yt-dlp": "Pure Python, inside ytdlp-site-packages.zip."}
NOT_VERIFIED = "NOT VERIFIED - update this only after a real emulator/device test passed"
DEFAULT_INTEGRITY = {
    "zip_sha256": "locally calculated from the downloaded file AND equal to the value listed in the release's SHA256SUMS.txt. "
                  "Both come from the same GitHub repository/release, so this is a consistency check, NOT an independently "
                  "authenticated upstream checksum.",
    "inner_sha256": "taken from the SHA256SUMS file inside each zip and re-calculated locally; equal in all cases.",
}


# --------------------------------------------------------------------------- pure helpers
def parse_sha256sums(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        if line.strip():
            digest, name = line.split(None, 1)
            if len(digest) != 64:
                raise fnb.BundleError(f"bad checksum line: {line!r}")
            out[name.strip().lstrip("*")] = digest.lower()
    return out


def tool_of_tag(tag: str) -> str:
    """'yt-dlp-v2026.10.27' -> 'yt-dlp'. Refuses moving refs, the desktop helper and anything that is not <tool>-v<version>."""
    if tag.lower() in fnb.FORBIDDEN_REFS:
        raise fnb.BundleError(f"pin an immutable release tag, not the moving ref '{tag}'")
    for tool in fnb.DESKTOP_ONLY_TOOLS:
        if tag.startswith(f"{tool}-v"):
            raise fnb.BundleError(f"'{tag}' is the desktop helper; the Android app does not use it "
                                  "(NewPipe Extractor is imported by scripts/fetch_newpipe.py)")
    for tool in fnb.ANDROID_TOOLS:
        if fnb.tag_regex(tool).match(tag):
            return tool
    raise fnb.BundleError(f"'{tag}' is not a release tag of this bundle (expected "
                          + ", ".join(f"{t}-v<version>" for t in fnb.ANDROID_TOOLS) + ")")


def version_key(version: str) -> tuple:
    return tuple(int(p) for p in version.split("."))


def newest_tags(tag_names) -> dict:
    """{tool: newest tag} from a list of git tag names (the fallback when the index release cannot be read)."""
    best: dict = {}
    for name in tag_names:
        for tool in fnb.ANDROID_TOOLS:
            m = fnb.tag_regex(tool).match(name)
            if m and (tool not in best or version_key(m.group(1)) > version_key(fnb.version_of_tag(tool, best[tool]))):
                best[tool] = name
    return best


def parse_index(text: str) -> dict:
    """{tool: tag} for the Android tools listed in the rolling index release (components.json). Other tools are ignored."""
    try:
        index = json.loads(text)
    except json.JSONDecodeError as e:
        raise fnb.BundleError(f"index components.json is not valid JSON: {e}")
    tools = index.get("tools")
    if not isinstance(tools, dict):
        raise fnb.BundleError("index components.json has no 'tools' object")
    out = {}
    for tool in fnb.ANDROID_TOOLS:
        entry = tools.get(tool)
        if not entry:
            continue
        tag = entry.get("tag", "")
        if tool_of_tag(tag) != tool:
            raise fnb.BundleError(f"index lists tag {tag!r} for '{tool}'")
        base = entry.get("base_url", "")
        if base and not base.startswith(f"{REPO}/releases/download/"):
            raise fnb.BundleError(f"index base_url for '{tool}' points outside the designated repository: {base}")
        out[tool] = tag
    return out


def _tag_matches_component(version: str, component_version: str) -> bool:
    """3.14.10 and 3.14.10.2 (a re-release from different inputs) both belong to component version 3.14.10."""
    return version == component_version or re.fullmatch(re.escape(component_version) + r"\.\d+", version) is not None


def read_zip(path: Path, tool: str, abi: str | None) -> dict:
    """Return {member: bytes}, validating the inner checksums, the ELF machine of every library and the layout."""
    if abi is not None and abi not in fnb.SUPPORTED_ABIS:
        raise fnb.BundleError(f"unsupported ABI '{abi}'")
    with zipfile.ZipFile(path) as z:
        if z.testzip() is not None:
            raise fnb.BundleError(f"{path.name} is corrupt")
        data = {n: z.read(n) for n in z.namelist() if not n.endswith("/")}
    for need in ["BUILD_INFO.json", "SHA256SUMS"] + fnb.key_files(tool, abi):
        if need not in data:
            raise fnb.BundleError(f"{path.name}: {need} missing")
    inner = parse_sha256sums(data["SHA256SUMS"].decode())
    for name, digest in inner.items():
        if name not in data or hashlib.sha256(data[name]).hexdigest() != digest:
            raise fnb.BundleError(f"{path.name}: inner checksum mismatch for {name}")
    for name, blob in data.items():
        if not name.startswith("jniLibs/"):
            continue
        if abi is None:
            raise fnb.BundleError(f"{path.name}: {tool} is ABI-independent but contains {name}")
        if not name.startswith(f"jniLibs/{abi}/"):
            raise fnb.BundleError(f"{path.name}: {name} is not under jniLibs/{abi}/")
        if fnb.elf_machine(blob) != fnb.SUPPORTED_ABIS[abi]:
            raise fnb.BundleError(f"{path.name}: {name} has the wrong ELF architecture for {abi}")
    return data


def tool_pin(tool: str, tag: str, tag_commit: str, zips: dict) -> tuple:
    """Pure function: pin one tool from already downloaded and SHA-checked zips ({abi or None: Path}).

    Returns (tool entry for the manifest, components of this tool, built_with).
    """
    version = fnb.version_of_tag(tool, tag)
    entries: dict = {}
    ref = None
    comps: dict = {}
    license_files: set = set()
    for abi, path in sorted(zips.items(), key=lambda kv: kv[0] or ""):
        if (abi is None) == fnb.PER_ABI[tool]:
            raise fnb.BundleError(f"{path.name}: {tool} is {'built per ABI' if fnb.PER_ABI[tool] else 'ABI-independent'}")
        data = read_zip(path, tool, abi)
        info = json.loads(data["BUILD_INFO.json"])
        for key, want in (("tool", tool), ("release_tag", tag), ("version_tag", version), ("abi", abi)):
            if info.get(key) != want:
                raise fnb.BundleError(f"{path.name}: BUILD_INFO {key} {info.get(key)!r} disagrees with the request ({want!r})")
        sig = (info["repo_commit"], info["content_id"], json.dumps(info["components"], sort_keys=True),
               info.get("android_ndk"), info.get("android_api_level"))
        if ref is None:
            ref, comps = (sig, info), info["components"]
        elif sig != ref[0]:
            raise fnb.BundleError(f"{path.name}: component set or build commit differs from the other ABIs of {tool}")
        license_files |= {n.split("/")[1] for n in data if n.startswith("licenses/") and n.count("/") >= 2}
        required = {n: {"sha256": hashlib.sha256(b).hexdigest(), "size": len(b)}
                    for n, b in sorted(data.items()) if n.startswith(("jniLibs/", "assets/"))}
        name = path.name
        entry = {
            "artifact": name,
            "original_repository_path": f"GitHub release asset {tag}/{name}",
            "url": f"{REPO}/releases/download/{tag}/{name}",
            "size": path.stat().st_size,
            "sha256": fnb.sha256_file(path),
            "sha256_published_in_release": True,
        }
        if abi:
            em, bits = fnb.SUPPORTED_ABIS[abi]
            entry["elf_machine"] = {"value": em, "name": ELF_NAMES[em], "bits": bits}
        entry["required_files"] = required
        if abi:
            entry["executables"] = [f"jniLibs/{abi}/{n}" for n in EXEC_NAMES if f"jniLibs/{abi}/{n}" in data]
        entries[abi] = entry
    if ref is None:
        raise fnb.BundleError(f"no zip for tool '{tool}'")
    info = ref[1]
    main = TAG_COMPONENT[tool]
    if main not in comps or not _tag_matches_component(version, comps[main]["version"]):
        raise fnb.BundleError(f"{tool}: release {tag} does not match the {main} version inside the zip "
                              f"({comps.get(main, {}).get('version')!r})")
    t = {"version": version, "release_tag": tag, "release_url": f"{REPO}/releases/tag/{tag}",
         "release_tag_commit": tag_commit, "build_commit": info["repo_commit"], "content_id": info["content_id"]}
    if fnb.PER_ABI[tool]:
        t["built_with"] = {"android_ndk": info.get("android_ndk"), "android_api_level": info.get("android_api_level")}
        t["abis"] = {abi: e for abi, e in entries.items()}
    else:
        t["artifact"] = entries[None]
    components = [{"name": n, "tool": tool, "version": c["version"], "license": c["license"], "upstream_commit": c["commit"],
                   "_license_file_shipped": n in license_files} for n, c in comps.items()]
    return t, components


def _component_record(c: dict, old: dict) -> dict:
    """Manifest entry of one component. A license counts as verified when the zip ships its license file (the rule the
    bundle's own BUILD_INFO-based entries always used); a review done by hand earlier is carried over."""
    shipped = c.pop("_license_file_shipped")
    prev = old.get(c["name"], {})
    c["notes"] = prev.get("notes") or PY_COMPONENT_NOTES.get(c["name"], "")
    if shipped:
        c["license_verified"] = True
        c["license_basis"] = (f"license string from the {c['tool']} zip's BUILD_INFO.json; "
                              f"license files ship in the zip under licenses/{c['name']}/")
    else:
        c["license_verified"] = bool(prev.get("license_verified", False)) and prev.get("license") == c["license"]
        c["license_basis"] = (prev.get("license_basis") if c["license_verified"] else
                              "UNVERIFIED: the zip ships no license file for this component")
    return c


def _sha(tools: dict, tool: str, abi: str):
    """SHA-256 of the zip a manifest pins for (tool, abi), or None."""
    t = tools.get(tool)
    if not t:
        return None
    entry = t.get("abis", {}).get(abi) if fnb.PER_ABI[tool] else t.get("artifact")
    return entry.get("sha256") if entry else None


def build_lock(pinned: dict, current: dict | None) -> dict:
    """Pure function. pinned = {tool: (tool entry, components)} for the tools that move; the others come from `current`."""
    cur_tools = (current or {}).get("tools", {}) if (current or {}).get("schema_version") == fnb.SCHEMA_VERSION else {}
    old_comps = {c["name"]: c for c in (current or {}).get("components", [])}
    tools, components = {}, []
    for tool in fnb.ANDROID_TOOLS:
        if tool in pinned:
            tools[tool] = pinned[tool][0]
            components += [_component_record(dict(c), old_comps) for c in pinned[tool][1]]
        elif tool in cur_tools:
            tools[tool] = json.loads(json.dumps(cur_tools[tool]))
            kept = [c for c in (current or {}).get("components", []) if c.get("tool") == tool]
            if not kept:
                raise fnb.BundleError(f"the current manifest carries no components for '{tool}'; propose it explicitly")
            components += kept
        else:
            raise fnb.BundleError(f"the current manifest has no pin for '{tool}': include it in this proposal")
    # an ABI is usable only when EVERY per-ABI tool has it (an APK without FFmpeg or without CPython would be broken)
    per_abi = [set(tools[t]["abis"]) for t in fnb.ANDROID_TOOLS if fnb.PER_ABI[t]]
    common = set.intersection(*per_abi)
    missing_required = [a for a in fnb.REQUIRED_ABIS if a not in common]
    if missing_required:
        raise fnb.BundleError(f"required ABI(s) missing in at least one per-ABI tool: {', '.join(missing_required)}")
    dropped = sorted(set.union(*per_abi) - common)
    for t in fnb.ANDROID_TOOLS:
        if fnb.PER_ABI[t]:
            tools[t]["abis"] = {a: e for a, e in sorted(tools[t]["abis"].items()) if a in common}
    old_abis = (current or {}).get("abis", {})
    abis = {}
    for abi in sorted(common):
        # a runtime PASS belongs to one exact combination of zips: any new or changed zip resets it
        same = bool(cur_tools) and all(
            _sha(cur_tools, t, abi) is not None and _sha(cur_tools, t, abi) == _sha(tools, t, abi) for t in fnb.ANDROID_TOOLS)
        prev = old_abis.get(abi, {}).get("verification", {}).get("runtime_on_device_or_emulator", NOT_VERIFIED)
        abis[abi] = {"verification": {
            "sha256": "PASS (local, == release SHA256SUMS.txt)",
            "zip_integrity_and_inner_checksums": "PASS",
            "elf_machine_matches_abi": "PASS",
            "runtime_on_device_or_emulator": prev if same else NOT_VERIFIED,
        }}
    lock = {
        "schema_version": fnb.SCHEMA_VERSION,
        "_comment": (current or {}).get("_comment", "Pinned native bundle manifest. Do not edit by hand: propose changes with "
                                                    "scripts/update_native_bundle.py and review the diff. Never point this at "
                                                    "main/master/latest/index."),
        "repository": REPO,
        "tools": tools,
        "integrity_model": (current or {}).get("integrity_model") or DEFAULT_INTEGRITY,
        "components": components,
        "licenses_note": (current or {}).get("licenses_note", ""),
        "abis": abis,
    }
    if dropped:
        lock["abis_note"] = ("Not usable (not released for every per-ABI tool, so left out): " + ", ".join(dropped))
    return lock


def render_diff(old: dict | None, new: dict) -> str:
    a = json.dumps(old, indent=2, sort_keys=True).splitlines() if old else []
    b = json.dumps(new, indent=2, sort_keys=True).splitlines()
    return "\n".join(difflib.unified_diff(a, b, "current manifest", "proposed manifest", lineterm="", n=1))


# ---------------------------------------------------------------- network (designated repository only)
def _get(url: str, timeout: float = 60.0) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "viddown-update-native-bundle/2"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def tag_commit(tag: str) -> str:
    out = subprocess.run(["git", "ls-remote", REPO, f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"],
                         capture_output=True, text=True, timeout=60)
    if out.returncode != 0 or not out.stdout.strip():
        raise fnb.BundleError(f"tag {tag} not found in {REPO}: {out.stderr.strip()}")
    rows = dict(reversed(line.split("\t")) for line in out.stdout.strip().splitlines())
    return rows.get(f"refs/tags/{tag}^{{}}") or rows[f"refs/tags/{tag}"]


def newest_from_remote(timeout: float) -> dict:
    """{tool: newest tag}: from the rolling index release, else from the git tags."""
    try:
        return parse_index(_get(INDEX_URL, timeout).decode("utf-8"))
    except (OSError, ValueError) as e:
        print(f"note: index release not readable ({e}); using the git tags instead", file=sys.stderr)
    out = subprocess.run(["git", "ls-remote", "--tags", "--refs", REPO], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise fnb.BundleError(f"cannot list tags: {out.stderr.strip()}")
    names = [line.split("\t")[1].removeprefix("refs/tags/") for line in out.stdout.strip().splitlines() if "\t" in line]
    return newest_tags(names)


def current_pins(path: Path) -> dict:
    try:
        lock = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return {}
    if lock.get("schema_version") != fnb.SCHEMA_VERSION:
        return {}
    return {t: e.get("release_tag", "") for t, e in lock.get("tools", {}).items()}


def resolve_unpinned(lock_path: Path, fetch_cache: Path, timeout: float, log,
                     newest=None, commit=None, download=None) -> dict:
    """Used by `fetch_native_bundle.py fetch` when the manifest is UNPINNED: pin the newest release of every tool NOW.

    Same checks as `propose` (release SHA256SUMS.txt, inner checksums, ELF machine, BUILD_INFO). The result is written over
    lock_path (in CI that is the workspace copy; commit it to make the pins permanent) and the downloaded zips are put into
    the fetch cache so they are not downloaded twice. The hooks exist for tests.
    """
    import shutil
    newest = newest or newest_from_remote
    commit = commit or tag_commit
    download = download or _download_release
    current = json.loads(lock_path.read_text()) if lock_path.exists() else None
    tags = newest(timeout)
    missing = [t for t in fnb.ANDROID_TOOLS if t not in tags]
    if missing:
        raise fnb.BundleError("the bundle repository has no release yet of: " + ", ".join(missing)
                              + " (run its build-native workflow first)")
    pinned, zips_by_tool = {}, {}
    for tool in fnb.ANDROID_TOOLS:
        tag = tags[tool]
        log(f"[{tool}] newest release is {tag}")
        zips = download(tool, tag, fetch_cache / "_resolve", timeout, log)
        pinned[tool] = tool_pin(tool, tag, commit(tag), zips)
        zips_by_tool[tool] = zips
    new = build_lock(pinned, current)
    new.pop("status", None)
    for tool, zips in zips_by_tool.items():
        for abi, path in zips.items():
            if abi is not None and abi not in new["abis"]:
                continue
            dest = fetch_cache / tool / (abi or "any") / path.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, dest)
    lock_path.write_text(json.dumps(new, indent=2) + "\n")
    return new


def cmd_discover(args) -> int:
    newest = newest_from_remote(args.timeout)
    pinned = current_pins(Path(args.lock))
    for tool in fnb.ANDROID_TOOLS:
        tag = newest.get(tool)
        have = pinned.get(tool)
        state = ("not released yet" if not tag else "NOT PINNED" if not have else
                 "up to date" if have == tag else f"pinned {have}, newer available")
        print(f"{tool:8} newest {tag or '-':28} {state}")
    print("newpipe  (desktop helper, ignored: the Android app imports NewPipe Extractor with scripts/fetch_newpipe.py)")
    return 0


def _download_release(tool: str, tag: str, cache: Path, timeout: float, log) -> dict:
    """Download every zip of one tool release and check it against the release's own SHA256SUMS.txt."""
    version = fnb.version_of_tag(tool, tag)
    base = f"{REPO}/releases/download/{tag}"
    folder = cache / tag
    folder.mkdir(parents=True, exist_ok=True)
    sums_path = folder / "SHA256SUMS.txt"
    fnb.download(f"{base}/SHA256SUMS.txt", sums_path, None, timeout, 3, 2.0, log)
    published = parse_sha256sums(sums_path.read_text())
    zips: dict = {}
    wanted = {None: fnb.artifact_name(tool, None, version)} if not fnb.PER_ABI[tool] else \
        {abi: fnb.artifact_name(tool, abi, version) for abi in fnb.SUPPORTED_ABIS}
    for abi, name in wanted.items():
        digest = published.get(name)
        if digest is None:
            continue
        dest = folder / name
        if not dest.exists() or fnb.sha256_file(dest) != digest:
            fnb.download(f"{base}/{name}", dest, None, timeout, 3, 2.0, log)
        if fnb.sha256_file(dest) != digest:
            raise fnb.BundleError(f"{name}: SHA-256 does not match the release's SHA256SUMS.txt")
        zips[abi] = dest
    if not zips:
        raise fnb.BundleError(f"release {tag} publishes none of the expected zips ({', '.join(wanted.values())})")
    return zips


def cmd_propose(args) -> int:
    lock_path, out_path = Path(args.lock), Path(args.out)
    if out_path.resolve() == lock_path.resolve():
        raise fnb.BundleError("refusing to overwrite the manifest; --out must be a different file")
    current = json.loads(lock_path.read_text()) if lock_path.exists() else None
    wanted: dict = {}
    for tag in args.tag or []:
        tool = tool_of_tag(tag)
        if tool in wanted:
            raise fnb.BundleError(f"two tags given for '{tool}'")
        wanted[tool] = tag
    for tool in args.tool or []:
        if tool in fnb.DESKTOP_ONLY_TOOLS:
            raise fnb.BundleError(f"'{tool}' is the desktop helper and is not used by the Android app")
        if tool not in fnb.ANDROID_TOOLS:
            raise fnb.BundleError(f"unknown tool '{tool}' (known: {', '.join(fnb.ANDROID_TOOLS)})")
    if not wanted:
        only = args.tool or list(fnb.ANDROID_TOOLS)
    else:
        only = [t for t in (args.tool or []) if t not in wanted]
    if only:
        newest = newest_from_remote(args.timeout)
        for tool in only:
            if tool not in newest:
                raise fnb.BundleError(f"the bundle repository has no release of '{tool}' yet")
            wanted[tool] = newest[tool]
    log = lambda m: print(m, file=sys.stderr)  # noqa: E731
    cache = Path(args.cache_dir)
    pinned = {}
    for tool in fnb.ANDROID_TOOLS:
        if tool not in wanted:
            continue
        tag = wanted[tool]
        zips = _download_release(tool, tag, cache, args.timeout, log)
        pinned[tool] = tool_pin(tool, tag, tag_commit(tag), zips)
    new = build_lock(pinned, current)
    out_path.write_text(json.dumps(new, indent=2) + "\n")
    print(render_diff(current, new))
    print(f"\nProposed manifest written to {out_path}. The current manifest was NOT changed.\n"
          f"Review the diff, then accept deliberately:  cp {out_path} {lock_path}", file=sys.stderr)
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    d = sub.add_parser("discover")
    d.add_argument("--lock", default="native/bundle.lock.json")
    d.add_argument("--timeout", type=float, default=60.0)
    d.set_defaults(func=cmd_discover)
    s = sub.add_parser("propose")
    s.add_argument("--tool", action="append", help="repeatable; move only this tool (to its newest release)")
    s.add_argument("--tag", action="append", help="repeatable; pin this exact release tag (e.g. yt-dlp-v2026.10.27)")
    s.add_argument("--lock", default="native/bundle.lock.json")
    s.add_argument("--out", default="native/bundle.lock.proposed.json")
    s.add_argument("--cache-dir", default=".cache/native-bundle-proposals")
    s.add_argument("--timeout", type=float, default=120.0)
    s.set_defaults(func=cmd_propose)
    args = p.parse_args(argv)
    try:
        return args.func(args)
    except fnb.BundleError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
