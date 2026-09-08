package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

class HierarchyIdentityAndHandlesBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    private var now = 100L

    override fun setUp() {
        super.setUp()
        Assume.assumeTrue("Java plugin required for PSI fixtures", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        LanguageHandlerRegistry.registerHandlers()
        HierarchyContinuationRegistry.getInstance().resetSession()
        SymbolIdRegistry.getInstance().resetSession()
    }

    override fun tearDown() {
        try {
            HierarchyContinuationRegistry.getInstance().resetSession()
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testCallPageRebindsHandlesAfterLruEvictionWithoutPsiChanges() = runBlocking {
        checkCallHandleRetention(expireByTtl = false)
    }

    fun testCallPageRebindsHandlesAfterIdleTtlWithoutPsiChanges() = runBlocking {
        checkCallHandleRetention(expireByTtl = true)
    }

    fun testTypePageRebindsHandlesAfterLruEvictionWithoutPsiChanges() = runBlocking {
        checkTypeHandleRetention(expireByTtl = false)
    }

    fun testTypePageRebindsHandlesAfterIdleTtlWithoutPsiChanges() = runBlocking {
        checkTypeHandleRetention(expireByTtl = true)
    }

    private suspend fun checkCallHandleRetention(expireByTtl: Boolean) {
        val registry = installSmallSymbolRegistry()
        writeProjectFile("src/handles/CallHandles.java", """
            package handles;
            class CallHandles {
                void alpha() {}
                void beta() {}
                void gamma() {}
                void root() { alpha(); beta(); gamma(); }
            }
        """.trimIndent())
        val owner = findClass("handles.CallHandles")
        val root = owner.findMethodsByName("root", false).single()
        val tool = CallHierarchyTool()
        val firstResult = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "handles.CallHandles#root()")
            put("direction", "callees")
            put("depth", 1)
            put("maxNodes", 1)
        })
        assertToolSucceeded("first call page", firstResult)
        val first = json.decodeFromString<CallHierarchyResult>(toolText(firstResult))
        assertEquals("look-ahead nodes must not allocate handles", 2, registry.sizeForTest())
        val cursor = requireNotNull(first.cursor)

        suspend fun page(): CallHierarchyResult {
            val result = tool.execute(project, buildJsonObject {
                put("cursor", cursor)
                put("maxNodes", 1)
            })
            assertToolSucceeded("continued call page", result)
            return json.decodeFromString(toolText(result))
        }

        val before = page()
        val oldRootId = requireNotNull(before.element.symbolId)
        val oldNodeId = requireNotNull(before.calls.single().symbolId)
        val modifications = PsiModificationTracker.getInstance(project).modificationCount
        evictHandles(registry, root, expireByTtl)
        assertTrue("root handle should actually be expired", registry.resolve(project, oldRootId).isFailure)
        assertTrue("page handle should actually be expired", registry.resolve(project, oldNodeId).isFailure)
        assertEquals(modifications, PsiModificationTracker.getInstance(project).modificationCount)

        val after = page()
        val rootId = requireNotNull(after.element.symbolId)
        val nodeId = requireNotNull(after.calls.single().symbolId)
        assertFalse(oldRootId == rootId)
        assertFalse(oldNodeId == nodeId)
        assertSame(root, registry.resolve(project, rootId).getOrThrow())
        assertSame(owner.findMethodsByName("beta", false).single(), registry.resolve(project, nodeId).getOrThrow())
        assertEquals(before.calls.map { it.name }, after.calls.map { it.name })
        assertEquals("retries must reuse their continuation snapshot", before.cursor, after.cursor)
        val retry = page()
        assertEquals(rootId, retry.element.symbolId)
        assertEquals(nodeId, retry.calls.single().symbolId)
    }

    private suspend fun checkTypeHandleRetention(expireByTtl: Boolean) {
        val registry = installSmallSymbolRegistry()
        writeProjectFile("src/handles/TypeHandles.java", """
            package handles;
            class TypeRoot {}
            class Alpha extends TypeRoot {}
            class Beta extends TypeRoot {}
            class Gamma extends TypeRoot {}
        """.trimIndent())
        val root = findClass("handles.TypeRoot")
        val tool = TypeHierarchyTool()
        val firstResult = tool.execute(project, buildJsonObject {
            put("className", "handles.TypeRoot")
            put("maxNodes", 1)
        })
        assertToolSucceeded("first type page", firstResult)
        val first = json.decodeFromString<TypeHierarchyResult>(toolText(firstResult))
        assertEquals("look-ahead types must not allocate handles", 2, registry.sizeForTest())
        val cursor = requireNotNull(first.cursor)

        suspend fun page(): TypeHierarchyResult {
            val result = tool.execute(project, buildJsonObject {
                put("cursor", cursor)
                put("maxNodes", 1)
            })
            assertToolSucceeded("continued type page", result)
            return json.decodeFromString(toolText(result))
        }

        val before = page()
        val oldRootId = requireNotNull(before.element.symbolId)
        val oldNodeId = requireNotNull(before.traversal.single().element.symbolId)
        val modifications = PsiModificationTracker.getInstance(project).modificationCount
        evictHandles(registry, root, expireByTtl)
        assertTrue(registry.resolve(project, oldRootId).isFailure)
        assertTrue(registry.resolve(project, oldNodeId).isFailure)
        assertEquals(modifications, PsiModificationTracker.getInstance(project).modificationCount)

        val after = page()
        val rootId = requireNotNull(after.element.symbolId)
        val nodeId = requireNotNull(after.traversal.single().element.symbolId)
        assertFalse(oldRootId == rootId)
        assertFalse(oldNodeId == nodeId)
        assertSame(root, registry.resolve(project, rootId).getOrThrow())
        assertSame(findClass(before.traversal.single().element.name), registry.resolve(project, nodeId).getOrThrow())
        assertEquals(before.traversal.map { it.element.name }, after.traversal.map { it.element.name })
        assertEquals(before.cursor, after.cursor)
        val retry = page()
        assertEquals(rootId, retry.element.symbolId)
        assertEquals(nodeId, retry.traversal.single().element.symbolId)
    }

    fun testAnonymousCallersOnTheSameLineRemainDistinctAcrossPagesAndEdits() = runBlocking {
        val path = writeProjectFile("src/identity/AnonymousCalls.java", """
            package identity;
            interface Action { void run(); }
            class AnonymousCalls {
                void target() {}
                Action first = new Action() { public void run() { target(); } }; Action second = new Action() { public void run() { target(); } };
            }
        """.trimIndent())
        val owner = findClass("identity.AnonymousCalls")
        val callers = PsiTreeUtil.findChildrenOfType(owner, PsiMethod::class.java)
            .filter { it.name == "run" }.sortedBy { it.textOffset }
        assertEquals(2, callers.size)
        assertEquals("both owners intentionally lack a qualified name", listOf(null, null), callers.map { it.containingClass?.qualifiedName })

        val tool = CallHierarchyTool()
        val firstResult = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "identity.AnonymousCalls#target()")
            put("direction", "callers")
            put("depth", 1)
            put("maxNodes", 1)
        })
        assertToolSucceeded("first anonymous caller page", firstResult)
        val first = json.decodeFromString<CallHierarchyResult>(toolText(firstResult))
        assertEquals(1, first.calls.size)
        val cursor = requireNotNull(first.cursor)
        val firstId = requireNotNull(first.calls.single().symbolId)

        val file = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path.toString()))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "\n\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val nextResult = tool.execute(project, buildJsonObject { put("cursor", cursor); put("maxNodes", 1) })
        assertToolSucceeded("anonymous caller cursor after a line shift", nextResult)
        val second = json.decodeFromString<CallHierarchyResult>(toolText(nextResult))
        assertEquals(1, second.calls.size)
        val firstPointer = SymbolIdRegistry.getInstance().resolve(project, firstId).getOrThrow()
        val secondPointer = SymbolIdRegistry.getInstance().resolve(project, requireNotNull(second.calls.single().symbolId)).getOrThrow()
        assertFalse("distinct anonymous declarations must not collapse", sameHierarchyDeclaration(firstPointer, secondPointer))
        assertFalse(second.hasMore)
    }

    fun testSameNamedLocalTypesInDifferentMethodsRemainDistinct() = runBlocking {
        writeProjectFile("src/identity/LocalTypes.java", """
            package identity;
            class Base {}
            class LocalTypes {
                void first() { class Local extends Base {} }
                void second() { class Local extends Base {} }
            }
        """.trimIndent())
        val localTypes = PsiTreeUtil.findChildrenOfType(findClass("identity.LocalTypes"), PsiClass::class.java)
            .filter { it.name == "Local" }
        assertEquals(2, localTypes.size)
        val resolved = mutableListOf<PsiElement>()
        var cursor: String? = null
        var remaining = 10
        do {
            assertTrue("local type cursor must terminate", remaining-- > 0)
            val result = TypeHierarchyTool().execute(project, buildJsonObject {
                put("maxNodes", 1)
                if (cursor == null) put("className", "identity.Base") else put("cursor", cursor!!)
            })
            assertToolSucceeded("local type hierarchy page", result)
            val page = json.decodeFromString<TypeHierarchyResult>(toolText(result))
            resolved += page.traversal.map {
                SymbolIdRegistry.getInstance().resolve(project, requireNotNull(it.element.symbolId)).getOrThrow()
            }
            cursor = page.cursor
        } while (cursor != null)
        assertEquals("both local declarations must be emitted once", 2, resolved.size)
        assertTrue(localTypes.all { expected -> resolved.count { sameHierarchyDeclaration(it, expected) } == 1 })
    }

    fun testExpansionFrontierReplayRetainsMembersAndSuccessorAfterEviction() = runBlocking {
        writeProjectFile("src/replay/ExpansionCalls.java", """
            package replay;
            class ExpansionCalls {
                void leafOne() {}
                void leafTwo() {}
                void alpha() {}
                void beta() {}
                void gamma() { leafOne(); leafTwo(); }
                void root() { alpha(); beta(); gamma(); }
            }
        """.trimIndent())
        val tool = CallHierarchyTool()
        val initialResult = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "replay.ExpansionCalls#root()")
            put("direction", "callees")
            put("depth", 2)
            put("maxNodes", 1)
        })
        assertToolSucceeded("initial expansion hierarchy", initialResult)
        val initial = json.decodeFromString<CallHierarchyResult>(toolText(initialResult))
        var parent = requireNotNull(initial.cursor)
        val prefix = initial.calls.map { it.name }.toMutableList()
        var pagesUntilExpansion = 10
        while (true) {
            val continuation = HierarchyContinuationRegistry.getInstance().resolve(project, parent).getOrThrow()
                as HierarchyContinuationRegistry.CallContinuation
            if (continuation.frontier.all { it is HierarchyContinuationRegistry.PendingCallExpansion }) break
            assertTrue("fixture must reach the budget-limited expansion frontier", pagesUntilExpansion-- > 0)
            val result = tool.execute(project, buildJsonObject { put("cursor", parent); put("maxNodes", 1) })
            assertToolSucceeded("advance to expansion frontier", result)
            val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
            prefix += page.calls.map { it.name }
            parent = requireNotNull(page.cursor)
        }
        assertEquals(listOf("ExpansionCalls.alpha()", "ExpansionCalls.beta()", "ExpansionCalls.gamma()"), prefix)
        suspend fun replay(): CallHierarchyResult {
            val result = tool.execute(project, buildJsonObject { put("cursor", parent); put("maxNodes", 1) })
            assertToolSucceeded("replayed expansion page", result)
            return json.decodeFromString(toolText(result))
        }
        val first = replay()
        val registry = SymbolIdRegistry.getInstance()
        listOfNotNull(first.element.symbolId, first.calls.single().symbolId).forEach(registry::invalidate)
        val second = replay()
        assertEquals(first.calls.map { it.name }, second.calls.map { it.name })
        assertEquals(first.cursor, second.cursor)
        assertNotNull(registry.resolve(project, requireNotNull(second.calls.single().symbolId)).getOrThrow())
        val allNames = prefix.toMutableList().apply { addAll(second.calls.map { it.name }) }
        var cursor = second.cursor
        var remaining = 10
        while (cursor != null) {
            assertTrue("continuation after replay must terminate", remaining-- > 0)
            val result = tool.execute(project, buildJsonObject { put("cursor", cursor!!); put("maxNodes", 1) })
            assertToolSucceeded("continuation after expansion replay", result)
            val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
            allNames += page.calls.map { it.name }
            cursor = page.cursor
        }
        assertEquals(listOf("ExpansionCalls.alpha()", "ExpansionCalls.beta()", "ExpansionCalls.gamma()", "ExpansionCalls.leafOne()", "ExpansionCalls.leafTwo()"), allNames)
    }

    private fun installSmallSymbolRegistry(): SymbolIdRegistry {
        val registry = SymbolIdRegistry(maxEntries = 4, ttlMillis = 1_000L, clock = { now })
        ApplicationManager.getApplication().replaceService(SymbolIdRegistry::class.java, registry, testRootDisposable)
        Disposer.register(testRootDisposable, registry)
        return registry
    }

    private fun evictHandles(registry: SymbolIdRegistry, target: PsiElement, expireByTtl: Boolean) {
        if (expireByTtl) now += 1_000L else repeat(4) { registry.bind(project, target) }
    }

    private fun findClass(qualifiedName: String): PsiClass = requireNotNull(
        JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.projectScope(project))
    )
}
