package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript.isJsTsElementOrFile
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript.resolveJsTsCallHierarchySeed
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.CallWork
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.CallContinuation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingCallExpansion
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingCallNode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import java.util.ArrayDeque
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for analyzing method call relationships across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class CallHierarchyTool : AbstractMcpTool() {

    override val supportsUnifiedTarget: Boolean = true

    override val name = "ide_call_hierarchy"

    override val description = """
        Build a call hierarchy tree for a method/function. Use to trace execution flow—find what calls this method (callers) or what this method calls (callees).

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Rust note: "callers" direction works well; "callees" direction may have limited results due to Rust plugin PSI resolution constraints.

        Returns deterministic breadth-first pages with method signatures, file locations, symbolId handles, and pagination metadata. Live progress is not available on stateless HTTP; continue with cursor.

        Target (mutually exclusive):
        - symbolId: opaque handle returned by a previous semantic call
        - file + line + column: position-based lookup
        - language + symbol: fully qualified symbol reference (supported languages: ${supportedSymbolReferenceLanguagesDescription()})

        Parameters: direction (required for a fresh query): "callers" or "callees". depth (optional, default: 3, max: 5), maxNodes (optional, default: 100, max: 500), cursor (continuation page), scope (optional).

        Example: {"file": "src/Service.java", "line": 42, "column": 10, "direction": "callers"}
        Example: {"language": "Java", "symbol": "com.example.Service#processRequest(String)", "direction": "callers", "scope": "project_and_libraries"}
        Example: {"language": "JavaScript", "symbol": "src/handlers#processRequest", "direction": "callers"}
        Example: {"language": "PHP", "symbol": "\\App\\Service\\UserService::find()", "direction": "callers"}
        """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .file(required = false, description = "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin. Required for position-based lookup.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .enumProperty("direction", "Direction for a fresh query: 'callers' or 'callees'. Omit when cursor is provided.", listOf("callers", "callees"))
        .intProperty("depth", "How many levels deep to traverse the call hierarchy (default: 3, max: 5)")
        .intProperty("maxNodes", "Maximum hierarchy nodes returned and expanded in this page. Default: 100, max: 500.")
        .stringProperty("cursor", "Opaque session/project-bound continuation from the previous hierarchy page. Other search parameters are ignored.")
        .scopeProperty("Search scope. Default: project_files.")
        .booleanProperty(ParamNames.INCLUDE_GENERATED, "Include callers/callees in generated sources (KSP/Dagger/annotation-processor output). Default: true.")
        .build()

    companion object {
        private const val DEFAULT_DEPTH = 3
        private const val MAX_DEPTH = 5
        private const val LEGACY_FIRST_PAGE_MAX_NODES = 20
        private const val DEFAULT_MAX_NODES = 100
        private const val MAX_NODES = 500
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAt = System.currentTimeMillis()
        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
        val explicitMaxNodes = arguments["maxNodes"]?.jsonPrimitive?.intOrNull
        // Before hierarchy pagination, language handlers exposed at most 20 direct calls on the
        // initial request. Keep that wire-compatible default while allowing explicit maxNodes to
        // opt into a larger first page; continuation pages retain the new 100-node default.
        val maxNodes = explicitMaxNodes ?: if (cursor == null) {
            LEGACY_FIRST_PAGE_MAX_NODES
        } else {
            DEFAULT_MAX_NODES
        }
        if (maxNodes !in 1..MAX_NODES) {
            return createErrorResult("maxNodes must be between 1 and $MAX_NODES")
        }

        val continuationRegistry = HierarchyContinuationRegistry.getInstance()
        if (cursor != null) {
            requireSmartMode(project)
            val lease = continuationRegistry.resolveLease(project, cursor).getOrElse {
                return createErrorResult(it.message ?: "Hierarchy cursor expired")
            }
            val continuation = lease.continuation as? CallContinuation
                ?: return createErrorResult("Cursor belongs to a different hierarchy tool. Start ide_call_hierarchy again without cursor.")
            return suspendingReadAction {
                buildCallPage(project, continuation, maxNodes, startedAt, lease.generation)
            }
        }

        val generation = continuationRegistry.currentGeneration()

        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: direction for a fresh hierarchy query")
        val depth = (arguments["depth"]?.jsonPrimitive?.int ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH)
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        if (direction !in listOf("callers", "callees")) {
            return createErrorResult("direction must be 'callers' or 'callees'")
        }
        val excludeGenerated = resolveExcludeGenerated(arguments, default = true)

        requireSmartMode(project)

        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val element = resolveCallHierarchySeed(project, arguments).getOrElse {
                return@suspendingReadAction createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No call hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            // Ask the language adapter for exactly one edge. The tool owns the bounded BFS and
            // never lets a handler recursively materialize the complete graph.
            val hierarchyData = handler.getCallHierarchy(
                element,
                project,
                direction,
                1,
                scope,
                excludeGenerated,
                page = HierarchyPageRequest(offset = 0, limit = maxNodes + 1)
            )
            if (hierarchyData == null) {
                val isSymbolMode = optionalStringArg(arguments, ParamNames.LANGUAGE) != null
                return@suspendingReadAction createErrorResult(
                    if (isSymbolMode) "No method/function found for the specified symbol"
                    else "No method/function found at position"
                )
            }

            val pointerManager = SmartPointerManager.getInstance(project)
            val modificationCount = PsiModificationTracker.getInstance(project).modificationCount
            val root = convertToCallElement(
                project,
                hierarchyData.element,
                element,
                optionalStringArg(arguments, ParamNames.SYMBOL_ID)
            ).copy(children = null)
            // Position lookup may start on a call-site reference. The handler has already
            // resolved it to the callable declaration; continuation identity must follow that
            // declaration rather than the disposable caller leaf.
            val rootTarget = PsiUtils.resolveNavigationTarget(
                hierarchyData.element.pointerTarget ?: element
            )
            val visited = linkedSetOf(hierarchyKey(rootTarget, root))
            val rootPointer = pointerManager.createSmartPsiElementPointer(rootTarget)
            val visitedPointers = mutableListOf<SmartPsiElementPointer<PsiElement>>(rootPointer)
            val frontier = buildList<CallWork> {
                hierarchyData.calls
                    .sortedWith(callDataComparator)
                    .mapNotNullTo(this) { data ->
                        pendingCallNode(
                            project, pointerManager, data, depth = 1, visited = visited,
                            visitedPointers = visitedPointers
                        )
                    }
                hierarchyData.nextOffset?.let { offset ->
                    add(PendingCallExpansion(rootPointer, depth = 0, offset = offset))
                }
            }
            val continuation = CallContinuation(
                rootPointer = rootPointer,
                root = root,
                frontier = frontier,
                visited = visited,
                visitedPointers = visitedPointers,
                direction = direction,
                maxDepth = depth,
                scope = scope,
                excludeGenerated = excludeGenerated,
                rootModificationCount = modificationCount
            )
            buildCallPage(project, continuation, maxNodes, startedAt, generation)
        }
    }

    private fun buildCallPage(
        project: Project,
        continuation: CallContinuation,
        maxNodes: Int,
        startedAt: Long,
        generation: Long
    ): CallToolResult {
        val registry = HierarchyContinuationRegistry.getInstance()
        if (!registry.isCurrentGeneration(generation)) {
            return createErrorResult("The MCP server session changed. Start the hierarchy query again without cursor.")
        }
        val rootElement = continuation.rootPointer.element
            ?: return createErrorResult("Hierarchy target was deleted or became invalid. Start the query again without cursor.")
        val pointerManager = SmartPointerManager.getInstance(project)
        val queue = ArrayDeque(continuation.frontier)
        val visited = continuation.visited.toMutableSet()
        val visitedPointers = continuation.visitedPointers.toMutableList()
        val returned = mutableListOf<CallElement>()
        var discoveredThisPage = 0
        var expandedThisPage = 0
        val modificationCount = PsiModificationTracker.getInstance(project).modificationCount

        val root = if (continuation.rootModificationCount == modificationCount) continuation.root else {
            refreshCallSnapshot(
                project,
                rootElement,
                continuation.root,
                continuation.direction,
                continuation.scope,
                continuation.excludeGenerated
            )
        }

        fun hasKnownPendingNode(): Boolean = queue.any { work ->
            work is PendingCallNode && (work.pointer == null || work.pointer.element != null)
        }

        // Do not issue a cursor for edge probes alone. At a terminal leaf we resolve pending
        // probes now, stopping at the first live node, so hasMore always represents a node the
        // following page can return rather than an empty terminal page.
        while (queue.isNotEmpty() && (returned.size < maxNodes || !hasKnownPendingNode())) {
            ProgressManager.checkCanceled()
            when (val work = queue.removeFirst()) {
                is PendingCallNode -> {
                    val target = work.pointer?.element
                    if (work.pointer != null && target == null) continue
                    val snapshot = if (target == null || work.modificationCount == modificationCount) {
                        work.snapshot.copy(children = null)
                    } else {
                        refreshCallSnapshot(
                            project,
                            target,
                            work.snapshot,
                            continuation.direction,
                            continuation.scope,
                            continuation.excludeGenerated
                        )
                    }
                    returned += snapshot
                    if (target != null && work.depth < continuation.maxDepth) {
                        queue.addLast(
                            PendingCallExpansion(
                                pointerManager.createSmartPsiElementPointer(PsiUtils.resolveNavigationTarget(target)),
                                depth = work.depth,
                                offset = 0
                            )
                        )
                    }
                }

                is PendingCallExpansion -> {
                    val lookingAhead = !hasKnownPendingNode()
                    if ((discoveredThisPage >= maxNodes || expandedThisPage >= maxNodes) && !lookingAhead) {
                        queue.addFirst(work)
                        break
                    }
                    expandedThisPage++
                    val target = work.pointer.element ?: continue
                    val handler = LanguageHandlerRegistry.getCallHierarchyHandler(target) ?: continue
                    val scanLimit = (
                        work.offset.toLong() + (maxNodes - discoveredThisPage).coerceAtLeast(0).toLong() + 1L
                    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val direct = handler.getCallHierarchy(
                        target,
                        project,
                        continuation.direction,
                        1,
                        continuation.scope,
                        continuation.excludeGenerated,
                        page = HierarchyPageRequest(offset = 0, limit = scanLimit)
                    ) ?: continue
                    val pendingChildren = direct.calls.sortedWith(callDataComparator).mapNotNull { child ->
                        pendingCallNode(
                            project, pointerManager, child, work.depth + 1, visited, visitedPointers
                        )
                    }
                    discoveredThisPage += maxOf(
                        (direct.calls.size - work.offset).coerceAtLeast(0),
                        pendingChildren.size
                    )
                    val additions = buildList<CallWork> {
                        addAll(pendingChildren)
                        direct.nextOffset?.let { nextOffset ->
                            if (nextOffset > work.offset) add(work.copy(offset = nextOffset))
                        }
                    }
                    for (addition in additions.asReversed()) queue.addFirst(addition)
                }
            }
        }

        val nextState = continuation.copy(
            rootPointer = pointerManager.createSmartPsiElementPointer(PsiUtils.resolveNavigationTarget(rootElement)),
            root = root,
            frontier = queue.toList(),
            visited = visited,
            visitedPointers = visitedPointers,
            rootModificationCount = modificationCount
        )
        val hasMore = nextState.frontier.any { work ->
            work is PendingCallNode && (work.pointer == null || work.pointer.element != null)
        }
        val nextCursor = if (hasMore) {
            registry.register(project, generation, nextState).getOrElse {
                return createErrorResult(it.message ?: "The MCP server session changed")
            }
        } else null

        return createJsonResult(
            CallHierarchyResult(
                element = root,
                calls = returned,
                returnedNodes = returned.size,
                truncated = hasMore,
                elapsedMs = System.currentTimeMillis() - startedAt,
                hasMore = hasMore,
                cursor = nextCursor
            )
        )
    }

    private fun refreshCallSnapshot(
        project: Project,
        target: PsiElement,
        previous: CallElement,
        direction: String,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): CallElement {
        val handler = LanguageHandlerRegistry.getCallHierarchyHandler(target) ?: return previous
        val fresh = handler.getCallHierarchy(
            target,
            project,
            direction,
            1,
            scope,
            excludeGenerated,
            page = HierarchyPageRequest(offset = 0, limit = 0)
        )?.element ?: return previous
        return convertToCallElement(project, fresh, target, previous.symbolId).copy(children = null)
    }

    private fun pendingCallNode(
        project: Project,
        pointerManager: SmartPointerManager,
        data: CallElementData,
        depth: Int,
        visited: MutableSet<String>,
        visitedPointers: MutableList<SmartPsiElementPointer<PsiElement>>
    ): PendingCallNode? {
        val snapshot = convertToCallElement(project, data).copy(children = null)
        val target = data.pointerTarget?.let(PsiUtils::resolveNavigationTarget)
        // Keep the handler's declaration target for JVM signatures even when navigation resolves
        // it to a Kotlin source node that does not itself expose the parameter list.
        val key = hierarchyKey(data.pointerTarget ?: target, snapshot)
        val pointer = if (target == null) {
            if (!visited.add(key)) return null
            null
        } else {
            val psiManager = PsiManager.getInstance(project)
            if (visitedPointers.any { existing ->
                    existing.element?.let { it === target || psiManager.areElementsEquivalent(it, target) } == true
                }
            ) return null
            if (!visited.add(key)) return null
            pointerManager.createSmartPsiElementPointer(target).also { pointer ->
                if (visitedPointers.size < HierarchyContinuationRegistry.MAX_VISITED_POINTERS_PER_CONTINUATION) {
                    visitedPointers += pointer
                }
            }
        }
        return PendingCallNode(
            pointer = pointer,
            snapshot = snapshot,
            depth = depth,
            modificationCount = PsiModificationTracker.getInstance(project).modificationCount
        )
    }

    private fun hierarchyKey(element: PsiElement?, snapshot: CallElement): String {
        if (element != null) {
            val target = PsiUtils.resolveNavigationTarget(element)
            val file = target.containingFile?.virtualFile?.url.orEmpty()
            val signature = (element as? PsiMethod ?: target as? PsiMethod)?.let { method ->
                val owner = method.containingClass?.qualifiedName.orEmpty()
                val parameters = method.parameterList.parameters.joinToString(",") { parameter ->
                    runCatching { parameter.type.canonicalText }.getOrDefault("?")
                }
                "$owner#${method.name}($parameters)"
            } ?: PsiUtils.qualifiedName(target).orEmpty()
            if (signature.isNotBlank()) return "${target.javaClass.name}|$file|$signature"
            return "${target.javaClass.name}|$file|${snapshot.name}|${snapshot.line}|${snapshot.column}|${target.textOffset}"
        }
        return "${snapshot.language}|${snapshot.file}|${snapshot.name}|${snapshot.line}|${snapshot.column}"
    }

    private val callDataComparator = compareBy<CallElementData>(
        { it.language },
        { it.file },
        { it.line },
        { it.column },
        { it.name }
    )

    private fun resolveCallHierarchySeed(project: Project, arguments: JsonObject): Result<com.intellij.psi.PsiElement> {
        val element = resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true).getOrElse {
            return Result.failure(it)
        }
        val explicitLanguage = optionalStringArg(arguments, ParamNames.LANGUAGE)
        val shouldNormalizeJsTsSeed = explicitLanguage == "JavaScript" ||
            explicitLanguage == "TypeScript" ||
            isJsTsElementOrFile(element)
        if (!shouldNormalizeJsTsSeed) {
            return Result.success(element)
        }

        return Result.success(resolveJsTsCallHierarchySeed(element))
    }

    /**
     * Converts handler CallElementData to tool CallElement.
     */
    private fun convertToCallElement(
        project: Project,
        data: CallElementData,
        fallbackTarget: com.intellij.psi.PsiElement? = null,
        preferredSymbolId: String? = null
    ): CallElement {
        return CallElement(
            name = data.name,
            file = data.file,
            line = data.line,
            column = data.column,
            language = data.language,
            symbolId = (data.pointerTarget ?: fallbackTarget)?.let {
                bindSymbolId(project, it, preferredSymbolId)
            },
            children = data.children?.map { convertToCallElement(project, it) }
        )
    }
}
