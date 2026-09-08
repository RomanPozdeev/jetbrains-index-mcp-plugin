package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.TypeContinuation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager

class HierarchyContinuationRegistryBehaviorTest : McpPlatformTestCase() {

    fun testDefaultsUseIndependentTenMinuteTtlAndBoundedLru() {
        assertEquals(128, HierarchyContinuationRegistry.DEFAULT_MAX_ENTRIES)
        assertEquals(10 * 60 * 1_000L, HierarchyContinuationRegistry.DEFAULT_TTL_MILLIS)
    }

    fun testAccessOrderLruEvictsTheLeastRecentlyUsedCursor() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            maxEntries = 2,
            ttlMillis = 10_000,
            clock = { 1_000L },
            idGenerator = { "cursor-${++generated}" }
        )
        val continuation = fixtureContinuation()

        val first = registry.register(project, continuation)
        val second = registry.register(project, continuation)
        assertTrue(registry.resolve(project, first).isSuccess)
        val third = registry.register(project, continuation)

        assertTrue("second cursor should be evicted as LRU", registry.resolve(project, second).isFailure)
        assertTrue(registry.resolve(project, first).isSuccess)
        assertTrue(registry.resolve(project, third).isSuccess)
        assertEquals(2, registry.sizeForTest())
        registry.dispose()
    }

    fun testTtlRefreshesOnAccessAndExpiresAtBoundary() {
        var now = 1_000L
        val registry = HierarchyContinuationRegistry(
            maxEntries = 2,
            ttlMillis = 100,
            clock = { now },
            idGenerator = { "ttl-cursor" }
        )
        val cursor = registry.register(project, fixtureContinuation())

        now = 1_099L
        assertTrue(registry.resolve(project, cursor).isSuccess)
        now = 1_198L
        assertTrue(registry.resolve(project, cursor).isSuccess)
        now = 1_298L
        assertTrue("cursor must expire at the exact TTL boundary", registry.resolve(project, cursor).isFailure)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testCursorIsBoundToExactProjectInstanceWithoutDestroyingOwnerState() {
        val registry = HierarchyContinuationRegistry(idGenerator = { "project-cursor" })
        val cursor = registry.register(project, fixtureContinuation())
        val otherProject = ProjectManager.getInstance().defaultProject

        assertNotSame(project, otherProject)
        assertTrue(registry.resolve(otherProject, cursor).isFailure)
        assertTrue("wrong-project lookup must not consume the owner's cursor", registry.resolve(project, cursor).isSuccess)
        registry.dispose()
    }

    fun testWrongProjectLookupDoesNotPromoteForeignCursorInAccessOrder() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            maxEntries = 2,
            idGenerator = { "foreign-lru-${++generated}" }
        )
        val first = registry.register(project, fixtureContinuation())
        val second = registry.register(project, fixtureContinuation())
        val otherProject = ProjectManager.getInstance().defaultProject

        assertTrue(registry.resolve(otherProject, first).isFailure)
        val third = registry.register(project, fixtureContinuation())

        assertTrue("foreign lookup must not save the oldest cursor from eviction", registry.resolve(project, first).isFailure)
        assertTrue(registry.resolve(project, second).isSuccess)
        assertTrue(registry.resolve(project, third).isSuccess)
        registry.dispose()
    }

    fun testResetRejectsRegisterFromPreviousGeneration() {
        val registry = HierarchyContinuationRegistry(idGenerator = { "stale-register" })
        val generation = registry.currentGeneration()

        registry.resetSession()
        val result = registry.register(project, generation, fixtureContinuation())

        assertTrue("an in-flight old-session query must not resurrect a cursor", result.isFailure)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testResolvedLeaseCannotRegisterAfterSessionReset() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(idGenerator = { "lease-${++generated}" })
        val cursor = registry.register(project, fixtureContinuation())
        val lease = registry.resolveLease(project, cursor).getOrThrow()

        registry.resetSession()
        val result = registry.register(project, lease.generation, lease.continuation)

        assertTrue(result.isFailure)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testResetSessionInvalidatesAllContinuations() {
        val registry = HierarchyContinuationRegistry(idGenerator = { "reset-cursor" })
        val cursor = registry.register(project, fixtureContinuation())

        registry.resetSession()

        assertTrue(registry.resolve(project, cursor).isFailure)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testMcpServerStopInvalidatesApplicationContinuationRegistry() {
        val registry = HierarchyContinuationRegistry.getInstance()
        registry.resetSession()
        val cursor = registry.register(project, fixtureContinuation())

        McpServerService.getInstance().stopServer()

        assertTrue("server generation boundary must expire hierarchy cursors", registry.resolve(project, cursor).isFailure)
    }

    fun testRegistryBoundsHistoricalSmartPointerIdentity() {
        val registry = HierarchyContinuationRegistry(idGenerator = { "bounded-pointers" })
        val fixture = fixtureContinuation()
        val continuation = fixture.copy(
            visitedPointers = List(HierarchyContinuationRegistry.MAX_VISITED_POINTERS_PER_CONTINUATION + 1) {
                fixture.rootPointer
            }
        )

        val cursor = registry.register(project, continuation)
        val restored = registry.resolve(project, cursor).getOrThrow() as TypeContinuation

        assertEquals(
            HierarchyContinuationRegistry.MAX_VISITED_POINTERS_PER_CONTINUATION,
            restored.visitedPointers.size
        )
        registry.dispose()
    }

    fun testRegistryRejectsContinuationWhoseFrontierExceedsSmartPointerCap() {
        val registry = HierarchyContinuationRegistry(idGenerator = { "frontier-overflow" })
        val fixture = fixtureContinuation()
        val continuation = fixture.copy(
            frontier = List(HierarchyContinuationRegistry.MAX_POINTERS_PER_CONTINUATION) {
                HierarchyContinuationRegistry.PendingTypeExpansion(
                    pointer = fixture.rootPointer,
                    direction = com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection.SUBTYPE,
                    offset = it
                )
            }
        )

        assertTrue(registry.register(project, registry.currentGeneration(), continuation).isFailure)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testRegistryRejectsTypeContinuationWhoseVisitedKeyCountExceedsBudget() {
        val registry = HierarchyContinuationRegistry(idGenerator = { "visited-key-count-overflow" })
        val fixture = fixtureContinuation()
        val continuation = fixture.copy(
            visited = (0..HierarchyContinuationRegistry.MAX_VISITED_KEYS_PER_CONTINUATION)
                .map { "type-key-$it" }
                .toSet()
        )

        val result = registry.register(project, registry.currentGeneration(), continuation)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("stable visited-key budget"))
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testRegistryRejectsCallContinuationWhoseVisitedKeyCharactersExceedBudget() {
        val registry = HierarchyContinuationRegistry(idGenerator = { "visited-key-character-overflow" })
        val continuation = fixtureCallContinuation().copy(
            visited = setOf("x".repeat(HierarchyContinuationRegistry.MAX_VISITED_KEY_CHARS_PER_CONTINUATION + 1))
        )

        val result = registry.register(project, registry.currentGeneration(), continuation)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("stable visited-key budget"))
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    private fun fixtureContinuation(): TypeContinuation {
        writeProjectFile("hierarchy-registry-src/RegistryRoot.java", "class RegistryRoot {}")
        val basePath = requireNotNull(project.basePath)
        return ReadAction.compute<TypeContinuation, Throwable> {
            val virtualFile = requireNotNull(
                LocalFileSystem.getInstance().findFileByPath("$basePath/hierarchy-registry-src/RegistryRoot.java")
            )
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) as PsiJavaFile
            val root = psiFile.classes.single()
            TypeContinuation(
                rootPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(root),
                root = TypeElement(
                    name = "RegistryRoot",
                    file = "hierarchy-registry-src/RegistryRoot.java",
                    kind = "CLASS",
                    language = "Java"
                ),
                frontier = emptyList(),
                visited = setOf("RegistryRoot"),
                scope = BuiltInSearchScope.PROJECT_FILES,
                excludeGenerated = false
            )
        }
    }

    private fun fixtureCallContinuation(): HierarchyContinuationRegistry.CallContinuation {
        val typeFixture = fixtureContinuation()
        return HierarchyContinuationRegistry.CallContinuation(
            rootPointer = typeFixture.rootPointer,
            root = CallElement(
                name = "RegistryRoot.call()",
                file = "hierarchy-registry-src/RegistryRoot.java",
                line = 1,
                column = 1,
                language = "Java"
            ),
            frontier = emptyList(),
            visited = setOf("RegistryRoot.call()"),
            direction = "callees",
            maxDepth = 1,
            scope = BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false
        )
    }
}
