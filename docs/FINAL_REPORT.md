# VidDown Android: final report (2026-11-16)

Honest state: Phases 1-10 are NOT complete. Phases 2-8 and the Phase 10 workflows/docs are written; none has been built by Gradle or run on a device; Phase 9 has not started. This report lists what that means. Nothing here is a CI result: no CI run, release, tag or artifact exists.

## Acceptance checklist (spec section 15)
| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | Project builds | FAIL | never built by Gradle (PROGRESS.txt 4a-4g) |
| 2 | App launches on emulator/device | FAIL | never run; first emulator run only exercised the PoC tests |
| 3 | Main navigation works | FAIL | UI tests written, not run |
| 4 | Browser navigation works | FAIL | WebView never run |
| 5 | Tabs can be managed | FAIL | TabManager logic passes on the JVM (63 browser tests); UI not run |
| 6 | Media detection on supported test resources | FAIL | pipeline passed against a local HTTP server on the JVM; not in a WebView |
| 7 | Download action opens a genuine format sheet | FAIL | presenters tested on the JVM; sheet UI not run |
| 8 | A direct test file downloads | FAIL | engine passed against a local server on the JVM; not on Android |
| 9 | Progress and completion states accurate | FAIL | JVM only |
| 10 | Completed files can be found and opened | FAIL | MediaStore/FileProvider never run |
| 11 | Engine selection persists; only the selected engine runs | FAIL | Phase 8 written, not compiled; no instrumented test |
| 12 | Unsupported sources give clear errors | FAIL | error mapping tested on the JVM; not on a device |
| 13 | GitHub Actions produce documented artifacts | FAIL | workflows documented (`docs/ci.md`, `docs/release.md`), actionlint clean, never run |
| 14 | Required licence notices included | FAIL | notices and inventory exist; Python-dependency licences unverified, nanojson licence unconfirmed (D67) |
| 15 | No major failure hidden behind a placeholder | FAIL | not reviewable until the app runs (Phase 9) |
| 16 | All native artifacts come from the designated repo | PASS | `scripts/fetch_native_bundle.py` accepts only `native/bundle.lock.json` URLs; real fetch run; script tests |
| 17 | Native bundle revision pinned | PASS | `native/bundle.lock.json` (tag + commits) |
| 18 | SHA-256 verification passes for every integrated artifact | PASS (scripts) | real run: "OK: 4 ABI(s) verified"; the Gradle build-time check never ran |
| 19 | Corrupted artifacts are rejected | PASS (scripts) | wrong SHA-256, same-size corruption, tampered cache tests (64 script tests re-run today: OK) |
| 20 | Missing artifacts cause clear build failures | PASS (scripts) | missing ABI/file tests; the Gradle side never ran |
| 21 | CPython and yt-dlp execute on each claimed ABI | FAIL | no ABI verified |
| 22 | FFmpeg executes and processes controlled media | FAIL | host FFmpeg only (fixtures); the bundle's FFmpeg never ran |
| 23 | No unauthorised binary fallback | PASS (design) | no fallback in the fetch script (D7); not exercised by Gradle |
| 24 | Build metadata identifies provenance accurately | FAIL | generation steps ran locally with fake files; the real workflow never ran |

## Tested features
Real runs (sandbox, no Android): 64 Python tests (today: OK); native bundle fetch and SHA-256/ELF verification for all four ABIs; JVM tests of the pure Kotlin modules for Phases 2-6 as recorded in PROGRESS.txt. **Not run:** Phase 7 and 8 tests, all Android code, all instrumented tests.
## Untested features
Everything on Android: app launch, navigation, WebView browser, tabs, detection in a real page, download service/notifications/MediaStore/SAF, library, settings persistence, the NewPipe engine on a real site, the yt-dlp engine and FFmpeg on any ABI, engine switching, release APKs, signing.
## Known limitations
See README and PROGRESS.txt sections 4c-4g, 10: no JavaScript runtime in the bundle, remux-only FFmpeg, no playlists/live, no DRM, plain http blocked, unverified licences listed above.
## Verified ABIs
None. All four are built by owner decision D21 and labelled NOT VERIFIED.
## Licence summary
App: GPL-3.0-or-later (because of NewPipeExtractor). Third-party: `THIRD_PARTY_NOTICES.md`, `docs/dependencies.md`. Open: nanojson licence confirmation, Python dependency licences, licences of jsoup/rhino/protobuf/jsr305 stated from memory.
## Engine matrix
Direct HTTP: written; NewPipe: written (complete video+audio and audio-only); yt-dlp: written (experimental, needs a runtime for the ABI). Exactly one active, no fallback. None run on Android.
## Native bundle revision
`native-v2026.10.28`, tag commit `c2590a62d4b17ad4a30c1a1691debea84d001950`, build commit `bbb3c426cc269648502999b3d5ec59955151cf34` (CPython 3.14.10, yt-dlp 2026.10.26, FFmpeg 8.1.3), repository https://github.com/saimumhabib/viddown-native-bundle.
## Artifact provenance / SHA-256 / links
Provenance design: `docs/release.md`. SHA-256 of the four bundle zips matched the release's SHA256SUMS.txt (consistency check only). CI run links: none (no run yet). Build artifact links: none (no build yet).
