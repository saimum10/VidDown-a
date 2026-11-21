# Engine selection (Phase 8)

Status: **written, not built, not run.** The JVM tests listed at the end were written in the same session, but this session had no
Kotlin compiler, no Gradle and no network, so none of them has been compiled or executed. Treat every statement below as "the code
is written to do this", not "this was seen working".

The rules come from section 3 of `docs/PROJECT_PROMPT.md`. This page says where each rule is enforced and what was changed in Phase 8.

## Rules and where they live

| Rule | Where it is enforced |
|---|---|
| Exactly one engine is chosen by the user | `AppSettings.selectedEngine`, persisted by DataStore; `EngineSelector` follows it |
| No automatic mode | There is no such `EngineId`. A saved value that is not a known engine is **not** turned into one (D78/D79) |
| No silent fallback | `EngineSelector.activeEngine()` returns a failure naming the engine (unavailable) or "no engine selected"; it never creates another engine |
| Only the selected engine is loaded | Only `EngineSelector` calls `EngineRegistry.create`. Creating an engine starts no runtime (yt-dlp prepares Python on first use; NewPipe initialises on first extraction) |
| Switching unloads the previous engine | `DownloadManager.start()` follows `EngineSelector.selection` and calls `releaseUnusedBy` on every change (D80) |
| A running download keeps its engine | The engine id is stored in the record at enqueue; `runTask` asks `engineFor(task.engineId)`; a release never closes an engine that a running download uses (D81) |
| New downloads use the new choice | `enqueue` uses `activeEngine()` |
| Engine information is displayed | Downloads, Home, Library details, the link dialog and the quality sheet show the engine; diagnostics show which engines are loaded |
| Engine-specific errors are clear | `DownloadError.engineUnavailable / noEngineSelected / engineChanged / engineUnavailableForDownload` (D82/D83) |

## What changed in Phase 8

* `EngineSelector` now takes `Flow<EngineId?>`. `null` means "no valid choice": `activeEngine()` fails with `noEngineSelected()`,
  no status is marked selected, nothing is created. `selection` is exposed for the one collector that needs it.
* The selection is read **inside** the selector's lock, for creating and for releasing, so an engine is never created for a choice
  that was already replaced and a release never closes the engine that has just been chosen.
* `releaseUnusedBy { inUse }` asks "which engines are busy?" while holding the lock. The older `releaseUnused(set)` still exists
  (it delegates). Reason: a set computed before the lock could miss a download that started in between, and the release would then
  close the engine that download was about to use.
* Closing happens under the lock and is non-cancellable; one engine failing to close does not keep the others open
  (`onCloseFailed` reports it).
* `DownloadManager`: closes the previous engine as soon as the selection changes (before, it stayed loaded until some download
  finished), keeps `runningEngine` in a `ConcurrentHashMap` (the selector reads it), publishes `engineCommitments` (engines of queued
  and running downloads) and takes an optional `listedWith` on `enqueue`.
* `listedWith`: the formats on screen belong to the engine that listed them. If the user switched engines in Settings between
  "list formats" and "download", the request is rejected with `engineChanged` instead of handing yt-dlp format ids to NewPipe.
  `LinkDownloader` and `MediaDownloader` pass it (`LinkState.Ready.engineId`, `QualityState.Ready.engineId`).
* A download whose own engine is unusable now fails with a message that says the download keeps its engine and that the fix is to
  select another engine and start the download again (changing the selection cannot help that record). Its partial data is kept.
* Settings: `AppSettings.unrecognizedEngineKey`. A present-but-unknown saved key is reported, left in DataStore untouched (an unrelated
  settings change no longer rewrites it with the default) and cleared only when the user chooses an engine. While it is set the
  container passes `null` to the selector, so no engine is active; Settings shows "No download engine is selected".
* Settings notice after switching: "X will be used for new downloads. Downloads that are already queued or running keep their
  engine (Y)." (`EnginePresenter.switchNotice`, only when it matters).
* Browser: the engine description follows `selector.selection` and asks for the **active** engine, so a stale id can never bring a
  previous engine back. With no engine selected, detections say so (`EngineUnavailable("No engine", ...)`), never name an engine.
* Diagnostics: each engine line ends with "(loaded)" or "(not loaded)"; the selected-engine line says "none" when the saved value is
  not recognised.

## Decisions

* **D78** The selector's choice is `EngineId?`. `null` = nothing selected. A fresh install (no saved key) still defaults to Direct HTTP (D28);
  only a saved value that exists but is unknown counts as "nothing selected".
* **D79** The unknown saved key is kept, shown shortened (40 characters) and never overwritten by other settings writes.
* **D80** Engine release is driven by selection changes **and** by download end. Queued downloads do not keep an engine loaded:
  it is closed after a switch and created again when the queued download starts (it still uses its own engine).
* **D81** Selection is read, engines are created and engines are closed under one lock; "busy engines" is evaluated late (inside it).
* **D82** `listedWith` guard: options are never sent to another engine. The user has to open the download again.
* **D83** A record keeps its engine for life. When that engine is unavailable the error names it and tells the user to start a new download.
* **D84** Opening the browser creates the selected engine object (needed for its capabilities) but never another one.

## Known gaps (not fixed, stated plainly)

* No lease on engines: if the user switches engines while a quality sheet is still loading formats, the engine that was loading them
  can be closed under it. The result is an error on the sheet; nothing is queued with the wrong engine (D82).
* `EngineSelector.statuses` is derived from the saved choice only. An engine that becomes unavailable at run time (for example the
  yt-dlp runtime fails its check, `YtDlpSupport.reportRuntimeProblem`) is shown as unavailable the next time the list is built, not
  at that moment.
* Settings cannot "choose nothing"; the no-engine state exists only for an unknown saved value.
* No instrumented test proves that selecting Direct HTTP never starts Python (the JVM tests prove the engine object is never
  created; `YtDlpSupport.availability()` only compares ABIs and starts nothing). The Compose banner for "no engine selected" and the
  switch notice have no UI test.
* Nothing here has been compiled. Expected first-build trouble: the new trailing-lambda constructor parameter of `EngineSelector`
  (tests use it), `Flow<EngineId?>` inference in `AppContainer`, and any test that constructs `EngineInfo`, `LinkState.Ready` or
  `QualityState.Ready` positionally.

## Tests written (not run)

* `engine-api` `EngineSelectorTest`: no selection creates nothing and says so; selection flow; release after a switch; release never
  closes the selected or a busy engine; release with no selection; a failing close does not block the others; a switch back creates a
  fresh engine.
* `download` `DownloadManagerTest`: switching closes the previous engine without creating the new one; a running download keeps its
  engine open and it closes when the download ends; options are never sent to another engine; same-engine enqueue accepted; no engine
  selected queues nothing and creates nothing; a download whose own engine is gone names that engine; commitments list queued and
  running engines.
* `data` `SettingsPreferencesTest`: unknown key reported, known/missing not flagged, not overwritten by unrelated writes, cleared by a
  choice, long keys shortened.
* `app` `PresentersTest` (no-selection warning, switch notice), `BrowserPresentersTest` (no engine selected in the browser).

Run with: `./gradlew :engine-api:test :download:test :data:testDebugUnitTest :app:testDebugUnitTest` (in CI: `ci.yml`).
