package com.viddown.engine.selector

import com.viddown.engine.api.DownloadEngine
import com.viddown.engine.api.DownloadError
import com.viddown.engine.api.EngineAvailability
import com.viddown.engine.api.EngineId
import com.viddown.engine.api.EngineMode
import com.viddown.engine.api.EngineRouting
import com.viddown.engine.api.EngineResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cheap description of an engine. Reading [availability] must never start or load the engine. */
data class EngineDescriptor(
    val id: EngineId,
    val description: String,
    val availability: () -> EngineAvailability,
)

interface EngineRegistry {
    val descriptors: List<EngineDescriptor>

    /** Creates the engine. Only [EngineSelector] calls this: for the selected engine, and only when it is available. */
    fun create(id: EngineId): DownloadEngine
}

/** What Settings shows for one engine. */
data class EngineStatus(
    val id: EngineId,
    val description: String,
    val availability: EngineAvailability,
    val selected: Boolean,
) {
    val label: String get() = id.label

    /** An engine that is not integrated or not usable on this device cannot be chosen. */
    val selectable: Boolean get() = availability.isAvailable
}

/** What Settings shows for one choice: Auto or one engine. */
data class ModeStatus(
    val mode: EngineMode,
    val description: String,
    val availability: EngineAvailability,
    val selected: Boolean,
) {
    val label: String get() = mode.label
    val selectable: Boolean get() = availability.isAvailable
}

sealed interface SelectOutcome {
    data object Allowed : SelectOutcome
    data class Refused(val reason: String) : SelectOutcome
}

/**
 * The user's choice is an [EngineMode]: one fixed engine, or Auto (the choice is persisted elsewhere and arrives here as [selectedMode]).
 *  * Fixed mode: exactly one engine is used for everything. When it is unavailable, [activeEngine] fails and names it. There is no fallback.
 *  * Auto mode: [engineForUrl] decides per link (YouTube -> NewPipe, everything else -> yt-dlp, see [EngineRouting]). There is still no
 *    fallback: when the engine a link needs is unavailable, that link fails and names the engine.
 *  * When there is no valid choice at all ([selectedMode] emits null) every call fails with "no engine selected": nothing is picked for the user.
 *  * Engines that are not needed are never created, so their runtimes are never loaded (Auto creates an engine when a link needs it).
 *  * Changing the selection does not touch a running download: an engine stays alive until [releaseUnused] is told it is free,
 *    and is then closed. Whoever follows [selection] (the download manager) calls it on every change.
 *
 * Creating, selecting and closing are serialised by one lock, and the selection is read INSIDE that lock. So an engine is never
 * created for a choice that was already replaced, and a release never closes the engine that has just been chosen.
 */
class EngineSelector(
    private val registry: EngineRegistry,
    private val selectedMode: Flow<EngineMode?>,
    /** Called when closing an engine threw; the other engines are still closed. Receives whatever the engine threw. */
    private val onCloseFailed: (EngineId, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Mutex()
    private val created = LinkedHashMap<EngineId, DownloadEngine>()

    /** The persisted choice as it changes; null while nothing valid is chosen. Reading it never creates an engine. */
    val selection: Flow<EngineMode?> = selectedMode.distinctUntilChanged()

    /** Ids of the engines that exist right now (for diagnostics and tests). */
    suspend fun createdEngineIds(): Set<EngineId> = lock.withLock { created.keys.toSet() }

    /** The two real engines. In Auto neither is "selected": Auto decides per link. */
    val statuses: Flow<List<EngineStatus>> = selectedMode.map { mode ->
        registry.descriptors.map { d ->
            EngineStatus(id = d.id, description = d.description, availability = d.availability(), selected = d.id == mode?.fixed)
        }
    }

    /** What Settings lists: Auto first, then each engine. Reading it never creates an engine. */
    val modes: Flow<List<ModeStatus>> = selectedMode.map { mode ->
        val descriptors = registry.descriptors
        val availabilities = descriptors.map { it.id to it.availability() }
        val auto = if (availabilities.any { it.second.isAvailable }) {
            val missing = availabilities.filter { !it.second.isAvailable }.map { it.first.label }
            ModeStatus(
                mode = EngineMode.AUTO,
                description = AUTO_DESCRIPTION + if (missing.isEmpty()) "" else " ${missing.joinToString()} is not available, so links that need it will fail.",
                availability = EngineAvailability.Available,
                selected = mode == EngineMode.AUTO,
            )
        } else {
            ModeStatus(EngineMode.AUTO, AUTO_DESCRIPTION, EngineAvailability.NotAvailable("No download engine is available"), mode == EngineMode.AUTO)
        }
        listOf(auto) + descriptors.map { d ->
            ModeStatus(mode = EngineMode.of(d.id), description = d.description, availability = d.availability(), selected = mode == EngineMode.of(d.id))
        }
    }

    fun checkSelectable(mode: EngineMode): SelectOutcome {
        val id = mode.fixed ?: return if (registry.descriptors.any { it.availability().isAvailable }) {
            SelectOutcome.Allowed
        } else {
            SelectOutcome.Refused("No download engine is available.")
        }
        return checkSelectable(id)
    }

    fun checkSelectable(id: EngineId): SelectOutcome {
        val descriptor = registry.descriptors.firstOrNull { it.id == id }
            ?: return SelectOutcome.Refused("${id.label} is not part of this build.")
        return when (val a = descriptor.availability()) {
            EngineAvailability.Available -> SelectOutcome.Allowed
            is EngineAvailability.NotAvailable -> SelectOutcome.Refused(a.reason)
        }
    }

    /**
     * The engine for the currently selected id, created on first use. Never substitutes another engine: an unavailable choice and
     * a missing choice are both reported as errors that name the problem.
     */
    suspend fun activeEngine(): EngineResult<DownloadEngine> = lock.withLock {
        val mode = selectedMode.first() ?: return@withLock EngineResult.Failure(DownloadError.noEngineSelected())
        val id = mode.fixed ?: return@withLock EngineResult.Failure(DownloadError.noEngineSelected("Auto mode chooses the engine per link: use engineForUrl."))
        getOrCreate(id)
    }

    /**
     * The engine for [url]: the fixed engine in a fixed mode, or in Auto the one [EngineRouting] picks for it (YouTube -> NewPipe,
     * everything else -> yt-dlp). The pick is a host check on the text, so it adds no noticeable time.
     */
    suspend fun engineForUrl(url: String?): EngineResult<DownloadEngine> = lock.withLock {
        val mode = selectedMode.first() ?: return@withLock EngineResult.Failure(DownloadError.noEngineSelected())
        getOrCreate(mode.fixed ?: EngineRouting.engineFor(url))
    }

    /**
     * The engine with [id], created on first use if available. Only for the engine a download was CREATED with (it keeps it for its
     * whole life, even after the selection changed). Everything that follows the user's choice uses [activeEngine].
     */
    suspend fun engineFor(id: EngineId): EngineResult<DownloadEngine> = lock.withLock { getOrCreate(id) }

    /** Lock held. */
    private fun getOrCreate(id: EngineId): EngineResult<DownloadEngine> {
        created[id]?.let { return EngineResult.Success(it) }
        val descriptor = registry.descriptors.firstOrNull { it.id == id }
        val availability = descriptor?.availability() ?: EngineAvailability.NotAvailable("Not part of this build")
        if (availability is EngineAvailability.NotAvailable) {
            return EngineResult.Failure(DownloadError.engineUnavailable(id, availability.reason))
        }
        val engine = registry.create(id)
        created[id] = engine
        return EngineResult.Success(engine)
    }

    /**
     * Closes every engine that is neither the selected one nor in use by a download ([inUse]). Safe to call at any time and as
     * often as wanted; calling it after each selection change is what keeps a previously selected engine (and its runtime) from
     * staying loaded. Returns the ids that were closed.
     */
    suspend fun releaseUnused(inUse: Set<EngineId>): Set<EngineId> = releaseUnusedBy { inUse }

    /**
     * Same, but [inUse] is asked INSIDE the selector's lock. A caller whose set was computed earlier could miss a download that
     * started in between and close an engine that download is about to use; asking late closes that gap. [inUse] must be quick,
     * must not suspend and must not call back into this selector.
     */
    suspend fun releaseUnusedBy(inUse: () -> Set<EngineId>): Set<EngineId> = lock.withLock {
        val mode = selectedMode.first()
        val busy = inUse()
        // Auto may need either engine at any moment, so it keeps what exists; a fixed mode keeps only its own engine.
        val ids = if (mode == EngineMode.AUTO) emptyList() else created.keys.filter { it != mode?.fixed && it !in busy }
        ids.forEach { id -> closeQuietly(id, created.remove(id)!!) }
        ids.toSet()
    }

    suspend fun closeAll() {
        lock.withLock {
            val all = created.toMap()
            created.clear()
            all.forEach { (id, engine) -> closeQuietly(id, engine) }
        }
    }

    /** Lock held: closing under the lock means a new engine of the same kind is never created while the old one is still closing. */
    private suspend fun closeQuietly(id: EngineId, engine: DownloadEngine) {
        // NonCancellable: the engine is already removed from the map, so a cancelled caller must not leave it open.
        withContext(NonCancellable) {
            try {
                engine.close()
            } catch (t: Throwable) {
                onCloseFailed(id, t)
            }
        }
    }

    private companion object {
        const val AUTO_DESCRIPTION = "YouTube links use NewPipe, every other link uses yt-dlp."
    }
}
