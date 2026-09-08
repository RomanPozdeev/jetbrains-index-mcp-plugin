package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingTypeExpansion
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.PendingTypeNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.TypeContinuation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.TypeWork
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyTraversalNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
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
 * Tool for retrieving type hierarchies across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class TypeHierarchyTool : AbstractMcpTool() {

    override val supportsUnifiedTarget: Boolean = true

    companion object {
        private val JS_TS_LANGUAGE_FILTER = setOf("JavaScript", "TypeScript")
        private val TYPE_SYMBOL_KINDS = setOf("CLASS", "INTERFACE")
        private const val DEFAULT_MAX_NODES = 100
        private const val MAX_NODES = 500
    }

    override val name = "ide_type_hierarchy"

    override val description = """
        Get the complete inheritance hierarchy for a class or interface. Use when you need to understand class relationships, find parent classes, or discover all subclasses.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Rust note: className parameter not supported for Rust; use file + line + column instead.

        Returns deterministic breadth-first pages containing target class info, supertypes, subtypes, symbolId handles, and pagination metadata. Live progress is not available on stateless HTTP; continue with cursor.

        Parameters for a fresh query: either symbolId, className (e.g., "com.example.MyClass" for Java/PHP-style FQNs or "MyComponent" for JavaScript/TypeScript symbols), OR file + line + column. maxNodes (optional, default: 100, max: 500), scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files). For the next page, pass cursor; other search parameters are ignored.

        Example: {"className": "com.example.UserService", "scope": "project_and_libraries"} or {"file": "src/MyClass.java", "line": 10, "column": 14}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .stringProperty("className", "Fully qualified class name for JVM/PHP-style languages or simple class/interface name for JavaScript/TypeScript (e.g., 'com.example.MyClass', 'App\\\\Models\\\\User', or 'MyComponent').")
        .file(required = false, description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Use with line and column.")
        .intProperty("line", "1-based line number where the class is defined. Required if using file parameter.")
        .intProperty("column", "1-based column number. Required if using file parameter.")
        .intProperty("maxNodes", "Maximum hierarchy nodes returned and expanded in this page. Default: 100, max: 500.")
        .stringProperty("cursor", "Opaque session/project-bound continuation from the previous hierarchy page. Other search parameters are ignored.")
        .scopeProperty("Search scope. Default: project_files.")
        .booleanProperty(ParamNames.INCLUDE_GENERATED, "Include supertypes/subtypes defined in generated sources (KSP/Dagger/annotation-processor output). Default: true — keep generated types in the hierarchy.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAt = System.currentTimeMillis()
        val maxNodes = arguments["maxNodes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_NODES
        if (maxNodes !in 1..MAX_NODES) {
            return createErrorResult("maxNodes must be between 1 and $MAX_NODES")
        }

        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
        val continuationRegistry = HierarchyContinuationRegistry.getInstance()
        if (cursor != null) {
            requireSmartMode(project)
            val lease = continuationRegistry.resolveLease(project, cursor).getOrElse {
                return createErrorResult(it.message ?: "Hierarchy cursor expired")
            }
            val continuation = lease.continuation as? TypeContinuation
                ?: return createErrorResult("Cursor belongs to a different hierarchy tool. Start ide_type_hierarchy again without cursor.")
            return suspendingReadAction {
                buildTypePage(project, continuation, maxNodes, startedAt, lease.generation)
            }
        }

        val generation = continuationRegistry.currentGeneration()

        requireSmartMode(project)

        val className = arguments["className"]?.jsonPrimitive?.content
        val file = arguments["file"]?.jsonPrimitive?.content
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        val excludeGenerated = resolveExcludeGenerated(arguments, default = true)
        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val elementResult = resolveTargetElement(project, arguments, scope)
            val element = elementResult.getOrElse { error ->
                if (optionalStringArg(arguments, ParamNames.SYMBOL_ID) != null) {
                    return@suspendingReadAction createErrorResult(error.message ?: "SYMBOL_ID_EXPIRED")
                }
                val errorMsg = when {
                    className != null -> "Class '$className' not found in project '${project.name}'. Verify the fully qualified name is correct and the class is part of this project."
                    file != null -> "No class found at the specified file/line/column position."
                    else -> "Provide 'symbolId', 'className' (e.g., 'com.example.MyClass'), or 'file' + 'line' + 'column'."
                }
                return@suspendingReadAction createErrorResult(errorMsg)
            }

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No type hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForTypeHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            // Ask the language adapter for exactly one edge. The tool owns the bounded BFS and
            // never lets a handler recursively materialize the complete graph.
            val hierarchyData = handler.getTypeHierarchy(
                element,
                project,
                scope,
                excludeGenerated,
                directOnly = true,
                direction = TypeHierarchyDirection.SUPERTYPE,
                page = HierarchyPageRequest(offset = 0, limit = maxNodes + 1)
            )
            if (hierarchyData == null) {
                return@suspendingReadAction createErrorResult("No class/type found at the specified position.")
            }

            val pointerManager = SmartPointerManager.getInstance(project)
            val modificationCount = PsiModificationTracker.getInstance(project).modificationCount
            val root = convertToTypeElement(
                project,
                hierarchyData.element,
                element,
                optionalStringArg(arguments, ParamNames.SYMBOL_ID)
            ).copy(supertypes = null)
            // Position lookup may start on a reference leaf. The handler has already resolved
            // that leaf to the semantic class declaration; bind the continuation to that exact
            // declaration so deleting/moving the referencing file does not invalidate the root.
            val rootTarget = PsiUtils.resolveNavigationTarget(
                hierarchyData.element.pointerTarget ?: element
            )
            val visited = linkedSetOf(hierarchyKey(rootTarget, root, hierarchyData.element.qualifiedName))
            val rootPointer = pointerManager.createSmartPsiElementPointer(rootTarget)
            val visitedPointers = mutableListOf<SmartPsiElementPointer<PsiElement>>(rootPointer)
            val frontier = buildList<TypeWork> {
                hierarchyData.supertypes
                    .sortedWith(typeDataComparator)
                    .mapNotNullTo(this) { data ->
                        pendingTypeNode(
                            project, pointerManager, data, TypeHierarchyDirection.SUPERTYPE, visited, visitedPointers
                        )
                    }
                hierarchyData.nextOffset?.let { offset ->
                    add(PendingTypeExpansion(rootPointer, TypeHierarchyDirection.SUPERTYPE, offset))
                }
                // Subtypes are loaded lazily. This keeps initial work bounded even when both
                // sides of the hierarchy are very wide.
                add(PendingTypeExpansion(rootPointer, TypeHierarchyDirection.SUBTYPE, 0))
            }
            val continuation = TypeContinuation(
                rootPointer = rootPointer,
                root = root,
                frontier = frontier,
                visited = visited,
                visitedPointers = visitedPointers,
                scope = scope,
                excludeGenerated = excludeGenerated,
                rootModificationCount = modificationCount
            )
            buildTypePage(project, continuation, maxNodes, startedAt, generation)
        }
    }

    private fun buildTypePage(
        project: Project,
        continuation: TypeContinuation,
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
        val returnedSupertypes = mutableListOf<TypeElement>()
        val returnedSubtypes = mutableListOf<TypeElement>()
        val traversal = mutableListOf<TypeHierarchyTraversalNode>()
        var discoveredThisPage = 0
        var expandedThisPage = 0
        val modificationCount = PsiModificationTracker.getInstance(project).modificationCount

        val root = if (continuation.rootModificationCount == modificationCount) continuation.root else {
            refreshTypeSnapshot(
                project,
                rootElement,
                continuation.root,
                TypeHierarchyDirection.SUPERTYPE,
                continuation.scope,
                continuation.excludeGenerated
            )
        }

        fun returnedCount(): Int = returnedSupertypes.size + returnedSubtypes.size

        fun hasKnownPendingNode(): Boolean = queue.any { work ->
            work is PendingTypeNode && (work.pointer == null || work.pointer.element != null)
        }

        // A cursor is only useful when it already contains a node which can be returned. When a
        // page ends at a leaf, consume its pending edge probes here instead of handing clients a
        // cursor whose next page would be empty. Each probe remains a bounded handler request and
        // we stop as soon as one live node is discovered.
        while (queue.isNotEmpty() && (returnedCount() < maxNodes || !hasKnownPendingNode())) {
            ProgressManager.checkCanceled()
            when (val work = queue.removeFirst()) {
                is PendingTypeNode -> {
                    val target = work.pointer?.element
                    if (work.pointer != null && target == null) continue
                    val snapshot = if (target == null || work.modificationCount == modificationCount) {
                        work.snapshot.copy(supertypes = null)
                    } else {
                        refreshTypeSnapshot(
                            project,
                            target,
                            work.snapshot,
                            work.direction,
                            continuation.scope,
                            continuation.excludeGenerated
                        )
                    }
                    when (work.direction) {
                        TypeHierarchyDirection.SUPERTYPE -> returnedSupertypes.add(snapshot)
                        TypeHierarchyDirection.SUBTYPE -> returnedSubtypes.add(snapshot)
                    }
                    traversal += TypeHierarchyTraversalNode(
                        direction = work.direction.name.lowercase(),
                        element = snapshot
                    )
                    if (target != null) {
                        queue.addLast(
                            PendingTypeExpansion(
                                pointerManager.createSmartPsiElementPointer(PsiUtils.resolveNavigationTarget(target)),
                                work.direction,
                                0
                            )
                        )
                    }
                }

                is PendingTypeExpansion -> {
                    val lookingAhead = !hasKnownPendingNode()
                    if ((discoveredThisPage >= maxNodes || expandedThisPage >= maxNodes) && !lookingAhead) {
                        queue.addFirst(work)
                        break
                    }
                    expandedThisPage++
                    val target = work.pointer.element ?: continue
                    val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(target) ?: continue
                    val scanLimit = (
                        work.offset.toLong() + (maxNodes - discoveredThisPage).coerceAtLeast(0).toLong() + 1L
                    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val direct = handler.getTypeHierarchy(
                        target,
                        project,
                        continuation.scope,
                        continuation.excludeGenerated,
                        directOnly = true,
                        direction = work.direction,
                        // Re-read an ever larger prefix and filter it by smart-pointer identity.
                        // A raw offset is not stable when a declaration is renamed or moved
                        // between pages: an item can cross the offset and be lost forever.
                        page = HierarchyPageRequest(offset = 0, limit = scanLimit)
                    ) ?: continue
                    val children = when (work.direction) {
                        TypeHierarchyDirection.SUPERTYPE -> direct.supertypes
                        TypeHierarchyDirection.SUBTYPE -> direct.subtypes
                    }
                    // Normalize only the bounded prefix returned by the handler. A global lexical
                    // sibling sort would require exhausting a potentially unbounded index query;
                    // cross-page order instead follows stable bounded discovery windows.
                    val pendingChildren = children.sortedWith(typeDataComparator).mapNotNull { child ->
                        pendingTypeNode(
                            project, pointerManager, child, work.direction, visited, visitedPointers
                        )
                    }
                    discoveredThisPage += maxOf(
                        (children.size - work.offset).coerceAtLeast(0),
                        pendingChildren.size
                    )
                    val additions = buildList<TypeWork> {
                        addAll(pendingChildren)
                        direct.nextOffset?.let { nextOffset ->
                            // A non-advancing continuation would create an endless cursor chain.
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
            work is PendingTypeNode && (work.pointer == null || work.pointer.element != null)
        }
        val nextCursor = if (hasMore) {
            registry.register(project, generation, nextState).getOrElse {
                return createErrorResult(it.message ?: "The MCP server session changed")
            }
        } else null
        val returnedNodes = returnedCount()

        return createJsonResult(
            TypeHierarchyResult(
                element = root,
                supertypes = returnedSupertypes,
                subtypes = returnedSubtypes,
                traversal = traversal,
                returnedNodes = returnedNodes,
                truncated = hasMore,
                elapsedMs = System.currentTimeMillis() - startedAt,
                hasMore = hasMore,
                cursor = nextCursor
            )
        )
    }

    private fun pendingTypeNode(
        project: Project,
        pointerManager: SmartPointerManager,
        data: TypeElementData,
        direction: TypeHierarchyDirection,
        visited: MutableSet<String>,
        visitedPointers: MutableList<SmartPsiElementPointer<PsiElement>>
    ): PendingTypeNode? {
        val snapshot = convertToTypeElement(project, data).copy(supertypes = null)
        val target = data.pointerTarget?.let(PsiUtils::resolveNavigationTarget)
        val key = hierarchyKey(target, snapshot, data.qualifiedName)
        val pointer = if (target == null) {
            if (!visited.add(key)) return null
            null
        } else {
            val psiManager = PsiManager.getInstance(project)
            if (visitedPointers.any { existing ->
                    existing.element?.let { it === target || psiManager.areElementsEquivalent(it, target) } == true
                }
            ) return null
            // The string key remains available after a pointer is invalidated or falls out of
            // the bounded pointer history, so structural edits cannot re-discover old nodes.
            if (!visited.add(key)) return null
            pointerManager.createSmartPsiElementPointer(target).also { pointer ->
                if (visitedPointers.size < HierarchyContinuationRegistry.MAX_VISITED_POINTERS_PER_CONTINUATION) {
                    visitedPointers += pointer
                }
            }
        }
        return PendingTypeNode(
            pointer = pointer,
            snapshot = snapshot,
            direction = direction,
            modificationCount = PsiModificationTracker.getInstance(project).modificationCount
        )
    }

    private fun refreshTypeSnapshot(
        project: Project,
        target: PsiElement,
        previous: TypeElement,
        direction: TypeHierarchyDirection,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): TypeElement {
        val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(target) ?: return previous
        val fresh = handler.getTypeHierarchy(
            target,
            project,
            scope,
            excludeGenerated,
            directOnly = true,
            direction = direction,
            page = HierarchyPageRequest(offset = 0, limit = 0)
        )?.element ?: return previous
        return convertToTypeElement(project, fresh, target, previous.symbolId).copy(supertypes = null)
    }

    private fun hierarchyKey(element: PsiElement?, snapshot: TypeElement, qualifiedName: String?): String {
        if (element != null) {
            val target = PsiUtils.resolveNavigationTarget(element)
            val file = target.containingFile?.virtualFile?.url.orEmpty()
            val resolvedQualifiedName = PsiUtils.qualifiedName(target).orEmpty().ifBlank { qualifiedName.orEmpty() }
            if (resolvedQualifiedName.isNotBlank()) return "${target.javaClass.name}|$file|$resolvedQualifiedName"
        }
        return "${snapshot.language}|${snapshot.file}|${qualifiedName.orEmpty()}|${snapshot.kind}|${snapshot.name}"
    }

    private val typeDataComparator = compareBy<TypeElementData>(
        { it.language },
        { it.file.orEmpty() },
        { it.line ?: Int.MAX_VALUE },
        { it.qualifiedName.orEmpty() },
        { it.name }
    )

    private fun resolveTargetElement(project: Project, arguments: JsonObject, scope: BuiltInSearchScope): Result<PsiElement> {
        val symbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)
        val hasQualifiedTarget = optionalStringArg(arguments, ParamNames.LANGUAGE) != null ||
            optionalStringArg(arguments, ParamNames.SYMBOL) != null
        if (symbolId != null || hasQualifiedTarget) {
            if (optionalStringArg(arguments, ParamNames.CLASS_NAME) != null) {
                return Result.failure(IllegalArgumentException(ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE))
            }
            return resolveElementFromArguments(project, arguments, allowLibraryFilesForPosition = true)
        }

        // Try className first. Direct class lookup covers JVM/PHP-style FQNs; symbol search
        // fills the same entry point for WebStorm JS/TS class and interface names.
        val className = arguments["className"]?.jsonPrimitive?.content
        if (className != null) {
            val target = findClassByName(project, className)
                ?: findJavaScriptOrTypeScriptClassByName(project, className, scope)
                ?: return Result.failure(IllegalArgumentException("Class not found"))
            return Result.success(target)
        }

        // Otherwise use file/line/column (works for all languages)
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return Result.failure(IllegalArgumentException("Missing position"))
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return Result.failure(IllegalArgumentException("Missing position"))
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return Result.failure(IllegalArgumentException("Missing position"))

        return findPsiElement(project, file, line, column)
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("No element at position"))
    }

    private fun findJavaScriptOrTypeScriptClassByName(
        project: Project,
        className: String,
        scope: BuiltInSearchScope
    ): PsiElement? {
        val simpleName = className.substringAfterLast('.').substringAfterLast('#')
        if (simpleName.isBlank()) return null
        val isQualifiedRequest = className.contains('.') || className.contains('#')

        val searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, scope)
        val symbols = OptimizedSymbolSearch.search(
            project = project,
            pattern = simpleName,
            scope = searchScope,
            limit = 50,
            languageFilter = JS_TS_LANGUAGE_FILTER
        )

        val match = symbols
            .filter { it.name == simpleName && it.kind in TYPE_SYMBOL_KINDS }
            .firstOrNull { symbol ->
                val qualifiedName = symbol.qualifiedName
                (!isQualifiedRequest && symbol.qualifiedName == null) ||
                    qualifiedName == className ||
                    qualifiedName?.endsWith(".$simpleName") == true
            }
            ?: symbols.firstOrNull {
                !isQualifiedRequest && it.name == simpleName && it.kind in TYPE_SYMBOL_KINDS
            }

        return match?.let { findPsiElement(project, it.file, it.line, it.column) }
    }

    /**
     * Converts handler TypeElementData to tool TypeElement.
     */
    private fun convertToTypeElement(
        project: Project,
        data: TypeElementData,
        fallbackTarget: PsiElement? = null,
        preferredSymbolId: String? = null
    ): TypeElement {
        return TypeElement(
            name = data.name,
            file = data.file,
            kind = data.kind,
            language = data.language,
            symbolId = (data.pointerTarget ?: fallbackTarget)?.let {
                bindSymbolId(project, it, preferredSymbolId)
            },
            supertypes = data.supertypes?.map { convertToTypeElement(project, it) }
        )
    }
}
