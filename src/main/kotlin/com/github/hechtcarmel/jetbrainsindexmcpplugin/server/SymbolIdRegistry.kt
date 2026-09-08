package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.ThreadContextElement
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap

/**
 * In-memory symbol handle storage owned by the running MCP server.
 *
 * A handle stores only an IntelliJ [SmartPsiElementPointer], never coordinates or a fallback
 * query. That distinction is deliberate: if IntelliJ cannot restore the exact PSI declaration,
 * resolving the handle fails with `SYMBOL_ID_EXPIRED` instead of selecting a nearby element.
 *
 * The cache is bounded by both an inactivity TTL and access-order LRU. The shared server epoch is
 * advanced and this cache is cleared whenever the embedded MCP server starts or stops, so IDs
 * never survive a restart. Each entry also keeps the identity (not merely the path) of its
 * [Project], preventing an ID created for a closed project from resolving in a newly opened
 * project at the same path.
 */
@Service(Service.Level.APP)
class SymbolIdRegistry @JvmOverloads constructor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idGenerator: () -> String = Companion::newOpaqueId,
    private val serverEpoch: McpServerEpoch = McpServerEpoch.shared
) : Disposable {

    private data class Entry(
        val project: WeakReference<Project>,
        var pointer: SmartPsiElementPointer<PsiElement>,
        val generation: Long,
        var lastAccessMillis: Long
    )

    // Insertion order plus explicit promotion gives O(1) non-promoting ownership lookup.
    // It also keeps last-access times ordered for amortized prefix expiry.
    private val entries = LinkedHashMap<String, Entry>()
    private val counters = CacheCounters()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    private val maintenance = CacheMaintenance(this, ::sweepExpired, ::removeProject)

    internal val responseHandleBudget: Int
        get() = minOf(maxEntries, PaginationService.MAX_PAGE_SIZE)

    /** Advances the shared MCP epoch and drops every symbol handle from the old session. */
    fun resetSession() {
        serverEpoch.advanceAndReset(::clearForSessionReset)
    }

    @Synchronized
    internal fun clearForSessionReset() {
        counters.removed(CacheEvictionReason.SESSION_RESET, entries.size)
        entries.clear()
    }

    /**
     * Registers [element] and returns an opaque handle.
     *
     * [preferredId] is used after a refactoring. If that handle is still owned by [project], its
     * pointer is rebound and the same ID is returned; if it was concurrently evicted, a new ID is
     * issued, which is the wire contract promised to callers.
     */
    @RequiresReadLock
    fun bind(project: Project, element: PsiElement, preferredId: String? = null): String {
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        return bind(
            project,
            serverEpoch.expectedForCurrentRequest(),
            pointer,
            preferredId
        ).getOrThrow()
    }

    /**
     * Registers a symbol only if the request still belongs to [expectedGeneration]. Long-running
     * navigation work captures the generation before its read action; this prevents it from
     * publishing a handle after a server stop/start reset.
     */
    @RequiresReadLock
    internal fun bind(
        project: Project,
        expectedGeneration: Long,
        element: PsiElement,
        preferredId: String? = null
    ): Result<String> {
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        return bind(project, expectedGeneration, pointer, preferredId)
    }

    internal fun currentGeneration(): Long = serverEpoch.expectedForCurrentRequest()

    /** Compatibility helper for focused registry tests; dispatchers use [McpServerEpoch] directly. */
    internal fun generationContext(): ThreadContextElement<Long?> =
        serverEpoch.requestContext(serverEpoch.capture())

    internal fun isCurrentGeneration(expectedGeneration: Long): Boolean =
        serverEpoch.isCurrent(expectedGeneration)

    private fun bind(
        project: Project,
        expectedGeneration: Long,
        pointer: SmartPsiElementPointer<PsiElement>,
        preferredId: String?
    ): Result<String> = serverEpoch.ifCurrent(
        expectedEpoch = expectedGeneration,
        stale = ::staleGeneration
    ) {
        synchronized(this) {
            require(!project.isDisposed) { "Cannot bind a symbol from a disposed project" }
            require(pointer.element?.isValid == true) { "Cannot bind an invalid PSI element" }

            val now = clock()
            evictExpiredPrefix(now)

            if (preferredId != null) {
                val existing = entries[preferredId]
                if (existing?.project?.get() === project && existing.generation == expectedGeneration) {
                    existing.pointer = pointer
                    promote(preferredId, existing, now)
                    return@synchronized Result.success(preferredId)
                }
            }

            var symbolId: String
            do {
                symbolId = idGenerator()
            } while (entries.containsKey(symbolId))

            entries[symbolId] = Entry(WeakReference(project), pointer, expectedGeneration, now)
            counters.insertions++
            evictLruOverflow()
            Result.success(symbolId)
        }
    }

    /**
     * Resolves [symbolId] to the exact stored PSI element.
     *
     * Must run under a read lock because dereferencing a smart pointer accesses PSI. No location,
     * name, or nearest-element fallback is attempted when the pointer no longer resolves.
     */
    @RequiresReadLock
    fun resolve(project: Project, symbolId: String): Result<PsiElement> {
        val expectedGeneration = serverEpoch.expectedForCurrentRequest()
        val resolved = serverEpoch.ifCurrent(expectedGeneration, stale = { null }) {
            synchronized(this) {
                val now = clock()
                evictExpiredPrefix(now)
                val candidate = entries[symbolId] ?: return@synchronized null
                val owner = candidate.project.get()
                if (owner == null || owner.isDisposed) {
                    remove(symbolId, CacheEvictionReason.PROJECT_CLOSED)
                    return@synchronized null
                }
                // A caller supplying the wrong project must not be able to destroy an otherwise
                // valid handle owned by another open project instance.
                if (owner !== project || project.isDisposed || candidate.generation != expectedGeneration) {
                    return@synchronized null
                }
                promote(symbolId, candidate, now)
                candidate to candidate.pointer
            }
        } ?: return expiredLookup(symbolId)
        val (entry, pointer) = resolved

        val element = runCatching { pointer.element }.getOrNull()
        if (element == null || !element.isValid) {
            synchronized(this) {
                if (entries[symbolId] === entry && entry.pointer === pointer) {
                    remove(symbolId, CacheEvictionReason.INVALIDATED)
                }
            }
            return expiredLookup(symbolId)
        }

        // resetSession(), invalidate(), or a post-refactoring rebind may have raced with pointer
        // dereferencing. Never let a handle from an older MCP generation resolve after reset.
        val stillCurrent = serverEpoch.ifCurrent(expectedGeneration, stale = { false }) {
            synchronized(this) {
                entries[symbolId] === entry && entry.pointer === pointer && entry.project.get() === project &&
                    entry.generation == expectedGeneration
            }
        }
        if (!stillCurrent) return expiredLookup(symbolId)
        synchronized(this) { counters.hits++ }
        return Result.success(element)
    }

    /**
     * Returns the exact live project instance owning [symbolId]. This lets the dispatcher route a
     * symbol-ID-only call even when several projects are open, without exposing a project path in
     * the opaque token.
     */
    fun projectFor(symbolId: String): Result<Project> {
        val expectedGeneration = serverEpoch.expectedForCurrentRequest()
        return serverEpoch.ifCurrent(expectedGeneration, stale = { expiredLookup(symbolId) }) {
            synchronized(this) {
                val now = clock()
                evictExpiredPrefix(now)
                val entry = entries[symbolId] ?: return@synchronized expiredLookup(symbolId)
                val project = entry.project.get()
                if (project == null || project.isDisposed) {
                    remove(symbolId, CacheEvictionReason.PROJECT_CLOSED)
                    return@synchronized expiredLookup(symbolId)
                }
                if (entry.generation != expectedGeneration) return@synchronized expiredLookup(symbolId)
                promote(symbolId, entry, now)
                counters.hits++
                Result.success(project)
            }
        }
    }

    /** Explicitly invalidates a handle after deleting its declaration. */
    fun invalidate(symbolId: String) {
        val expectedGeneration = serverEpoch.expectedForCurrentRequest()
        serverEpoch.ifCurrent(expectedGeneration, stale = {}) {
            synchronized(this) {
                if (entries[symbolId]?.generation == expectedGeneration) {
                    remove(symbolId, CacheEvictionReason.INVALIDATED)
                }
            }
        }
    }

    @Synchronized
    internal fun sizeForTest(): Int {
        evictExpiredPrefix(clock())
        return entries.size
    }

    override fun dispose() {
        maintenance.dispose()
        resetSession()
    }

    @Synchronized
    internal fun stats(): CacheStats = counters.snapshot(entries.size, entries.size.toLong())

    @Synchronized
    internal fun removeProject(project: Project) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.project.get() === project) {
                iterator.remove()
                counters.removed(CacheEvictionReason.PROJECT_CLOSED)
            }
        }
    }

    @Synchronized
    internal fun sweepExpired() {
        counters.maintenanceScans++
        val now = clock()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val reason = expiryReason(entry, now)
            if (reason != null) {
                iterator.remove()
                counters.removed(reason)
            }
        }
    }

    private fun evictExpiredPrefix(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val reason = expiryReason(iterator.next().value, now) ?: break
            iterator.remove()
            counters.removed(reason)
        }
    }

    private fun expiryReason(entry: Entry, now: Long): CacheEvictionReason? = when {
        entry.project.get()?.isDisposed != false -> CacheEvictionReason.PROJECT_CLOSED
        now - entry.lastAccessMillis >= ttlMillis -> CacheEvictionReason.TTL
        else -> null
    }

    private fun promote(symbolId: String, entry: Entry, now: Long) {
        entries.remove(symbolId)
        entry.lastAccessMillis = now
        entries[symbolId] = entry
    }

    private fun remove(symbolId: String, reason: CacheEvictionReason) {
        if (entries.remove(symbolId) != null) counters.removed(reason)
    }

    private fun evictLruOverflow() {
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
            counters.removed(CacheEvictionReason.LRU)
        }
    }

    private fun <T> expired(symbolId: String): Result<T> =
        Result.failure(IllegalArgumentException(ErrorMessages.symbolIdExpired(symbolId)))

    @Synchronized
    private fun <T> expiredLookup(symbolId: String): Result<T> {
        counters.misses++
        return expired(symbolId)
    }

    private fun <T> staleGeneration(): Result<T> = Result.failure(
        IllegalStateException(
            "The MCP server session changed while the symbol handle was being resolved. " +
                "Rediscover the symbol and retry the operation."
        )
    )

    companion object {
        const val DEFAULT_MAX_ENTRIES = 4_096
        const val DEFAULT_TTL_MILLIS = 60 * 60 * 1_000L

        private val secureRandom = SecureRandom()
        private val base64Url = Base64.getUrlEncoder().withoutPadding()

        fun getInstance(): SymbolIdRegistry =
            ApplicationManager.getApplication().getService(SymbolIdRegistry::class.java)

        private fun newOpaqueId(): String {
            val bytes = ByteArray(18)
            secureRandom.nextBytes(bytes)
            return "sym_${base64Url.encodeToString(bytes)}"
        }
    }
}
