# Native bundle integration

Status: the integration was moved to the bundle repository's per-tool releases on 2026-11-20 (sections 1, 2, 4, 8, 9 rewritten). Sections 3 and 5-7 still describe the contents verified on 2026-10-29. **The committed manifest is UNPINNED: `fetch` pins the newest per-tool releases itself at build time** (section 2). Nothing here has been run on Android yet (see "Verification status").

## 1. Source of truth

All CPython, yt-dlp, FFmpeg and Python-runtime artifacts come from ONE place:
`https://github.com/saimumhabib/viddown-native-bundle` (the owner's own repository), as GitHub **release assets**.
There is no fallback to upstream projects, PyPI, mirrors or any other host. The only recovery source is a
previously verified cached copy of the exact same pinned file (`scripts/fetch_native_bundle.py` re-verifies it every time).

The bundle repo contains **build scripts**, not binaries. Its GitHub Actions cross-compile CPython, OpenSSL, libffi and FFmpeg
from upstream source with the Android NDK. **Every tool is released on its own**, under a tag that is the tool's own version
(`scripts/ci/tools.py` in the bundle repo). The old single `native-v*` release with one zip per ABI no longer exists as a concept.

| release tag | zip | contains |
|---|---|---|
| `yt-dlp-v<version>` | `viddown-native-yt-dlp-<version>.zip` | yt-dlp + its pure-Python dependencies (`assets/ytdlp-site-packages.zip`); **no ABI**, one zip for all |
| `cpython-v<version>` | `viddown-native-cpython-<abi>-<version>.zip` | CPython + OpenSSL + libffi + stdlib (`jniLibs/<abi>/*.so`, `assets/python-lib-*.zip`) |
| `ffmpeg-v<version>` | `viddown-native-ffmpeg-<abi>-<version>.zip` | FFmpeg + FFprobe (`jniLibs/<abi>/libffmpeg.so`, `libffprobe.so`, `metadata/`) |
| `newpipe-v<version>` | `viddown-native-newpipe-<platform>-<version>.zip` | NewPipe helper for the **desktop** app. **Not used by the Android app and rejected by the manifest loader.** |
| `index` | `components.json` | rolling release that lists the newest tag per tool. Used only by `update_native_bundle.py discover/propose` to find tags; never pinned. |

A tag may carry a suffix (`cpython-v3.14.10.2`) when the same tool version was released again from different inputs; the suffix is
part of the zip name. The app imports **NewPipe Extractor** exactly as before (`scripts/fetch_newpipe.py`, composite build); nothing about it changed.

The app repo must never repeat the compilation; it only consumes the zips. Updating one tool (for example yt-dlp, which moves often)
no longer changes the pins of CPython and FFmpeg.

## 2. Current pin (`native/bundle.lock.json`, schema 2)

```
schema_version  2
tools
  yt-dlp   { version, release_tag, release_tag_commit, build_commit, content_id, artifact{ artifact, url, size, sha256, required_files } }
  cpython  { ..., built_with, abis{ <abi>{ artifact, url, size, sha256, elf_machine, required_files, executables } } }
  ffmpeg   { ..., built_with, abis{ <abi>{ ... } } }
components     every component with its tool, version, licence and whether the licence text ships in the zip
abis           per ABI: static check results and the RUNTIME verification of the three tools together
```

**Status: UNPINNED, and that needs no manual step.** `native/bundle.lock.json` carries `"status": "UNPINNED"`. When `python3 scripts/fetch_native_bundle.py fetch`
sees that, it takes the newest release of `yt-dlp`, `cpython` and `ffmpeg` from the bundle repository (via the rolling `index`, else the git tags),
runs the same checks as `propose` (below), writes the resulting pins over `native/bundle.lock.json` in the workspace, and continues with the normal verified
download. CI and local builds therefore always get the newest tools, each verified. To FREEZE the versions, commit the resolved `native/bundle.lock.json`
(then `fetch` uses exactly those pins and never looks for newer ones); to go back to "always newest", restore the UNPINNED file.
The versions a build really used are in `BUILD_INFO.native.json` and the release notes. If one tool has no release yet, the fetch stops with a message naming it
(run the bundle repo's `build-native` workflow first).

Manual pinning (optional, for review):

```sh
python3 scripts/update_native_bundle.py discover          # newest tag per tool, compared with the pins
python3 scripts/update_native_bundle.py propose           # same checks, writes a proposal + diff instead of using it right away
cp native/bundle.lock.proposed.json native/bundle.lock.json     # after reading the diff
python3 scripts/gen_dependency_inventory.py && python3 scripts/sync_legal_assets.py
```

Later updates:

```sh
python3 scripts/update_native_bundle.py propose --tool yt-dlp              # only yt-dlp moves; cpython and ffmpeg keep their pins
python3 scripts/update_native_bundle.py propose --tag yt-dlp-v2026.10.27   # an exact tag (repeatable, one per tool)
```

What `propose` checks before it writes anything: every zip equals the release's own `SHA256SUMS.txt`; the zip's inner `SHA256SUMS` match; every
library has the ELF machine of its ABI; `BUILD_INFO.json` names the same tool, release tag, ABI and content id for all ABIs of a tool; the
component version inside the zip belongs to the tag; the tag commit is read with `git ls-remote`. An ABI that is missing in one per-ABI tool is left
out of the manifest (noted in `abis_note`); `arm64-v8a` and `x86_64` must exist in both. A recorded runtime `PASS` is carried over only while every zip
of that ABI is unchanged: any new zip resets it to `NOT VERIFIED`.

Integrity model (be precise when documenting provenance): each zip SHA-256 is calculated locally AND equals the value in the
release's `SHA256SUMS.txt`. Both come from the same repository, so this is a consistency check, **not** an independently
authenticated upstream checksum. The checksums inside each zip (`SHA256SUMS`) are also recomputed and must match.

### History: the previous single-zip pin
The app used to pin one release `native-v2026.10.28` (tag commit `c2590a62d4b17ad4a30c1a1691debea84d001950`, build commit `bbb3c426cc269648502999b3d5ec59955151cf34`,
NDK 27.3.13750724 / API 24, so `minSdk >= 24`) with four per-ABI zips. Its components (CPython 3.14.10, OpenSSL 3.5.9, libffi 3.4.4, FFmpeg 8.1.3, yt-dlp 2026.10.26) are what the
`components` list of the UNPINNED manifest still shows. An even earlier pin (`native-v0.1.0`) disappeared when the owner's repository history was rewritten: a pinned release can vanish,
which is why CI caches the verified zips.

## 3. Component versions (identical in all four ABIs)

| component | version | license (source) |
|---|---|---|
| CPython | 3.14.10 | PSF-2.0 (BUILD_INFO + licenses/) |
| OpenSSL | 3.5.9 | Apache-2.0 (BUILD_INFO + licenses/) |
| libffi | 3.4.4 | MIT (BUILD_INFO + licenses/) |
| FFmpeg / FFprobe | 8.1.3 | LGPL-2.1-or-later, built without `--enable-gpl` (`CONFIG_GPL/NONFREE/VERSION3 = 0` in `metadata/ffmpeg-config-<abi>.h`) |
| yt-dlp | 2026.10.26 (git head `c91f8de0...`) | Unlicense, UNVERIFIED (see below) |
| certifi 2026.10.23, idna 3.20, urllib3 2.8.0, requests 2.34.2, charset-normalizer 3.5.2, mutagen 1.48.1, websockets 17.3, yt-dlp-ejs 0.8.0 | as listed | recollection only, UNVERIFIED |

The bundle ships **no license text and no license metadata** for the pure-Python packages (their `*.dist-info/METADATA` holds only name and version).
`license_verified: false` in the manifest marks them. Before any release: obtain the real license texts (Apache-2.0 and MPL-2.0 carry notice duties; mutagen is GPL) and
ask the bundle repo to ship them.

## 4. Zip layout (the three Android zips merged, per ABI)

```
# cpython zip
jniLibs/<abi>/libviddown_python.so   launcher: PIE executable that calls Py_BytesMain (ELF executable with a lib*.so name)
jniLibs/<abi>/libpython3.14.so       interpreter (NEEDED: libm, libdl, liblog, libc)
jniLibs/<abi>/libssl_python.so, libcrypto_python.so
assets/python-lib-3.14-<abi>.zip     stdlib + extension modules (*.cpython-314-<abi>-linux-android.so), ~684 files
assets/python-version.txt            "3.14.10"
# ffmpeg zip
jniLibs/<abi>/libffmpeg.so, libffprobe.so   PIE EXECUTABLES (not JNI libraries); NEEDED: libm, libz, libc only
metadata/ffmpeg-configure-<abi>.txt, ffmpeg-config-<abi>.h
# yt-dlp zip (no ABI; extracted into every ABI tree)
assets/ytdlp-site-packages.zip       pure Python: yt_dlp, requests, urllib3, certifi (cacert.pem), idna, mutagen, websockets, yt_dlp_ejs, ...
metadata/python-packages.json
# every zip
licenses/<component>/, BUILD_INFO.json, THIRD_PARTY_NOTICES.md, LICENSE, SHA256SUMS
```
`fetch_native_bundle.py` merges `jniLibs/ assets/ metadata/ licenses/` of the three zips into `build/native/<abi>/` (two tools providing the same path is an error) and keeps each zip's own `BUILD_INFO.json`, `THIRD_PARTY_NOTICES.md`, `LICENSE` and `SHA256SUMS` under `build/native/<abi>/provenance/<tool>/`. Gradle and the app therefore see the same tree as before.
Static checks done: every `.so` is the right ELF machine for its ABI (EM_AARCH64 / EM_ARM / EM_386 / EM_X86_64). The FFmpeg configure line passes `-Wl,-z,max-page-size=16384` (16 KB page support); the other libraries' alignment was NOT checked.

## 5. Runtime model the bundle was designed for (from `native/launcher/viddown_python.c` and `scripts/smoke/android_smoke.sh` in the bundle repo)

yt-dlp runs as a **separate process**, started with `ProcessBuilder`, so that cancel = kill the process tree, a crash cannot take the app down, and Python is not loaded unless the yt-dlp engine is selected:

```
env: PYTHONHOME=<filesDir>/python   PYTHONPATH=<filesDir>/site   LD_LIBRARY_PATH=<nativeLibraryDir>
     HOME=<filesDir>/home  TMPDIR=<cacheDir>/ytdlp  PYTHONDONTWRITEBYTECODE=1  SSL_CERT_FILE=<filesDir>/site/certifi/cacert.pem
cmd: <nativeLibraryDir>/libviddown_python.so -m yt_dlp ...        (FFmpeg: <nativeLibraryDir>/libffmpeg.so ...)
```
Required app setup (UNVERIFIED on a device):
* The executables must be installed in `nativeLibraryDir`: `android:extractNativeLibs="true"` and `packaging { jniLibs { useLegacyPackaging = true } }`. Android 10+ forbids executing files from writable app storage.
* `assets/python-lib-3.14-<abi>.zip` must be extracted to `<filesDir>/python/lib/python3.14/` (the zip holds the CONTENTS of `python3.14/`; extracting anywhere else makes Python abort with "Failed to import encodings"). Extension modules are `dlopen`ed from there.
* `assets/ytdlp-site-packages.zip` must be extracted to `<filesDir>/site/`.
* Check extraction with a version marker and re-extract when the bundle version changes.

## 6. FFmpeg capabilities (read `metadata/ffmpeg-configure-<abi>.txt`; do not assume more)

Remux-only minimal build: `--disable-everything --disable-network`, protocols `file,pipe` only; demuxers mov, matroska, mp3, aac, ogg, flac, wav, mpegts;
muxers mp4, ipod, matroska, webm, mp3, adts, ogg, opus, flac, wav, mov; encoders **aac, flac, pcm_s16le only** (no MP3, no Opus, no H.264 encoder); decoders aac, mp3float, vorbis, opus, flac, pcm_s16le;
no `hls`/`dash` demuxer and no network (yt-dlp downloads the segments itself; FFmpeg is only used to merge/remux local files).
Consequences for the app: "extract audio as MP3" is NOT possible; audio extraction is limited to stream copy or AAC/FLAC/WAV; HLS/DASH must be downloaded by yt-dlp, not FFmpeg.

## 7. Known gaps in the bundle (report these to the bundle repo; do not hide them in the app)

1. No JavaScript runtime; `yt-dlp-ejs` is present but useless without one, so some YouTube-style extraction will fail. Engine error mapping must say so.
2. `brotli` and `pycryptodomex` are absent (need native compilation).
3. Missing stdlib modules: `_bz2`, `_lzma`, `_sqlite3`, `_curses`, ...
4. The stdlib zip still contains CPython test modules (`_testcapi`, `_ctypes_test`, `_xxtestfuzz`, ...): wasted APK size.
5. No license texts for the Python dependencies (section 3).
6. `armeabi-v7a`/`x86` are not covered by CPython's own CI (experimental). `arm64-v8a` cannot be run on the x86_64 CI emulator.
7. The bundle repo's own README says no ABI is verified yet.

## 8. Verification status

| check | result |
|---|---|
| SHA-256 of the 4 old single-zip artifacts == release SHA256SUMS.txt | PASS (2026-10-29, real download; that release is no longer the pin) |
| The new per-tool fetch / update code against fake per-tool releases served from a loopback server (see section 9) | PASS (2026-11-20, sandbox, no real network) |
| The new per-tool fetch / update code against the REAL bundle releases (`yt-dlp-v*`, `cpython-v*`, `ffmpeg-v*`) | **NOT RUN**: the sandbox could not reach the repository. Needs `propose` (section 2). |
| Gradle wiring (`app/build.gradle.kts`, merged `required_files`, `NATIVE_BUNDLE_TAG`) and `RuntimeInstaller` stamp | **NOT BUILT**: no Android SDK in the authoring sandbox |
| CPython starts, yt-dlp reports version, ffmpeg runs, cancel, native libs load, on Android | **NOT VERIFIED for any ABI** |

An ABI may be listed as supported only after the Phase 1 PoC instrumented test passes for it in CI.

## 9. Scripts

* `python3 scripts/fetch_native_bundle.py fetch [--abi X ...] [--cache-dir D] [--out build/native]` downloads the three pinned tool releases, verifies each zip, merges them into `build/native/<abi>/` and writes `build/native/BUILD_INFO.native.json` (provenance per tool and zip; copy into the app's build metadata). The cache is per tool (`.cache/native-bundle/<tool>/<abi>/`), so a new yt-dlp downloads only the yt-dlp zip. The yt-dlp zip is downloaded once and shared by all ABIs.
* `python3 scripts/fetch_native_bundle.py check` HEAD-checks every pinned URL and size (source health, no download).
* `python3 scripts/update_native_bundle.py discover | propose [--tool T] [--tag TAG]` proposes a manifest update and prints a diff (section 2). It never edits `native/bundle.lock.json`; accept by `cp native/bundle.lock.proposed.json native/bundle.lock.json` after review, then re-run the tests.
* `python3 scripts/verified_abis.py`, `scripts/release_notes.py`, `scripts/gen_dependency_inventory.py` read the same manifest (release notes and the inventory list every tool's tag, commits and every zip's SHA-256).
* Tests: `python3 -m unittest discover -s scripts/tests -v` (no real network). The fetch tests cover: connection refused, 404 (not retried), 500 (retried), timeout, truncated body, wrong SHA-256, same-size corruption, missing/unsupported ABI, invalid zip, missing required file, wrong ELF architecture, foreign-ABI library, native libraries inside the ABI-independent yt-dlp zip, BUILD_INFO tool/tag/commit/content-id mismatch, zip-slip, two tools providing the same file, tampered cache (with and without server), "updating one tool downloads only that tool", moving refs (including the rolling `index`), a tag of another tool, foreign repositories, an old schema-1 or UNPINNED manifest, the desktop NewPipe helper in the manifest, and loopback-only test URLs. The updater tests cover tag parsing, the index, per-tool pinning, partial updates, ABI intersection, license handling and the reset of a runtime PASS.
* `--test-base-url` exists for tests only and is rejected for non-loopback hosts.

## 10. Licensing and redistribution notes (see also docs/architecture.md)

FFmpeg is configured LGPL and runs as a separate executable; ship its license files, the exact configure line (`metadata/`), and the source offer/pointer for the exact version (8.1.3).
OpenSSL 3.x is Apache-2.0 (needs notice; compatible with GPLv3). Ship `THIRD_PARTY_NOTICES.md` from the zip plus the Python dependency licenses once obtained.
