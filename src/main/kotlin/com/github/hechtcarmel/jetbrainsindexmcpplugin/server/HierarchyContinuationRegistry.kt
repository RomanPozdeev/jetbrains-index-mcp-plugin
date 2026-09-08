package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap

/** Session/project-bound continuation storage for deterministic hierarchy BFS pages. */
@Service(Service.Level.APP)
class HierarchyContinuationRegistry @JvmOverloads constructor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = Companion::newOpaqueCursor
) : Disposable {

    internal sealed interface Continuation

    internal sealed interface CallWork

    internal data class PendingCallNode(
        val pointer: SmartPsiElementPointer<PsiElement>?,
        val snapshot: CallElement,
        val depth: Int,
        val modificationCount: Long = -1L
    ) : CallWork

    internal data class PendingCallExpansion(
        val pointer: SmartPsiElementPointer<PsiElement>,
        val depth: Int,
        val offset: Int
    ) : CallWork

    internal data class CallContinuation(
        val rootPointer: SmartPsiElementPointer<PsiElement>,
        val root: CallElement,
        val frontier: List<CallWork>,
        val visited: Set<String>,
        val visitedPointers: List<SmartPsiElementPointer<PsiElement>> = emptyList(),
        val direction: String,
        val maxDepth: Int,
        val scope: BuiltInSearchScope,
        val excludeGenerated: Boolean,
        val rootModificationCount: Long = -1L
    ) : Continuation

    internal sealed interface TypeWork

    internal data class PendingTypeNode(
        val pointer: SmartPsiElementPointer<PsiElement>?,
        val snapshot: TypeElement,
        val direction: TypeHierarchyDirection,
        val modificationCount: Long = -1L
    ) : TypeWork

    internal data class PendingTypeExpansion(
        val pointer: SmartPsiElementPointer<PsiElement>,
        val direction: TypeHierarchyDirection,
        val offset: Int
    ) : TypeWork

    internal data class TypeContinuation(
        val rootPointer: SmartPsiElementPointer<PsiElement>,
        val root: TypeElement,
        val frontier: List<TypeWork>,
        val visited: Set<String>,
        val visitedPointers: List<SmartPsiElementPointer<PsiElement>> = emptyList(),
        val scope: BuiltInSearchScope,
        val excludeGenerated: Boolean,
        val rootModificationCount: Long = -1L
    ) : Continuation

    private data class Entry(
        val project: WeakReference<Project>,
        val continuation: Continuation,
        val generation: Long,
        var lastAccessMillis: Long
    )

    internal data class Lease(
        val continuation: Continuation,
        val generation: Long
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var generation: Long = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    @Synchronized
    internal fun register(project: Project, continuation: Continuation): String {
        return register(project, generation, continuation).getOrThrow()
    }

    /**
     * Registers only if the request still belongs to [expectedGeneration]. A hierarchy query can
     * outlive stop/start while it is inside a read action; without this check it could publish an
     * old-session cursor after [resetSession] had already cleared the registry.
     */
    @Synchronized
    internal fun register(
        project: Project,
        expectedGeneration: Long,
        continuation: Continuation
    ): Result<String> {
        require(!project.isDisposed) { "Cannot register a hierarchy cursor for a disposed project" }
        if (expectedGeneration != generation) return staleGeneration()
        val now = clock()
        evictExpired(now)
        var cursor: String
        do cursor = idGenerator() while (entries.containsKey(cursor))
        val boundedContinuation = continuation.withBoundedVisitedPointers()
        if (boundedContinuation.visitedKeyBudgetExceeded()) {
            return Result.failure(
                IllegalStateException(
                    "Hierarchy continuation exceeds the stable visited-key budget " +
                        "($MAX_VISITED_KEYS_PER_CONTINUATION keys / $MAX_VISITED_KEY_CHARS_PER_CONTINUATION characters). " +
                        "Restart with a smaller maxNodes or depth."
                )
            )
        }
        if (boundedContinuation.pointerCount() > MAX_POINTERS_PER_CONTINUATION) {
            return Result.failure(
                IllegalStateException(
                    "Hierarchy continuation exceeds the $MAX_POINTERS_PER_CONTINUATION smart-pointer limit. " +
                        "Restart with a smaller maxNodes or depth."
                )
            )
        }
        entries[cursor] = Entry(WeakReference(project), boundedContinuation, generation, now)
        evictLruOverflow()
        return Result.success(cursor)
    }

    @Synchronized
    internal fun resolve(project: Project, cursor: String): Result<Continuation> {
        return resolveLease(project, cursor).map { it.continuation }
    }

    @Synchronized
    internal fun resolveLease(project: Project, cursor: String): Result<Lease> {
        val now = clock()
        evictExpired(now)
        // LinkedHashMap is access ordered. Do not use get() until ownership is validated: a
        // wrong-project probe must not promote somebody else's cursor and influence LRU eviction.
        val entry = entries.entries.firstOrNull { it.key == cursor }?.value
            ?: return expired(cursor)
        val owner = entry.project.get()
        if (owner == null || owner.isDisposed) {
            entries.remove(cursor)
            return expired(cursor)
        }
        if (owner !== project || project.isDisposed || entry.generation != generation) return expired(cursor)
        entries[cursor] // Promote only after successful project/session validation.
        entry.lastAccessMillis = now
        return Result.success(Lease(entry.continuation, entry.generation))
    }

    @Synchronized
    internal fun currentGeneration(): Long = generation

    @Synchronized
    internal fun isCurrentGeneration(expectedGeneration: Long): Boolean = generation == expectedGeneration

    /** Drops every continuation belonging to the previous MCP server generation. */
    @Synchronized
    fun resetSession() {
        entries.clear()
        generation++
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

    /**
     * Pointer identity is only an optimisation: tools also retain stable string keys for every
     * discovered node. Keeping an unlimited historical pointer list in an application service
     * would otherwise retain PSI graphs for the lifetime of a cursor chain.
     */
    private fun Continuation.withBoundedVisitedPointers(): Continuation = when (this) {
        is TypeContinuation -> copy(visitedPointers = visitedPointers.take(MAX_VISITED_POINTERS_PER_CONTINUATION))
        is CallContinuation -> copy(visitedPointers = visitedPointers.take(MAX_VISITED_POINTERS_PER_CONTINUATION))
    }

    /**
     * Stable keys preserve dedup when smart pointers are invalidated, so truncating them would
     * reintroduce duplicates. Reject an oversized continuation instead of silently weakening the
     * traversal invariant or retaining unbounded application-service memory.
     */
    private fun Continuation.visitedKeyBudgetExceeded(): Boolean {
        val keys = when (this) {
            is TypeContinuation -> visited
            is CallContinuation -> visited
        }
        if (keys.size > MAX_VISITED_KEYS_PER_CONTINUATION) return true
        var characters = 0L
        for (key in keys) {
            characters += key.length.toLong()
            if (characters > MAX_VISITED_KEY_CHARS_PER_CONTINUATION) return true
        }
        return false
    }

    /** Includes the root, bounded historical identity, and every live work item in the frontier. */
    private fun Continuation.pointerCount(): Int = when (this) {
        is TypeContinuation -> 1 + visitedPointers.size + frontier.count { work ->
            when (work) {
                is PendingTypeNode -> work.pointer != null
                is PendingTypeExpansion -> true
            }
        }
        is CallContinuation -> 1 + visitedPointers.size + frontier.count { work ->
            when (work) {
                is PendingCallNode -> work.pointer != null
                is PendingCallExpansion -> true
            }
        }
    }

    private fun <T> expired(cursor: String): Result<T> = Result.failure(
        IllegalArgumentException(
            "Hierarchy cursor '$cursor' expired, was evicted, or belongs to a different MCP session/project. " +
                "Run the hierarchy query again without cursor."
        )
    )

    private fun <T> staleGeneration(): Result<T> = Result.failure(
        IllegalStateException(
            "The MCP server session changed while the hierarchy page was being computed. " +
                "Run the hierarchy query again without cursor."
        )
    )

    companion object {
        const val DEFAULT_MAX_ENTRIES = 128
        const val DEFAULT_TTL_MILLIS = 10 * 60 * 1_000L
        internal const val MAX_VISITED_POINTERS_PER_CONTINUATION = 4_096
        internal const val MAX_POINTERS_PER_CONTINUATION = 8_192
        internal const val MAX_VISITED_KEYS_PER_CONTINUATION = 8_192
        internal const val MAX_VISITED_KEY_CHARS_PER_CONTINUATION = 256_000

        private val secureRandom = SecureRandom()
        private val base64Url = Base64.getUrlEncoder().withoutPadding()

        fun getInstance(): HierarchyContinuationRegistry =
            ApplicationManager.getApplication().getService(HierarchyContinuationRegistry::class.java)

        private fun newOpaqueCursor(): String {
            val bytes = ByteArray(18)
            secureRandom.nextBytes(bytes)
            return "hier_${base64Url.encodeToString(bytes)}"
        }
    }
}
