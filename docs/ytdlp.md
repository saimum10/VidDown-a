# yt-dlp engine (Phase 7)

Status: WRITTEN on 2026-11-16. Nothing here has been built by Gradle or run on a device or emulator; the authoring session had no Kotlin
compiler either. The JVM tests exist (`engine-ytdlp/src/test`) but were NOT run. See PROGRESS.txt section 4f.

## 1. Shape

```
:engine-ytdlp  (pure Kotlin/JVM)             :app (Android)
  YtDlpEngine  ── DownloadEngine               AndroidNativeTools : NativeTools   (RuntimeInstaller + ProcessBuilder)
  ToolRunner   ── child processes, kill        YtDlpSupport  (availability, "experimental" text from BuildConfig)
  FfmpegProcessor (merge / remux / ffprobe)    AppEngineRegistry (creates the engine only when it is selected)
  InfoMapper, YtDlpArgs, YtDlpErrors, ProgressParser, MediaProbe + OutputValidator, MergePlanner, Json
```
The engine never touches Android classes. The only door to the native bundle is the `NativeTools` interface
(`prepare()` = verify SHA-256 of every packaged file and extract; `start(tool, args)` = one child process). Tests use scripted fakes.

yt-dlp runs as a separate process (`libviddown_python.so -m yt_dlp ...`), exactly the runtime model of docs/native-bundle.md section 5:
cancel = kill the process, a crash cannot take the app down, and Python is never loaded unless this engine is used
(creating `YtDlpEngine` and reading `availability()` start nothing; `prepare()` runs on the first inspect/download).

## 2. What it does

* **inspect / listFormats**: `yt-dlp --dump-single-json --flat-playlist --no-playlist -- <url>`; the JSON is read by a small strict parser
  (`Json`) and mapped by `InfoMapper`. The last answer is kept for 60 s so inspect + listFormats cost one run.
* **Formats offered** (only ones yt-dlp reported; unknown values stay null; sizes are yt-dlp's own, `filesize_approx` is flagged as an estimate):
  complete files, **merged pairs** (video-only + audio-only, id `"V+A"`, `requiresMerge = true`, `container` = planned container),
  video-only, audio-only. Left out and counted (so the error can say why): DRM formats, storyboards, formats whose protocol needs
  FFmpeg's network (`m3u8`, rtmp, rtsp, mms...; this FFmpeg has no network), format ids that are not plain tokens.
  Duplicates are reduced to the best bitrate per (height, codec family, container) for video and (codec family, container, language) for audio.
* **download**: every stream is downloaded by yt-dlp into `destinationDir` as `vd-stage-N.<ext>` (`-P dir -o vd-stage-N.%(ext)s`, `.part` files
  resume). After a stage finishes, `vd-stage-N.done` records the file, so a retry does not download it again.
  * One stream: ffprobe; MPEG-TS content (HLS) is remuxed to mp4 (mkv if mp4 refuses; m4a/mka for audio-only); ffprobe again; validation.
  * Two streams: FFmpeg stream copy (`-c copy`, mp4 gets `+faststart`) into the planned container, fallback Matroska once; ffprobe; validation; parts deleted.
  * Validation (`OutputValidator`): the streams the format promised exist, size > 0, duration within max(5 s, 10 %) of the announced one when both are known.
  * `Completed` is emitted only after all of that; the file is renamed to `<title>.<ext>` (real extension) inside the temporary folder.
* **Progress**: `--progress-template` prints `VDPROG|downloaded|total|estimate|speed|eta`; at most 4 events per second. The total is only a real number:
  the size yt-dlp listed (exact) or the exact size the server announced for a single stream; otherwise null (indeterminate). Merged downloads add the stages up and report no ETA.
* **Cancel**: cancelling the collecting coroutine kills the running process (`ToolRunner` `finally`), also on timeout and when the process prints nothing for 10 minutes.
  `close()` kills everything still running. FFmpeg/ffprobe runs are serialised by one process-wide semaphore.
* **Errors** (`YtDlpErrors`): yt-dlp's stderr is mapped to the single `DownloadError` model: login / membership / private, age, region, bot check and DRM are reported as
  restrictions ("VidDown does not sign in or bypass access restrictions"); a missing JavaScript runtime says exactly that and suggests another engine (known gap 1 of the bundle);
  network, TLS (never weakened), storage, HTTP status, unsupported URL, vanished format, broken runtime (`No module named ...`) have their own kinds; anything else stays visible (redacted) as an extraction failure.

## 3. Decisions made in Phase 7

| id | decision | why / what to revisit |
|---|---|---|
| D68 | yt-dlp never signs in itself. Only `Referer`, `User-Agent`, `Origin`, `Accept-Language` go on the command line; credentials (`Authorization`) never are. The `Cookie` of a request (the in-app browser's cookies for that site) is written to a private temporary Netscape cookie file for ONE run (`--cookies`), for the site's own domain only, and deleted afterwards (`BrowserCookies`, `YtDlpEngine.withCookieFile`). | Media that needs the browser's signed-in session (private or friends-only videos) can be read without VidDown having a login of its own. The cookies are never on a command line, never logged, never stored in the database. NewPipe (D63) stays anonymous. Known limit: YouTube through yt-dlp needs a JavaScript runtime that this bundle lacks (gap 1), so Auto mode still sends YouTube links to NewPipe. |
| D69 | VidDown does the merge itself (two yt-dlp runs + its own FFmpeg run), not yt-dlp's post-processors (`--fixup never`, no `--ffmpeg-location`). | The bundle's FFmpeg is named `libffmpeg.so`, which yt-dlp may not recognise; own runs give real `Processing` stages, fallback containers and validation. |
| D70 | Only `https://` is accepted (debug builds also `http://127.0.0.1`/`localhost` for tests). | Python ignores Android's network security policy, so the app applies the same rule itself (as D55 for the other engines). Plain-http segments inside a manifest are still up to the site. |
| D71 | Merged choice: one audio partner per video (same container family first, then yt-dlp's language preference, then best bitrate); container: avc/hevc+aac/mp3 -> mp4, vp8/vp9/av1+opus/vorbis -> webm, anything else -> mkv. | Stream copy only (this FFmpeg has no video encoder). |
| D72 | A transport stream whose remux fails is delivered as `.ts` under its true name instead of failing the download. | The file is valid media; hiding it behind an error helps nobody. A failed MERGE does fail (`FFMPEG_FAILURE`). |
| D73 | Without an exact total the progress is indeterminate (null total). | "Never fake progress"; `total_bytes_estimate` of fragmented streams is not shown as a total. |
| D74 | yt-dlp is `Available` whenever the APK packages a runtime for the device's ABI, **also when that ABI is not verified**; the engine list then says "Experimental: the runtime for <abi> has not passed on-device tests yet." `BuildConfig.VERIFIED_NATIVE_ABIS` comes from `native/bundle.lock.json`. | Follows D21 (owner: build ARM ABIs without waiting). A runtime that fails when first used is reported as "could not start its native components". Flip `YtDlpSupport.availability()` to require verification if the owner prefers the strict reading of the spec. |
| D75 | `capabilities.mediaTypes` = PAGE, DIRECT_MP4/WEBM/OTHER, HLS, DASH (the generic extractor handles direct files and manifests). | Browser detections of those kinds become downloadable with this engine. |
| D76 | Live and upcoming streams are refused with a plain message. A playlist link is not refused: `inspect` reports it with `DownloadError.isPlaylist`, and `inspectPlaylist` lists its videos (`--dump-single-json --flat-playlist --playlist-end 100`, no `--no-playlist`). Each chosen video is then read and downloaded alone, with `--no-playlist`. | A live stream has no end. A playlist is only a list of single videos, so the single-file flow is unchanged. |
| D77 | A native runtime that fails its SHA-256 check, cannot be extracted or cannot start makes the engine "not available" with the reason (`YtDlpSupport.reportRuntimeProblem`), until a later preparation succeeds or the app restarts. | Spec: an engine whose runtime cannot run on a device must be shown as unavailable. The Settings list reads availability each time it is shown. |

## 4. Not done / open

* Everything above is unbuilt and unrun. Expect compile errors on first build (a few hand-checked risk spots: smart casts in `YtDlpEngine`, `channelFlow(...).buffer(UNLIMITED)` fusion, `Char.code` use in `ToolRunner`).
* `yt-dlp` self-update is not used; the pinned version (`BuildConfig.YTDLP_VERSION`) is what runs.
* The exact stdout of `--print after_move:filepath` and the `--progress-template` fields were written from knowledge of yt-dlp's documentation, not checked against yt-dlp 2026.10.26 on a device. The engine falls back to the newest `vd-stage-N.*` file when the printed path is missing.
* Instrumented tests (`YtDlpEngineInstrumentedTest`, 7 tests, written, NOT run): local WAV download, HLS with a separate audio rendition (merge), HLS with muxed audio (TS remux), cancel kills the python process
  (checked through /proc), a runtime that fails its check makes the engine unavailable and recovers, unreachable address, plain http to another host refused. Fixtures: `app/src/androidTest/assets/media/` (108 KB, made with a host
  FFmpeg 6.1: 64x64 H.264 + AAC, 3 s). The merge and remux COMMANDS and the ffprobe JSON the engine reads were run against these fixtures with the host FFmpeg and worked; the bundle's own FFmpeg and yt-dlp's real format list were not.
* Not covered by any test yet: DASH, a tampered APK asset (the failure path is simulated by an injected installer), and "an unselected engine never starts Python" (Phase 8).
* Audio extraction/conversion is not offered: this FFmpeg can only stream-copy or encode AAC/FLAC/WAV.
