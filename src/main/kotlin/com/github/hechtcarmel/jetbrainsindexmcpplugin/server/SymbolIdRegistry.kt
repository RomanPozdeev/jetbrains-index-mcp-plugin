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
import kotlinx.coroutines.asContextElement
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
 * The cache is bounded by both an inactivity TTL and access-order LRU. [resetSession] is called
 * whenever the embedded MCP server starts or stops, so IDs never survive an MCP restart. Each
 * entry also keeps the identity (not merely the path) of its [Project], preventing an ID created
 * for a closed project from resolving in a newly opened project at the same path.
 */
@Service(Service.Level.APP)
class SymbolIdRegistry @JvmOverloads constructor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = Companion::newOpaqueId
) : Disposable {

    private data class Entry(
        val project: WeakReference<Project>,
        var pointer: SmartPsiElementPointer<PsiElement>,
        val generation: Long,
        var lastAccessMillis: Long
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var generation: Long = 0L
    private val requestGeneration = ThreadLocal<Long?>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    /** Drops every handle belonging to the previous MCP server generation. */
    @Synchronized
    fun resetSession() {
        entries.clear()
        generation++
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
        return synchronized(this) {
            bind(project, requestGeneration.get() ?: generation, pointer, preferredId).getOrThrow()
        }
    }

    /**
     * Registers a symbol only if the request still belongs to [expectedGeneration]. Long-running
     * navigation work captures the generation before its read action; this prevents it from
     * publishing a handle after a server stop/start reset.
     */
    @RequiresReadLock
    @Synchronized
    internal fun bind(
        project: Project,
        expectedGeneration: Long,
        element: PsiElement,
        preferredId: String? = null
    ): Result<String> {
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        return bind(project, expectedGeneration, pointer, preferredId)
    }

    @Synchronized
    internal fun currentGeneration(): Long = generation

    /** Captures the server generation for one dispatched coroutine and all of its thread hops. */
    internal fun generationContext(): ThreadContextElement<Long?> =
        requestGeneration.asContextElement(currentGeneration())

    @Synchronized
    internal fun isCurrentGeneration(expectedGeneration: Long): Boolean = generation == expectedGeneration

    private fun bind(
        project: Project,
        expectedGeneration: Long,
        pointer: SmartPsiElementPointer<PsiElement>,
        preferredId: String?
    ): Result<String> {
        require(!project.isDisposed) { "Cannot bind a symbol from a disposed project" }
        require(pointer.element?.isValid == true) { "Cannot bind an invalid PSI element" }
        if (expectedGeneration != generation) return staleGeneration()

        val now = clock()
        evictExpired(now)

        if (preferredId != null) {
            val existing = entries[preferredId]
            if (existing?.project?.get() === project && existing.generation == generation) {
                existing.pointer = pointer
                existing.lastAccessMillis = now
                return Result.success(preferredId)
            }
        }

        var symbolId: String
        do {
            symbolId = idGenerator()
        } while (entries.containsKey(symbolId))

        entries[symbolId] = Entry(WeakReference(project), pointer, generation, now)
        evictLruOverflow()
        return Result.success(symbolId)
    }

    /**
     * Resolves [symbolId] to the exact stored PSI element.
     *
     * Must run under a read lock because dereferencing a smart pointer accesses PSI. No location,
     * name, or nearest-element fallback is attempted when the pointer no longer resolves.
     */
    @RequiresReadLock
    fun resolve(project: Project, symbolId: String): Result<PsiElement> {
        val (entry, pointer) = synchronized(this) {
            val now = clock()
            evictExpired(now)
            // LinkedHashMap is access ordered. Do not use get() until the handle's owner has
            // been validated: a foreign probe must not affect LRU eviction.
            val candidate = entries.entries.firstOrNull { it.key == symbolId }?.value ?: return expired(symbolId)
            val owner = candidate.project.get()
            if (owner == null || owner.isDisposed) {
                entries.remove(symbolId)
                return expired(symbolId)
            }
            // A caller supplying the wrong project must not be able to destroy an otherwise
            // valid handle owned by another open project instance.
            if (owner !== project || project.isDisposed || candidate.generation != generation) return expired(symbolId)
            entries[symbolId] // Promote only after project/session validation.
            candidate.lastAccessMillis = now
            candidate to candidate.pointer
        }

        val element = runCatching { pointer.element }.getOrNull()
        if (element == null || !element.isValid) {
            synchronized(this) {
                if (entries[symbolId] === entry && entry.pointer === pointer) entries.remove(symbolId)
            }
            return expired(symbolId)
        }

        // resetSession(), invalidate(), or a post-refactoring rebind may have raced with pointer
        // dereferencing. Never let a handle from an older MCP generation resolve after reset.
        val stillCurrent = synchronized(this) {
            entries[symbolId] === entry && entry.pointer === pointer && entry.project.get() === project &&
                entry.generation == generation
        }
        if (!stillCurrent) return expired(symbolId)
        return Result.success(element)
    }

    /**
     * Returns the exact live project instance owning [symbolId]. This lets the dispatcher route a
     * symbol-ID-only call even when several projects are open, without exposing a project path in
     * the opaque token.
     */
    fun projectFor(symbolId: String): Result<Project> = synchronized(this) {
        val now = clock()
        evictExpired(now)
        val entry = entries.entries.firstOrNull { it.key == symbolId }?.value ?: return expired(symbolId)
        val project = entry.project.get()
        if (project == null || project.isDisposed) {
            entries.remove(symbolId)
            return expired(symbolId)
        }
        if (entry.generation != generation) return expired(symbolId)
        entries[symbolId]
        entry.lastAccessMillis = now
        Result.success(project)
    }

    /** Explicitly invalidates a handle after deleting its declaration. */
    @Synchronized
    fun invalidate(symbolId: String) {
        entries.remove(symbolId)
    }

    @Synchronized
    internal fun sizeForTest(): Int {
        evictExpired(clock())
        return entries.size
    }

    override fun dispose() = resetSession()

    private fun evictExpired(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now - entry.lastAccessMillis >= ttlMillis || entry.project.get()?.isDisposed != false) {
                iterator.remove()
            }
        }
    }

    private fun evictLruOverflow() {
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun <T> expired(symbolId: String): Result<T> =
        Result.failure(IllegalArgumentException(ErrorMessages.symbolIdExpired(symbolId)))

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
