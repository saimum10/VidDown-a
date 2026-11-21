package com.viddown.app.presentation

import com.viddown.core.model.ActiveSummary
import com.viddown.core.model.AppSettings
import com.viddown.core.model.DownloadItemUi
import com.viddown.core.model.DownloadUi
import com.viddown.core.model.Formatters
import com.viddown.core.model.LibraryFilter
import com.viddown.core.model.LibraryQuery
import com.viddown.core.model.LibrarySort
import com.viddown.core.model.SearchEngine
import com.viddown.core.model.UrlInput
import com.viddown.core.model.UrlInputResult
import com.viddown.data.repo.PageVisit
import com.viddown.download.BlockReason
import com.viddown.download.LiveProgress
import com.viddown.download.WorkState
import com.viddown.engine.api.DownloadTask
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.selector.EngineSelector
import com.viddown.engine.selector.ModeStatus
import com.viddown.engine.selector.SelectOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What a list screen shows. [Loading] is the value before the first data arrives. */
sealed interface ListState<out T> {
    data object Loading : ListState<Nothing>
    data object Empty : ListState<Nothing>
    data class Content<T>(val items: List<T>) : ListState<T>
    data class Error(val message: String, val detail: String?) : ListState<Nothing>
}

private fun <T> Flow<ListState<T>>.orError(what: String): Flow<ListState<T>> =
    catch { e -> emit(ListState.Error("Could not load $what.", e.message)) }

// ---------------------------------------------------------------------------------------------------- Home

data class HomeUiState(
    val recentPages: List<PageVisit>,
    val active: ActiveSummary,
    val searchEngine: SearchEngine,
)

class HomePresenter(
    downloads: Flow<List<DownloadTask>>,
    history: Flow<List<PageVisit>>,
    settings: Flow<AppSettings>,
) {
    val state: Flow<HomeUiState> = combine(downloads, history, settings) { d, h, s ->
        HomeUiState(
            recentPages = h.take(2),
            active = DownloadUi.summary(d),
            searchEngine = s.searchEngine,
        )
    }

    companion object {
        /** What the address field does with what was typed: open an address, search, or explain why not. */
        fun interpret(text: String, searchEngine: SearchEngine): HomeAction = when (val r = UrlInput.parse(text, searchEngine)) {
            is UrlInputResult.Url -> HomeAction.OpenInBrowser(r.url)
            is UrlInputResult.Search -> HomeAction.OpenInBrowser(r.url)
            UrlInputResult.Empty -> HomeAction.ShowError("Enter a web address or some words to search for.")
            is UrlInputResult.UnsupportedScheme -> HomeAction.ShowError("Only http and https addresses can be opened (not ${r.scheme}:).")
            is UrlInputResult.Invalid -> HomeAction.ShowError(r.reason)
        }
    }
}

sealed interface HomeAction {
    data class OpenInBrowser(val url: String) : HomeAction
    data class ShowError(val message: String) : HomeAction
}

// ---------------------------------------------------------------------------------------------------- Downloads

/**
 * The Downloads list: the saved records plus what is only known while the app runs (speed, time left, what a queued
 * download is waiting for). Without the live flows it shows just the records.
 */
class DownloadsPresenter(
    downloads: Flow<List<DownloadTask>>,
    live: Flow<Map<String, LiveProgress>> = flowOf(emptyMap()),
    work: Flow<WorkState> = flowOf(WorkState(running = 0, queued = 0, blockedBy = null)),
) {
    val state: Flow<ListState<DownloadItemUi>> = combine(downloads, live, work) { tasks, liveById, w ->
        val result: ListState<DownloadItemUi> = if (tasks.isEmpty()) {
            ListState.Empty
        } else {
            val waiting = when (w.blockedBy) {
                BlockReason.NO_NETWORK -> "Waiting for network"
                BlockReason.WIFI_REQUIRED -> "Waiting for Wi-Fi"
                null -> null
            }
            ListState.Content(
                tasks.map { t ->
                    val p = liveById[t.id]
                    DownloadUi.withLive(DownloadUi.item(t), p?.speedBytesPerSecond, p?.etaSeconds, p?.stage, waiting)
                },
            )
        }
        result
    }.orError("downloads")
}

// ---------------------------------------------------------------------------------------------------- Library

data class LibraryQueryState(
    val search: String = "",
    val sort: LibrarySort = LibrarySort.NEWEST,
    val filter: LibraryFilter = LibraryFilter.ALL,
)

data class LibraryItemUi(
    val id: String,
    val title: String,
    /** Resolution, duration, size and engine: only the parts that are really known. */
    val details: String,
    val createdAtMillis: Long,
    /** Where the saved file is (a content URI or a path), or null when the record has none. */
    val outputPath: String? = null,
)

data class LibraryUiState(
    val list: ListState<LibraryItemUi>,
    val query: LibraryQueryState,
    /** True when something was searched or filtered, so an empty result means "no match" and not "nothing yet". */
    val isFiltered: Boolean,
)

class LibraryPresenter(downloads: Flow<List<DownloadTask>>, query: Flow<LibraryQueryState>) {
    val state: Flow<LibraryUiState> = combine(downloads, query) { all, q ->
        val items = LibraryQuery.apply(all, q.search, q.sort, q.filter).map { t ->
            LibraryItemUi(
                id = t.id,
                title = DownloadUi.title(t),
                details = listOfNotNull(
                    t.format?.resolutionLabel,
                    Formatters.duration(t.durationSeconds),
                    Formatters.bytes(t.bytesDownloaded.takeIf { it > 0 }),
                    t.engineId.label,
                ).joinToString(" · "),
                createdAtMillis = t.createdAtMillis,
                outputPath = t.outputPath,
            )
        }
        LibraryUiState(
            list = if (items.isEmpty()) ListState.Empty else ListState.Content(items),
            query = q,
            isFiltered = q.search.isNotBlank() || q.filter != LibraryFilter.ALL,
        )
    }.catch { e ->
        emit(LibraryUiState(ListState.Error("Could not load the library.", e.message), LibraryQueryState(), false))
    }
}

// ---------------------------------------------------------------------------------------------------- Engine settings

data class EngineSettingsUiState(
    /** Auto first, then each engine. */
    val engines: List<ModeStatus>,
    /** Shown when the persisted choice cannot be used. No other engine is ever substituted. */
    val warning: String?,
)

class EnginePresenter(private val selector: EngineSelector) {
    val state: Flow<EngineSettingsUiState> = selector.modes.map { list ->
        val selected = list.firstOrNull { it.selected }
        EngineSettingsUiState(
            engines = list,
            warning = when {
                // Nothing is chosen (an unknown saved value): no engine is substituted, the user must pick one.
                selected == null -> "No download engine is selected. Downloads cannot start until you choose one below."
                !selected.selectable ->
                    "${selected.label} is selected but not available: ${(selected.availability as com.viddown.engine.api.EngineAvailability.NotAvailable).reason}. " +
                        "Downloads cannot start until an available engine is selected."
                else -> null
            },
        )
    }

    /** Returns the new selection to persist, or the reason it was refused. */
    fun choose(mode: EngineMode): EngineChoice = when (val o = selector.checkSelectable(mode)) {
        SelectOutcome.Allowed -> EngineChoice.Persist(mode)
        is SelectOutcome.Refused -> EngineChoice.Refused(o.reason)
    }

    companion object {
        /**
         * What to say after the user chose [now]: new downloads use it, but downloads that are already queued or running ([committed])
         * keep the engine they were created with. Null when there is nothing to explain (same choice, no pending download on
         * another engine, or Auto, which may use either engine).
         */
        fun switchNotice(now: EngineMode, previous: EngineMode?, committed: Set<EngineId>): String? {
            if (previous == now) return null
            val fixed = now.fixed ?: return null
            val others = committed.filter { it != fixed }.sortedBy { it.ordinal }
            if (others.isEmpty()) return null
            return "${fixed.label} will be used for new downloads. Downloads that are already queued or running keep their engine (${others.joinToString { it.label }})."
        }
    }
}

sealed interface EngineChoice {
    data class Persist(val mode: EngineMode) : EngineChoice
    data class Refused(val reason: String) : EngineChoice
}
