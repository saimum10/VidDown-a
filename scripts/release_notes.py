#!/usr/bin/env python3
"""Write the release notes for a draft GitHub release, from native/bundle.lock.json and the build facts.

Everything stated about ABIs comes from the manifest's own verification field; this script cannot make an ABI look verified.
The manifest pins one release per tool (yt-dlp, cpython, ffmpeg); the notes list each tag, its commits and every zip's SHA-256.
Usage: release_notes.py --lock native/bundle.lock.json --version v0.1.0 --signed false --commit SHA [--run-url URL] --abis a,b,c
"""
import argparse
import json
import sys
from pathlib import Path

ABI_HELP = {
    "arm64-v8a": "most phones and tablets made since about 2017 (64-bit ARM)",
    "armeabi-v7a": "older 32-bit ARM phones",
    "x86_64": "emulators, Chromebooks, some tablets (64-bit Intel/AMD)",
    "x86": "old 32-bit emulators/devices",
}


TOOL_ORDER = ("yt-dlp", "cpython", "ffmpeg")


def provenance(lock: dict, abis: list) -> list:
    """Which release of each tool is inside this APK, and the SHA-256 of every zip used for the given ABIs."""
    tools = lock.get("tools", {})
    if not tools:
        return ["* The native tools are not pinned in this manifest (status: UNPINNED); this build contains no verified native runtime.", ""]
    out = [f"* Repository: {lock['repository']}",
           "* Every tool is released on its own; the pinned releases are:"]
    for tool in TOOL_ORDER:
        t = tools[tool]
        out.append(f"  * `{t['release_tag']}`: tag commit `{t['release_tag_commit']}`, build commit `{t['build_commit']}`")
    out += ["* Zip SHA-256 (locally calculated and equal to the release's published SHA256SUMS.txt; not independently authenticated):", ""]
    out.append(f"  * `{tools['yt-dlp']['artifact']['artifact']}`: `{tools['yt-dlp']['artifact']['sha256']}`")
    for abi in abis:
        for tool in ("cpython", "ffmpeg"):
            e = tools[tool]["abis"][abi]
            out.append(f"  * `{e['artifact']}`: `{e['sha256']}`")
    return out


def render(lock: dict, version: str, signed: bool, commit: str, abis: list, run_url: str = "") -> str:
    lines = [f"# VidDown {version}", ""]
    if not signed:
        lines += ["**UNSIGNED build. Not a production release.** The release APKs were built without a signing key; Android may refuse or warn on update.", ""]
    lines += [
        f"Built from commit `{commit}`." + (f" Workflow run: {run_url}" if run_url else ""),
        "",
        "## Which APK do I install?",
        "One APK per CPU type, each containing only that CPU's native runtime (CPython, yt-dlp, FFmpeg). Pick the one that matches the device:",
        "",
        "| file contains | for |",
        "|---|---|",
    ]
    for abi in abis:
        lines.append(f"| `{abi}` | {ABI_HELP.get(abi, '')} |")
    lines += ["", "## Native runtime verification (from `native/bundle.lock.json`)", "",
              "| ABI | runtime check on a device or emulator |", "|---|---|"]
    unverified = []
    for abi in abis:
        status = lock["abis"][abi].get("verification", {}).get("runtime_on_device_or_emulator", "NOT VERIFIED")
        if not status.startswith("PASS"):
            unverified.append(abi)
        lines.append(f"| `{abi}` | {status} |")
    if unverified:
        lines += ["", f"**Included without a passed runtime check: {', '.join(unverified)}.** They were built and packaged at the owner's request. "
                  "The yt-dlp engine may not work on these ABIs; this release does not claim that it does.", ""]
    lines += ["", "## Native bundle provenance", ""] + provenance(lock, abis)
    comps = {c["name"]: c["version"] for c in lock.get("components", [])}
    shown = [f"{n} {comps[n]}" for n in ("cpython", "yt-dlp", "ffmpeg", "openssl") if n in comps]
    lines += [""] + (["Versions: " + ", ".join(shown) + ".", ""] if shown else []) + [
              "## Known limitations", "",
              "* The bundled yt-dlp has no JavaScript runtime, so some sites will fail to extract.",
              "* The bundled FFmpeg is a remux-only build (no MP3/Opus encoding, no network, no HLS/DASH demuxer).",
              "* Checksums of every file in this release: `SHA256SUMS.txt`. Exact build metadata: `BUILD_INFO.app.json`, `BUILD_INFO.native.json`.", ""]
    return "\n".join(lines)


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--lock", default="native/bundle.lock.json")
    p.add_argument("--version", required=True)
    p.add_argument("--signed", required=True, choices=["true", "false"])
    p.add_argument("--commit", required=True)
    p.add_argument("--run-url", default="")
    p.add_argument("--abis", required=True, help="comma separated")
    p.add_argument("--out", default="-")
    a = p.parse_args(argv)
    lock = json.loads(Path(a.lock).read_text(encoding="utf-8"))
    abis = [x for x in a.abis.split(",") if x]
    unknown = [x for x in abis if x not in lock["abis"]]
    if unknown:
        print(f"ERROR: ABI not in manifest: {unknown}", file=sys.stderr)
        return 2
    text = render(lock, a.version, a.signed == "true", a.commit, abis, a.run_url)
    if a.out == "-":
        print(text)
    else:
        Path(a.out).write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
