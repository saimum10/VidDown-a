"""Shared fake bundle releases for the script tests (one release PER TOOL, like the real bundle repository).

Nothing here touches the network: the zips are built in memory and served by a loopback server in the tests.
"""
import hashlib
import io
import json
import struct
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_native_bundle as fnb  # noqa: E402

REPO = fnb.DESIGNATED_REPOSITORY
VERSIONS = {"yt-dlp": "2099.1.1", "cpython": "3.14.10", "ffmpeg": "8.1.3"}
MAIN_COMPONENT = {"yt-dlp": "yt-dlp", "cpython": "cpython", "ffmpeg": "ffmpeg"}
BUILD = {"yt-dlp": "a" * 40, "cpython": "b" * 40, "ffmpeg": "c" * 40}
TAG_COMMIT = {"yt-dlp": "d" * 40, "cpython": "e" * 40, "ffmpeg": "f" * 40}
CONTENT = {"yt-dlp": "0123456789ab", "cpython": "1123456789ab", "ffmpeg": "2123456789ab"}
ALL_ABIS = tuple(sorted(fnb.SUPPORTED_ABIS))


def fake_elf(machine: int, bits: int, pad: int = 64) -> bytes:
    hdr = bytearray(b"\x7fELF")
    hdr += bytes([1 if bits == 32 else 2, 1, 1]) + b"\x00" * 9
    hdr += struct.pack("<HH", 3, machine)  # e_type, e_machine at offset 18
    return bytes(hdr) + b"\x00" * pad


def tag_of(tool: str, version: str | None = None) -> str:
    return f"{tool}-v{version or VERSIONS[tool]}"


def components_of(tool: str, version: str) -> dict:
    base = version
    if tool == "yt-dlp":
        return {"yt-dlp": {"version": "2099.1.1", "license": "Unlicense", "commit": "1" * 40},
                "py-idna": {"version": "3.20", "license": "BSD-3-Clause", "commit": "2" * 40}}
    if tool == "cpython":
        return {"cpython": {"version": "3.14.10", "license": "PSF-2.0", "commit": "3" * 40},
                "openssl": {"version": "3.5.9", "license": "Apache-2.0", "commit": "4" * 40}}
    return {"ffmpeg": {"version": "8.1.3", "license": "LGPL-2.1-or-later", "commit": "5" * 40}}


def payload(tool: str, abi: str | None, machine=None, bits=None) -> dict:
    if abi:
        em, eb = fnb.SUPPORTED_ABIS[abi]
        machine = em if machine is None else machine
        bits = eb if bits is None else bits
    if tool == "yt-dlp":
        return {"assets/ytdlp-site-packages.zip": b"fake site packages zip",
                "metadata/python-packages.json": b"[]"}
    if tool == "cpython":
        return {f"jniLibs/{abi}/libviddown_python.so": fake_elf(machine, bits),
                f"jniLibs/{abi}/libpython3.14.so": fake_elf(machine, bits, 80),
                f"assets/python-lib-3.14-{abi}.zip": b"fake stdlib",
                "assets/python-version.txt": b"3.14.10\n"}
    return {f"jniLibs/{abi}/libffmpeg.so": fake_elf(machine, bits, 90),
            f"jniLibs/{abi}/libffprobe.so": fake_elf(machine, bits, 95),
            f"metadata/ffmpeg-configure-{abi}.txt": b"./configure --disable-everything"}


def make_zip(tool: str, abi: str | None = None, *, version=None, machine=None, bits=None, drop=None, extra=None,
             build_info=None, licenses=True) -> tuple[bytes, dict]:
    """(zip bytes, required_files). required_files covers jniLibs/ and assets/ like the real manifest."""
    version = version or VERSIONS[tool]
    files = payload(tool, abi, machine, bits)
    info = {"bundle": "viddown-native-bundle", "tool": tool, "version_tag": version, "release_tag": tag_of(tool, version),
            "content_id": CONTENT[tool], "abi": abi, "repo_commit": BUILD[tool],
            "android_ndk": "27.3" if abi else None, "android_api_level": 24 if abi else None,
            "components": components_of(tool, version)}
    if build_info:
        info.update(build_info)
    members = dict(files)
    members["BUILD_INFO.json"] = json.dumps(info).encode()
    members["THIRD_PARTY_NOTICES.md"] = b"# notices\n"
    members["LICENSE"] = b"GPL\n"
    if licenses:
        for name in info["components"]:
            members[f"licenses/{name}/LICENSE"] = f"licence of {name}\n".encode()
    if extra:
        members.update(extra)
    members["SHA256SUMS"] = "".join(f"{hashlib.sha256(d).hexdigest()}  {n}\n" for n, d in sorted(members.items())).encode()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for n, d in members.items():
            if n != drop:
                z.writestr(n, d)
    required = {n: {"sha256": hashlib.sha256(d).hexdigest(), "size": len(d)}
                for n, d in files.items() if n.startswith(("jniLibs/", "assets/"))}
    return buf.getvalue(), required


def make_release(tool: str, abis=("x86_64",), **kw) -> dict:
    """{abi or None: (zip bytes, required_files)} for one tool release."""
    if not fnb.PER_ABI[tool]:
        return {None: make_zip(tool, None, **kw)}
    return {abi: make_zip(tool, abi, **kw) for abi in abis}


def tool_entry(tool: str, blobs: dict, version=None) -> dict:
    version = version or VERSIONS[tool]
    tag = tag_of(tool, version)
    entries = {}
    for abi, (data, required) in blobs.items():
        name = fnb.artifact_name(tool, abi, version)
        e = {"artifact": name, "url": f"{REPO}/releases/download/{tag}/{name}", "sha256": hashlib.sha256(data).hexdigest(),
             "size": len(data), "required_files": required}
        if abi:
            em, eb = fnb.SUPPORTED_ABIS[abi]
            e["elf_machine"] = {"value": em, "bits": eb}
            e["executables"] = [n for n in required if n.endswith(("libviddown_python.so", "libffmpeg.so", "libffprobe.so"))]
        entries[abi] = e
    t = {"version": version, "release_tag": tag, "release_url": f"{REPO}/releases/tag/{tag}",
         "release_tag_commit": TAG_COMMIT[tool], "build_commit": BUILD[tool], "content_id": CONTENT[tool]}
    if fnb.PER_ABI[tool]:
        t["abis"] = entries
    else:
        t["artifact"] = entries[None]
    return t


def make_lock(releases: dict) -> dict:
    """releases = {tool: {abi or None: (bytes, required)}} -> a valid schema 2 manifest."""
    abis = sorted(next(a for t, r in releases.items() if fnb.PER_ABI[t] for a in [list(r)]) )
    lock = {
        "schema_version": 2, "repository": REPO,
        "tools": {tool: tool_entry(tool, blobs) for tool, blobs in releases.items()},
        "components": [
            {"name": n, "tool": tool, "version": c["version"], "license": c["license"], "upstream_commit": c["commit"],
             "notes": "", "license_verified": True, "license_basis": "test"}
            for tool in releases for n, c in components_of(tool, VERSIONS[tool]).items()],
        "abis": {abi: {"verification": {"runtime_on_device_or_emulator": "NOT VERIFIED - test"}} for abi in abis},
    }
    return lock


def default_releases(abis=("x86_64",)) -> dict:
    return {tool: make_release(tool, abis) for tool in fnb.ANDROID_TOOLS}
