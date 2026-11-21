<p align="center"><img src="docs/branding/logo.png" height="128" alt="VidDown logo"></p>

# VidDown

An Android video browser and downloader with an integrated browser, media detection, quality selection, a download manager and a library.
All code and design are original. No analytics, no tracking, minimal permissions.

> **Status: early development.** Phases 2-8 (foundation, screens, Direct HTTP downloads, browser and media detection, NewPipe engine, yt-dlp engine, engine-selection hardening) and the workflows/docs of Phase 10 are *written*. Nothing has been built by Gradle or run on a device yet, Phase 9 (testing and polish) has not started, and no release exists. This README only states what is true today; see `docs/FINAL_REPORT.md` for the acceptance checklist (all open).

## Features (written, see the table below for what has really been run)
Integrated browser with tabs, bookmarks and history; evidence-based media detection; quality bottom sheet with only the formats the selected engine returns; download manager (queue, resume, Wi-Fi only, notifications, MediaStore/folder save); library; three engines (Direct HTTP, NewPipe, yt-dlp), exactly one active; light/dark theme.

## Screenshots
None yet: the app has never run on a device or emulator.

## What exists today
| Area | State |
|---|---|
| Project structure (`app`, `browser`, `core`, `data`, `engine-api`, `engine-direct`, `engine-newpipe`, `engine-ytdlp`, `download`), version catalog, CI, release workflow | written; first CI run pending |
| Engine API (`DownloadEngine`, typed models, error model, state machine, redaction) and `EngineSelector` (Auto or one fixed engine, no fallback) | written and unit-tested on the JVM (39 tests) |
| Settings model, validation, quality selection | written and unit-tested on the JVM |
| Design system (Material 3 theme, light/dark, original icon set, empty/loading/error states) | written; palette contrast verified; not yet seen on a device |
| Screens: Home (address field, recent pages and downloads), Downloads, Library (search, sort, filter), Settings (theme, engine list, browser options, clear history/cookies/cache), About and licences | written; presentation logic unit-tested; Compose UI not yet run on a device |
| Room database (downloads) and DataStore settings | written; JVM mapping tests pass; device tests pending |
| Native runtime proof of concept (CPython, yt-dlp, FFmpeg) | written; first emulator run reported one failure that was then fixed; no ABI verified yet |
| Direct HTTP engine (resume, retry, redirects, refuses web pages and playlists) and download manager (queue, limits, Wi-Fi only, network loss, recovery, cancel/retry), foreground service, notifications, save location (MediaStore, chosen folder), open/share/delete | written; the JVM logic was run here (engine against a local HTTP server, manager with fakes: 231 tests in total); Android parts only type-checked against stubs, never run on a device |
| In-app browser (Phase 5): WebView with tabs (open/close/switch, restored after a restart), address bar and search, back/forward/reload/stop, home, progress, bookmarks, history, share-menu links, secure-by-default WebView settings (https only, certificate errors never bypassed, no popups, no camera/mic/location, no JavaScript bridge) | written; the pure logic (URL policy, tabs, snapshot codec) is unit-tested on the JVM; the WebView screens were never built or run |
| Media detection (Phase 5): per tab and page; evidence-based (server Content-Type, HLS/DASH manifest text, controlled probe, page `<video>` elements); de-duplicated; DRM and unsupported kinds are explained, never offered as downloads; quality bottom sheet with only what the engine returned | written; the logic was run here against a real local HTTP server serving MP4, WebM, HLS and DASH (63 tests in `:browser`, 10 in `:app` presenters); the Android side is untested |
| NewPipe engine | **written** (Phase 6; JVM logic run, never built by Gradle) |
| yt-dlp engine | **written** (Phase 7; never built or run, JVM tests exist but were not run); see `docs/ytdlp.md` |
| Engine-selection hardening (Phase 8) | **written**, never compiled or run; see `docs/engine-selection.md` |
| GitHub Actions + release documentation (Phase 10) | **written**: `ci`, `poc-runtime-tests`, `native-health`, `release` (actionlint clean, never run on GitHub); `docs/release.md` |

## Engines (Settings: Auto, NewPipe or yt-dlp; no silent fallback)
| Engine | State in this build |
|---|---|
| Auto (default) | per link: YouTube links (youtube.com and its subdomains, youtu.be, youtube-nocookie.com) use NewPipe, every other link uses yt-dlp; decided by a host check on the link text only (no network); applies to the browser and to "Download from link"; NewPipe and yt-dlp can still be chosen alone and then behave exactly as before |
| Direct HTTP | integrated (Phase 4): plain video/audio files over http(s); no pages, no HLS/DASH playlists; not yet run on a device |
| NewPipe | integrated in code (Phase 6): complete video+audio and audio-only files; separate video/audio streams need FFmpeg and are not offered; see `docs/newpipe.md` |
| yt-dlp | integrated in code (Phase 7): runs as a child process, offers the formats yt-dlp reports, merges video+audio with FFmpeg (stream copy), checks the result with ffprobe; only available when the APK packages a runtime for the device's ABI ("Not available in this build" otherwise); experimental on ABIs without on-device proof; see `docs/ytdlp.md` |

## ABIs
| ABI | Built in releases | Runtime verified |
|---|---|---|
| arm64-v8a | yes (owner decision) | **no** |
| armeabi-v7a | yes (owner decision) | **no** |
| x86_64 | yes | **no** (emulator test pending) |
| x86 | yes | **no** (emulator test pending) |

## Architecture
Kotlin, Jetpack Compose, Material 3, Navigation Compose, Room, DataStore, coroutines/Flow, MVVM. Modules: `app` (screens, navigation, wiring, WebView and Android download pieces), `browser` (URL policy, media detection, tabs; pure Kotlin), `core` (design system, settings model), `data` (Room, DataStore), `engine-api` (engine contract, pure Kotlin), `engine-direct` (Direct HTTP engine, pure Kotlin + OkHttp), `download` (download manager, pure Kotlin). Further engine, browser and feature modules arrive in later phases. See `docs/architecture.md`.

## Native components
CPython, yt-dlp and FFmpeg come only from https://github.com/saimumhabib/viddown-native-bundle, which releases every tool on its own (`yt-dlp-v<version>`, `cpython-v<version>`, `ffmpeg-v<version>`); `native/bundle.lock.json` pins one release per tool and `scripts/fetch_native_bundle.py` checks each zip with SHA-256 and merges them into one tree per ABI. (The bundle's NewPipe helper is for the desktop app and is not used here; NewPipe Extractor is still imported by `scripts/fetch_newpipe.py`.) Update one tool with `python3 scripts/update_native_bundle.py propose --tool yt-dlp`. Details and provenance: `docs/native-bundle.md`.

## Building
There is no local setup to follow: builds run in GitHub Actions (`.github/workflows`, explained in `docs/ci.md`).
NewPipeExtractor is not stored in this repository: every build fetches the latest stable release (and the nanojson commit it needs) with `scripts/fetch_newpipe.py` (needs git and Python 3.11+; Gradle runs it for you) and builds it from source. `NEWPIPE_REF=v0.26.5` builds a specific release, `NEWPIPE_UPDATE=never` skips the network check; see `docs/newpipe.md`.
* `ci` runs on every push: script tests, bundle verification, build, unit tests, lint.
* `poc-runtime-tests` runs the native runtime tests and the UI navigation tests on emulators.
* `native-health` (Mondays + manual) checks that the pinned native bundle is still reachable and verifies a cache-free download.
* `release` (tag `v*` or manual) runs unit tests and lint, then builds one debug and one release APK per ABI and creates a **draft** release with checksums, build metadata and test reports. Release APKs are **unsigned** unless the four signing secrets are configured. Step by step: `docs/release.md`.

## Artifact provenance
Every release carries `SHA256SUMS.txt`, `BUILD_INFO.app.json` (app commit, workflow run, signed or not) and `BUILD_INFO.native.json` (tag, commits and SHA-256 of every tool release and zip). The native zips' SHA-256 are compared with the bundle release's own `SHA256SUMS.txt`: this checks integrity and consistency, it is not independent authentication. No GitHub build attestation is produced (`docs/release.md`).

## Tested features
Real runs so far (author's sandbox, JVM only): 64 Python script tests (bundle fetch/verify, tampered and missing artifacts, icons, inventory, release notes, report collector); Kotlin JVM tests for the pure modules as listed in `PROGRESS.txt` (Phases 2-6; Phases 7-8 tests were written but not run). A real download of the pinned native bundle with SHA-256 verification for all four ABIs. Nothing on Android.

## Licence
GPL-3.0-or-later (`LICENSE`). Third-party components: `THIRD_PARTY_NOTICES.md`, `docs/dependencies.md`.

## Known limitations (today)
* Nothing has been built by Gradle or run on a device by the author of this code: results come from CI runs of the owner.
* Direct HTTP only handles plain https files (plain http:// links are blocked by Android and reported as such). A page, a streaming playlist or a site needs another engine (not available yet). Downloads interrupted by the system killing the app continue the next time the app is opened.
* The bundled yt-dlp has no JavaScript runtime, so some sites will not work; the bundled FFmpeg only remuxes (no MP3/Opus encoding, no network).
* DRM-protected, blob and MSE media cannot be downloaded and will never be bypassed.
