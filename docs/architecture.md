# VidDown architecture (Phase 1 proposal)

Status: PROPOSAL. Nothing below has been compiled; the authoring sandbox had no Android SDK, Gradle or emulator
(see PROGRESS.txt). Items marked VERIFIED were checked with real commands; everything else is a design decision or an open question.

## 1. Repository decision

No project repository existed. The repo at the root of this archive was created for the app (`git init`, branch `main`).
Package name proposed: `com.viddown.app` (matches the owner's earlier decision for the native-bundle project; confirm in Phase 2).
License proposed: GPL-3.0-or-later (reasons in section 6). The native bundle is a separate repository and is only consumed.

## 2. Module layout (as specified; keep it lean)

**Status (Phase 5):** `browser` is a pure Kotlin/JVM module: `UrlPolicy` (only http/https pages are loaded), `MediaClassifier` + `ManifestInspector` + `MediaDetectionManager` (evidence-based, per tab and page, de-duplicated, bounded), `HttpUrlMediaProber` (one Range request or a bounded manifest read, tested against a real local HTTP server), `TabManager` + `TabsSnapshotCodec`, `PagePlayer` (a fixed read-only script and its strict parser) and `ExternalUrl`. The WebView never leaves `app/browser` and the browser screen: `BrowserHub` (process-wide: tabs, detections, saved page state as plain Bundles, the tab list as a small file) holds no WebView; `TabWebViewClient`/`TabChromeClient` report into it; the one live WebView belongs to the composition that shows the active tab and is saved and destroyed with it (`BrowserWebViews.dispose`). Download flow: detection row -> `BrowserViewModel` -> `MediaDownloader` (inspect with the selected engine, show exactly the formats it returned, queue the choice through `DownloadManager`); the UI never calls an engine.

**Earlier status (Phase 4):** `engine-direct` (Direct HTTP engine behind an `HttpTransport`; OkHttp in production, HttpURLConnection in the JVM tests) and `download` (the `DownloadManager` plus the contracts it needs: `DownloadStore`, `OutputStore`, `NetworkState`) exist as pure Kotlin/JVM modules, so queue, resume, retry, cancel, recovery and network-loss rules are tested without Android. The Android parts live in `app/download` (MediaStore/SAF `AndroidOutputStore`, `DownloadService`, notifications, `ConnectivityMonitor`, `FileActions`). Data flow: UI -> `DownloadManager` (the only writer of download state) -> `DownloadStore` (Room) / `EngineSelector` (engine per download) / `OutputStore` (final file). Foreground service and notifications only observe the manager.

**Earlier status (Phase 3):** `app`, `core`, `data` and `engine-api` exist; the screens live in `app` (`ui/screens`) on top of pure presenters (`presentation`), `EngineSelector` lives in `engine-api`. `core` holds the Material 3 design system and the settings model, `data` holds Room and DataStore, `engine-api` is pure Kotlin. `engine-direct` (Phase 4), `engine-newpipe` (6), `engine-ytdlp` (7) and the `feature-*` modules (3-5) are created together with their code. The native runtime classes (RuntimeInstaller, ProcessRunner, NativeRuntime) stay in `app` (they need Android's Context and BuildConfig); `engine-ytdlp` (Phase 7, pure JVM) reaches them only through its `NativeTools` interface, implemented by `app/engines/AndroidNativeTools`.


```
app                  Application, navigation host, DI wiring, foreground service declaration
core                 design system (Material 3), common models (MediaInfo, FormatOption, DownloadTask,
                     EngineCapabilities, DownloadError), error model, redaction utilities
data                 Room, DataStore, repositories, download/queue persistence
engine-api           DownloadEngine interface + EngineSelector contract (no Android UI, no engine classes)
engine-direct        OkHttp downloader (range resume, retries)
engine-newpipe       NewPipeExtractor adapter (OkHttp Downloader), mapping to VidDown models
engine-ytdlp         yt-dlp + FFmpeg engine (child processes behind NativeTools), see docs/ytdlp.md
engine-ytdlp         process-based yt-dlp runtime + FFmpeg helper (see section 3)
feature-home | feature-downloads | feature-library | feature-settings
browser              (Phase 5, implemented) URL policy, media detection, tabs: pure Kotlin, no WebView
```
Rules: UI code never imports NewPipe or yt-dlp classes. Engines are created lazily by an `EngineSelector`. The user's choice is an `EngineMode`: a fixed engine (the other engine's
classes/runtimes are never initialised) or **Auto**, where `EngineSelector.engineForUrl(url)` asks `EngineRouting` (a host check on the link text, no network):
YouTube links -> NewPipe, every other link -> yt-dlp. In Auto an engine is created only when a link needs it. No silent fallback: if the engine a link needs is
unavailable, that link fails and names it. A running download keeps the engine recorded on its `DownloadTask`.

## 3. yt-dlp engine design (matches how the bundle was built)

* Process model, not JNI: `ProcessBuilder` runs `<nativeLibraryDir>/libviddown_python.so -m yt_dlp ...` with `PYTHONHOME`, `PYTHONPATH`, `LD_LIBRARY_PATH`, `SSL_CERT_FILE` (exact variables in docs/native-bundle.md section 5).
* Why: cancellation = kill the process tree; a crash cannot kill the app; Python stays unloaded for the other engines; no terminal emulator needed.
* Progress: pass `--progress-template` / `--newline` and parse structured lines from stdout; collect stderr separately (old linkers print harmless warnings there). Formats via `-J --no-playlist`.
* FFmpeg: `libffmpeg.so` / `libffprobe.so` run as separate processes, only when the chosen format needs merging/remuxing; limit concurrency; validate output with ffprobe (duration + streams) before marking Completed. Capabilities are remux-only (docs/native-bundle.md section 6).
* First start: extract the stdlib zip to `<filesDir>/python/lib/python3.14/` and the yt-dlp zip to `<filesDir>/site/`, guarded by a version marker; do this off the main thread and report it as a "Preparing" state.
* ABI gating: yt-dlp is "Available" only if the device ABI is in the verified list AND the runtime self-test (`import ssl,zlib; yt_dlp --version`) passes at first use. Otherwise "Not available in this build/on this device".

## 4. NewPipe engine (Phase 6: written, never built by Gradle)

Decision (details, risks and the Phase 6 file list: `docs/newpipe.md`): the latest stable NewPipeExtractor release and the nanojson fork it needs are
fetched at build time by `scripts/fetch_newpipe.py` (not pinned, not stored in the repository) and built from source as Gradle composite builds, so no binary repository (and not JitPack) is used for them. Module `:engine-newpipe`: only its `adapter`
package imports extractor classes; `NewPipeEngines.create()` is the single entry for the app. Offers only complete files (progressive video+audio, audio-only); separate
video-only streams need FFmpeg and are not listed until the yt-dlp/FFmpeg phase.

## 5. Dependency and compatibility table

| item | version | origin | verified |
|---|---|---|---|
| CPython | 3.14.10 | native bundle | static only |
| yt-dlp | 2026.10.26 | native bundle | static only |
| FFmpeg/FFprobe | 8.1.3 (LGPL, remux-only) | native bundle | static only |
| OpenSSL / libffi | 3.5.9 / 3.4.4 | native bundle | static only |
| NewPipeExtractor | latest stable release at build time (v0.27.1 on 2026-11-18) | fetched by scripts/fetch_newpipe.py, built from source | adapter API usage compared with v0.27.1 by hand; never built by Gradle |
| Android NDK used by the bundle | 27.3.13750724, API 24 | bundle BUILD_INFO | yes |
| minSdk | >= 24 (forced by bundle API level) | decision | n/a |
| AGP / Gradle / Kotlin / Compose BOM / Room / OkHttp / WorkManager | choose in Phase 2 | Google Maven / Maven Central | NOT CHECKED (sandbox could not reach them) |

Pin everything in `gradle/libs.versions.toml`; look up current stable versions online in Phase 2 rather than trusting any number in this file.

## 6. Licensing risk assessment

* Chosen: **GPL-3.0-or-later**. Compatible with GPL-3.0 NewPipeExtractor (linked, so the combined work must be GPLv3-compatible) and with the owner's GPL-3.0-or-later bundle repo.
* FFmpeg built LGPL-2.1+, executed as a separate process: ship license files, the configure line, and a pointer to the exact source (8.1.3). Do not enable GPL components later without re-assessing.
* OpenSSL 3.x Apache-2.0: compatible with GPLv3 (not with GPLv2-only); notice required.
* CPython PSF-2.0, libffi MIT, yt-dlp Unlicense: permissive.
* Python dependencies: licenses UNVERIFIED (docs/native-bundle.md section 3); mutagen is believed GPL-2.0-or-later (fine under GPLv3), certifi MPL-2.0 (file-level copyleft, must keep its notice), requests/urllib3 etc. permissive. Must be confirmed from real license texts before a release.
* Distribution: sources for GPL parts must be offered (this repo public or a source link in the About screen).
* Legal exposure of the product category (downloading from streaming sites) is a matter for the owner; the app must not bypass DRM/access controls and must not claim universal support.

## 7. Major technical risks

1. **Nothing has run on Android yet.** Whether the launcher executes from `nativeLibraryDir` inside an app sandbox, whether `dlopen` of extension modules from `filesDir` works under the target API, and whether SELinux allows the child processes is unproven. First job of the PoC.
2. arm64-v8a (the most important real-world ABI) cannot be run on the x86_64 CI emulator; claiming it needs a physical device or arm test infrastructure. Without that it must not be advertised.
3. The bundle's pin can vanish (it already did once). Cache verified zips in CI.
4. No JavaScript runtime in the bundle: some sites (YouTube class) will fail with yt-dlp until one is added to the bundle.
5. FFmpeg is remux-only: no MP3/Opus encoding, no network. UI must not offer what the build cannot do.
6. APK size: 4 ABIs x ~15 MB zips plus test modules in the stdlib zip; consider ABI splits.
7. Pure-Python dependency licenses unverified.
8. `PYTHONHOME` layout mistakes abort the interpreter with a misleading "encodings" error (already hit once by the bundle's own smoke test).
9. Gradle/AGP/Compose version compatibility cannot be checked offline.
10. WebView MSE/blob/DRM media cannot be downloaded; detection must say so honestly.

## 8. Phase 1 PoC specification (IMPLEMENTED in `app/` and `.github/workflows/poc.yml`, NOT yet run: see PROGRESS.txt)

A single-module app `poc/` plus an instrumented test run by GitHub Actions on an x86_64 emulator (API 24 and a recent API):
copy `build/native/<abi>/jniLibs` into `poc/src/main/jniLibs`, set `extractNativeLibs=true` + `useLegacyPackaging=true`, bundle the two zips as assets.
Tests (each must really execute): extract runtime; `libviddown_python.so -c "import sys,ssl,zlib,hashlib"` reports 3.14 and OpenSSL 3.5.9; `-m yt_dlp --version` == 2026.10.26;
`-m yt_dlp -J` against a local HTTP server that serves an MP4 and an HLS playlist generated in the test (not a public site) returns real formats; `libffmpeg.so -version` shows 8.1.3 and no `--enable-gpl`;
FFmpeg remux/merge of an owned test file plus `libffprobe.so` validation; cancellation (destroy the process, assert no child left); `System.loadLibrary`/ELF check for the shared libs;
`Build.SUPPORTED_ABIS` vs manifest; SHA-256 verification and corrupted/missing artifact rejection (already covered on the JVM-less side by `scripts/tests`, add a Kotlin equivalent only if the app verifies at runtime).
Reuse the commands in the bundle repo's `scripts/smoke/android_smoke.sh` as the reference for expected outputs.
