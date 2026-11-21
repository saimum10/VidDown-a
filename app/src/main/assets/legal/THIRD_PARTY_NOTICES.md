# Third-party notices

VidDown is licensed under the GNU General Public License v3.0 or later (see `LICENSE`).
The exact component list, versions, sources and checksums are in `docs/dependencies.md` (generated). This file states the licence terms that apply when the app is distributed.

STATUS: initial version (Phase 2). The full licence texts of every shipped component still have to be collected into a `licenses/` directory and into the in-app licences screen (Phase 3 and Phase 10). Items marked UNVERIFIED must be resolved before any public release.

## Bundled in the app

| Component | Licence | Notice duty |
|---|---|---|
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 | ship the licence text and any NOTICE file |
| OkHttp and Okio (Direct HTTP engine) | Apache-2.0 | same |
| AndroidX (Activity, Lifecycle, Navigation, DataStore, Room), Jetpack Compose (Foundation, Material 3, UI) | Apache-2.0 | same |
| CPython 3.14.10 | PSF-2.0 | ship the licence text (in the bundle: `licenses/`) |
| OpenSSL 3.5.9 | Apache-2.0 | ship licence and NOTICE; compatible with GPLv3 |
| libffi 3.4.4 | MIT | ship the copyright and permission notice |
| FFmpeg / FFprobe 8.1.3 (LGPL-2.1-or-later, built without `--enable-gpl`) | LGPL-2.1-or-later | ship the licence, the exact configure line (in the bundle: `metadata/`), and a way to obtain the corresponding source of this version; it runs as a separate executable and is not linked into the app |
| yt-dlp 2026.10.26 | Unlicense | none required |
| Python packages shipped with yt-dlp: certifi, idna, urllib3, requests, charset-normalizer, mutagen, websockets, yt-dlp-ejs | **UNVERIFIED** (the bundle ships no licence text for them; believed MPL-2.0, BSD-3-Clause, MIT, Apache-2.0, GPL-2.0-or-later) | obtain the real licence texts before release; certifi (MPL-2.0) and mutagen (GPL) have specific obligations |

The native components come from https://github.com/saimumhabib/viddown-native-bundle (see `docs/native-bundle.md` for the exact release, commits and checksums). Their own licence files travel inside the bundle zips under `licenses/` and `THIRD_PARTY_NOTICES.md`.

## Built from source, not yet released

* NewPipeExtractor (GPL-3.0-or-later) is built from source (fetched at build time, composite build) and linked into the NewPipe engine. It is not pinned: every build uses the newest stable release at that moment. The exact release tag and commit inside an APK are shown in About and recorded in the release's BUILD_INFO.app.json and BUILD_INFO.newpipe.json. Source: https://github.com/TeamNewPipe/NewPipeExtractor at that tag. nanojson (TeamNewPipe fork, the commit the extractor names in its own gradle/libs.versions.toml) is built from source for it: Apache-2.0 per the licence header of its source files (no LICENSE file in the repository: confirm with the maintainers before release). The extractor's own libraries (jsoup, jsr305, protobuf-javalite, rhino) come from Maven Central; their licences are listed in docs/dependencies.md and still need confirmation from the real artifacts.

## Build and test tools (not distributed)

Android Gradle Plugin, Kotlin Gradle and Compose compiler plugins, KSP, Room Gradle plugin (Apache-2.0); JUnit 4 (EPL-1.0); AndroidX Test (Apache-2.0).

## Original assets

* The app icon and logo (`docs/branding/original-icon.png`) were supplied by the project owner.
* The in-app vector icons (`core/src/main/res/drawable/vd_ic_*.xml`) were drawn for this project and are covered by the project licence.

## Source code availability

The complete source of VidDown is this repository. The About screen (Phase 3) and every release page must link to it. For the LGPL FFmpeg binary the corresponding source is the upstream FFmpeg 8.1.3 release; the bundle repository records the exact commit and configuration used to build it.
