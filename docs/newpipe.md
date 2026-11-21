# NewPipe engine: integration decision and Phase 6 status

Status: INTEGRATED IN CODE (Phase 6), NEVER BUILT BY GRADLE (section "What Phase 6 built" below). Owner instruction: "JitPack, or whatever is best": JitPack was rejected.
Facts below were checked on 2026-10-31 (when v0.26.5 was pinned; see the UPDATE above).

## UPDATE (2026-11-18): NewPipeExtractor is no longer pinned; every build uses the latest release
Owner request: nothing about NewPipe is hardcoded in the source tree; the APK is built with the newest NewPipeExtractor available at build time.
The submodule decision below (points 1 and 6 of "Decision", and `third_party/newpipe.lock.json`) is SUPERSEDED. What replaced it:

* `third_party/NewPipeExtractor`, `third_party/nanojson`, `.gitmodules` and `newpipe.lock.json` are gone from the repository (the two folders are git-ignored).
* `scripts/fetch_newpipe.py ensure` (run by `settings.gradle.kts` on every Gradle start, and as an explicit step in every workflow) asks `git ls-remote` for the newest stable tag
  (`vX.Y.Z`, compared numerically; rc/beta/other names ignored), shallow-clones it into `third_party/NewPipeExtractor`, reads the nanojson commit from the extractor's own
  `gradle/libs.versions.toml` (`teamnewpipe-nanojson`) and shallow-clones exactly that commit into `third_party/nanojson`. The download goes to a temporary folder first, so a
  failed run never leaves a half-replaced checkout. Only `git` and Python 3.11+ are needed.
* The result is recorded in `third_party/newpipe.resolved.json` (tag, commit, nanojson commit, time). It is copied into the APK (About screen, `BuildConfig.NEWPIPE_EXTRACTOR_TAG`/`_COMMIT`)
  and into the release (`BUILD_INFO.newpipe.json`, `newpipe` key of `BUILD_INFO.app.json`), which is also what the GPL source offer needs: the exact version inside each APK.
* Everything else is unchanged: both sources are still compiled as composite builds with explicit substitution, so JitPack is still never contacted. `third_party/nanojson-build` stays.
* Knobs: `-PnewpipeRef=v0.26.5` or env `NEWPIPE_REF` builds a specific tag/branch/commit (reproducible rebuild, or a quick way back when a new release breaks the adapter);
  `-PnewpipeUpdate=never` or env `NEWPIPE_UPDATE=never` skips the network check when sources are already on disk (offline work). With the network down and a copy on disk, the build
  warns and uses that copy; with no copy it stops with a clear message. `NEWPIPE_EXTRACTOR_URL` / `NEWPIPE_NANOJSON_URL` point the fetch at a mirror or a test repository.
* Tests: `scripts/tests/test_fetch_newpipe.py` (offline, real git against local fixture repositories).

Trade-off the owner accepted by asking for this: a build is no longer reproducible by commit alone, and a new upstream release can break the adapter. Only the package
`com.viddown.engine.newpipe.adapter` imports extractor classes, and the compile error (or a failing `:engine-newpipe:test`) shows up in CI before an APK exists; pin with
`NEWPIPE_REF` to release meanwhile. Checked by hand on 2026-11-18: between v0.26.5 and v0.27.1 none of the extractor classes the adapter imports changed their public API
(only additions: `Response.ensure*/throwIf*`, `StreamInfo.hasLiveChat`, `HttpResponseException`). v0.27.x moved the extractor's JDK toolchain from 11 to 17 (CI installs 11, 17 and 21).
Never built by Gradle: this environment had no Gradle, so the wiring (included build from a freshly fetched folder, Gradle 9.7.1 building an extractor that declares 9.8.2) is
untested until the first CI run.

## What was found
* `TeamNewPipe/NewPipeExtractor` tag `v0.26.5` (commit `f9e6bb808f82bf3e4dc1f2a29a16fd376931c8ef`), GPL-3.0-or-later, Java, Gradle 9.6.1 wrapper, JDK 11 toolchain.
* Its README suggests JitPack, and its own `settings.gradle.kts` adds `maven("https://jitpack.io")` **because it depends on `com.github.TeamNewPipe:nanojson:<commit>`**, a fork that exists only on JitPack.
  So even building the extractor from source pulls one artifact from JitPack unless nanojson is also built from source.
* The other dependencies come from Maven Central: jsoup 1.22.2, jsr305 3.0.2, protobuf-javalite 4.35.1 (+ protoc through the protobuf Gradle plugin 0.10.0), rhino and rhino-engine 1.8.1 (1.9.0 needs minSdk 26; we have minSdk 24).
* `TeamNewPipe/nanojson` @ `e9d656dd...` is a Maven project (no Gradle) with sources in `src/main/java/com/grack/nanojson/`. **It has no LICENSE file, and no licence in its pom.xml or README.** The upstream `grack/nanojson` could not be read from the sandbox.

## Decision
1. Add two git submodules (Phase 6, not before): `third_party/NewPipeExtractor` at `f9e6bb8...` and `third_party/nanojson` at `e9d656d...`.
2. Wrap nanojson in a 10-line Gradle build of our own (`third_party/nanojson-build/`: `java-library`, group `com.github.TeamNewPipe`, name `nanojson`, `sourceSets.main.java.srcDir("../nanojson/src/main/java")`).
3. `settings.gradle.kts`: `includeBuild("third_party/nanojson-build")` and `includeBuild("third_party/NewPipeExtractor")`. Gradle substitutes the extractor's `com.github.TeamNewPipe:nanojson` coordinate with our included build automatically (same group and name), so JitPack is never contacted.
4. `engine-newpipe` depends on `net.newpipe:extractor` (the included build) and only the adapter package imports its classes. The OkHttp-based `Downloader` is ours.
5. CI installs JDK 11 next to JDK 17 (`actions/setup-java` with two versions) because the extractor's toolchain is JDK 11 and no toolchain auto-download plugin is configured.
6. The submodules are pinned by commit; updating them is a deliberate PR with a changelog review, like the native bundle.

Why not the alternatives: JitPack for both artifacts (third-party build service, unpinned binaries, forbidden by the project rules); copying NewPipeExtractor into our tree (loses the update path); using the full NewPipe app (not a library).

## Risks to check first in Phase 6 (could not be tried: no Gradle or Maven access in the authoring sandbox)
* Does Gradle 9.7.1 build the extractor (it declares 9.6.1)? Does its protobuf plugin work as an included build?
* Does automatic substitution of nanojson work, or is an explicit `dependencySubstitution` needed?
* **nanojson licence is unknown.** Before any release: find the licence of `grack/nanojson` (the fork's origin), ask the NewPipe maintainers if needed, or replace it. Do not ship until resolved. GPL-3.0-or-later for VidDown stays compatible with the extractor (GPL-3.0).
* The extractor GPL notice and source offer must appear in the in-app licences screen and THIRD_PARTY_NOTICES.
* Android API level: the extractor targets Java 11 bytecode and needs core library desugaring below API 33 (its README); we have minSdk 24, so enable `coreLibraryDesugaring`.

## What Phase 6 built
Module `:engine-newpipe` (pure Kotlin/JVM). Only the package `com.viddown.engine.newpipe.adapter` imports `org.schabi.newpipe.*`; the app module creates the engine through `NewPipeEngines.create()` and never sees an extractor class.

| File | Role |
|---|---|
| `adapter/ExtractorGateway` | the only user of the extractor's stream API: `StreamInfo.getInfo` -> `ExtractedMedia`; every exception becomes a `GatewayException` holding a VidDown `DownloadError`; `NewPipe.init` happens lazily on the first extraction |
| `adapter/OkHttpDownloader` | the extractor's HTTP stack on OkHttp (HTTP 429 -> `ReCaptchaException`, as NewPipe's own downloader does); no cookies, no logging; calls are tied to a `CancelToken` through a ThreadLocal so a cancelled coroutine aborts the blocked call |
| `adapter/ExtractionErrors` | exception -> `DownloadError`: age/private/paid/region/sign-in/captcha become `accessRestricted` ("VidDown does not sign in or bypass access restrictions"), network problems stay network problems, a `ParsingException` says the site may have changed |
| `FormatMapper` | extracted streams -> `FormatOption`s; stable ids (`av:18`, `a:140`, `~2` for duplicates) so a recovered download finds the stream the user chose |
| `StreamTransfer` | the download loop: bounded 10 MiB Range chunks, resume with If-Range, retry with backoff, final rename only when every byte arrived and the size matches |
| `NewPipeEngine` | `DownloadEngine`: `inspect` (extract, map, explain why nothing is offered), `download` (re-extract because addresses expire, find the format by id, transfer) |

Decisions made in Phase 6 (all listed in PROGRESS.txt, D58 onwards):
* **Single files**: video+audio progressive streams and audio-only streams are offered as they are (on YouTube the only video+audio file is 360p).
* **Higher qualities (merged)**: when the app has FFmpeg (the packaged native bundle exists for the device's ABI), each sharp video-only stream is paired with the best audio stream of its container family (`FormatMapper.partnerFor`) and offered as ONE choice with id `mrg:<videoId>+<audioId>` and `requiresMerge = true`. The engine downloads both streams itself (`StreamTransfer`, folders `video/` and `audio/`, resumable, a `.done` note per finished stream), then merges them with FFmpeg by stream copy (`StreamMerger`, implemented by `FfmpegStreamMerger`; the app gives it `AndroidFfmpegTools`, which verifies and starts `libffmpeg.so` only: no Python, no yt-dlp). Container: mp4 for H.264/H.265 + AAC/MP3, webm for VP8/VP9/AV1 + Opus/Vorbis, Matroska for everything else and as the retry when the first container fails. Without FFmpeg, video-only streams are only counted and the error says why. Written, not yet run on a device.
* **Plain files**: an address no extractor service recognises is probed (`DirectFileProbe`: HEAD, then a one-byte ranged GET) and, when it is a video/audio file, offered as "the file as served" (`original`) and saved with the browser's headers by `StreamTransfer`. This is what a file detected in the browser or a link to a file needs, now that Direct HTTP is no longer an engine. Web pages and HLS/DASH playlists are refused (`UNSUPPORTED_RESOURCE` / `UNSUPPORTED_FORMAT`).
* HLS/DASH delivery, live streams and DRM/login/paid/age/region content are reported, never bypassed. No sign-in, no cookies from the browser are sent to the extractor or the stream host.
* The engine class depends on `:engine-direct` for its HTTP primitives only (`HttpTransport`, `RetryPolicy`, `SpeedMeter`, `ResumeMeta`, `ContentTypePolicy`); the Direct HTTP engine class was removed together with its engine id.
* `settings.gradle.kts` uses explicit `dependencySubstitution` for both included builds (the extractor's project group is not `net.newpipe`, so automatic substitution would not match).
* The app enables core library desugaring (`desugar_jdk_libs_nio` 2.1.5, the artifact the extractor's README names) for the extractor's JDK 11 APIs on API 24-25.

nanojson licence: 14 of its 15 main source files carry the Apache-2.0 header; the repository has no LICENSE file (see the lock file). That is verified at file-header level only and should still be confirmed with the maintainers before a release. Apache-2.0 is compatible with GPL-3.0-or-later.

## Still open after Phase 6
* **Nothing here has been built by Gradle.** The Phase 6 JVM logic was compiled and run with kotlinc + JUnit (see PROGRESS.txt section 4e); the composite-build wiring (Gradle 9.7.1 building the extractor, protobuf plugin as an included build, explicit substitutions, JDK 11 toolchain lookup in CI) is the first thing the first CI run will test.
* Nothing was tried against a real site (the authoring sandbox only reaches GitHub). Whether YouTube stream URLs download with this User-Agent and plain bounded Range requests is unknown.
* Licences of the extractor's own dependencies (jsoup, rhino, protobuf, jsr305) are still from memory.
* Release builds: `isMinifyEnabled` is false today. When R8 is switched on, Rhino and the extractor need keep rules (see NewPipe app's proguard rules).
* The browser does not yet offer "download this page" for site-extractor engines (media detection never produces a PAGE candidate); NewPipe works through "Download from a link" on Home. `NewPipeEngine.supportsPage(url)` exists for that follow-up.

## Playlists
A playlist link (`/playlist?list=...`, or a video link that carries `list=...`) is listed with `inspectPlaylist` (at most 100 videos, following the site's pages). YouTube video links with `list=` are asked for under `https://www.youtube.com/playlist?list=<id>`, because the extractor reads them as one video. YouTube "Mix" lists (`list=RD...`) are not playlists for the extractor and are read as the one video. Every chosen video is then read and downloaded like any single video.
