package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SymbolIdRegistryBehaviorTest : McpPlatformTestCase() {

    fun testDefaultsAreOneHourAnd4096Entries() {
        assertEquals(4_096, SymbolIdRegistry.DEFAULT_MAX_ENTRIES)
        assertEquals(60 * 60 * 1_000L, SymbolIdRegistry.DEFAULT_TTL_MILLIS)
    }

    fun testAccessOrderLruEvictsLeastRecentlyUsedHandle() {
        val elements = fixtureElements()
        var generated = 0
        val registry = SymbolIdRegistry(
            maxEntries = 2,
            ttlMillis = 10_000,
            clock = { 1_000L },
            idGenerator = { "test-${++generated}" }
        )

        val first = bind(registry, elements[0])
        val second = bind(registry, elements[1])
        assertResolved(registry, first)
        val third = bind(registry, elements[2])

        assertExpired(registry, second)
        assertResolved(registry, first)
        assertResolved(registry, third)
        assertEquals(2, registry.sizeForTest())
        registry.dispose()
    }

    fun testSamePsiSymbolMayHaveMultipleUnequalSimultaneouslyValidHandles() {
        val element = fixtureElements().first()
        var generated = 0
        val registry = SymbolIdRegistry(idGenerator = { "same-symbol-${++generated}" })

        val first = bind(registry, element)
        val second = bind(registry, element)

        assertFalse("symbolId is a handle, not a canonical symbol identity", first == second)
        assertResolved(registry, first)
        assertResolved(registry, second)
        registry.dispose()
    }

    fun testTtlIsBasedOnLastAccessAndExpiresAtBoundary() {
        val element = fixtureElements().first()
        var now = 1_000L
        val registry = SymbolIdRegistry(
            maxEntries = 2,
            ttlMillis = 100,
            clock = { now },
            idGenerator = { "ttl-id" }
        )

        val id = bind(registry, element)
        now = 1_099L
        assertResolved(registry, id)
        now = 1_198L
        assertResolved(registry, id)
        now = 1_298L
        assertExpired(registry, id)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testWrongProjectInstanceDoesNotResolveOrDestroyOwnerHandle() {
        val element = fixtureElements().first()
        val registry = SymbolIdRegistry(idGenerator = { "project-id" })
        val id = bind(registry, element)
        val otherProject = ProjectManager.getInstance().defaultProject

        assertNotSame(project, otherProject)
        val wrongProjectResult = ReadAction.compute<Result<PsiElement>, Throwable> {
            registry.resolve(otherProject, id)
        }
        assertTrue(wrongProjectResult.isFailure)
        assertTrue(wrongProjectResult.exceptionOrNull()?.message.orEmpty().contains("SYMBOL_ID_EXPIRED"))

        assertResolved(registry, id)
        registry.dispose()
    }

    fun testWrongProjectLookupDoesNotPromoteForeignHandleInAccessOrder() {
        val elements = fixtureElements()
        var generated = 0
        val registry = SymbolIdRegistry(maxEntries = 2, idGenerator = { "foreign-lru-${++generated}" })
        val first = bind(registry, elements[0])
        val second = bind(registry, elements[1])

        ReadAction.compute<Result<PsiElement>, Throwable> {
            registry.resolve(ProjectManager.getInstance().defaultProject, first)
        }
        val third = bind(registry, elements[2])

        assertExpired(registry, first)
        assertResolved(registry, second)
        assertResolved(registry, third)
        registry.dispose()
    }

    fun testResetRejectsBindCapturedByPreviousGeneration() {
        val element = fixtureElements().first()
        val registry = SymbolIdRegistry(idGenerator = { "stale-bind" })
        val generation = registry.currentGeneration()

        registry.resetSession()
        val result = ReadAction.compute<Result<String>, Throwable> {
            registry.bind(project, generation, element)
        }

        assertTrue("an in-flight old-session request must not publish a handle", result.isFailure)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    fun testPublicBindCannotResurrectHandleFromCapturedRequestGeneration() = runBlocking {
        val element = fixtureElements().first()
        val registry = SymbolIdRegistry(idGenerator = { "stale-public-bind" })
        val oldRequestContext = registry.generationContext()

        registry.resetSession()
        val result = withContext(oldRequestContext) {
            ReadAction.compute<Result<String>, Throwable> {
                runCatching { registry.bind(project, element) }
            }
        }

        assertTrue("public bind must honor the request generation captured by dispatcher", result.isFailure)
        assertEquals(0, registry.sizeForTest())
        registry.dispose()
    }

    private fun fixtureElements(): List<PsiElement> {
        writeProjectFile(
            "symbol-registry-src/RegistryTargets.java",
            """
            class RegistryTargets {
                int first;
                int second;
                int third;
            }
            """.trimIndent()
        )
        val basePath = requireNotNull(project.basePath)
        return ReadAction.compute<List<PsiElement>, Throwable> {
            val virtualFile = requireNotNull(
                LocalFileSystem.getInstance().findFileByPath("$basePath/symbol-registry-src/RegistryTargets.java")
            )
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) as PsiJavaFile
            psiFile.classes.single().fields.toList()
        }
    }

    private fun bind(registry: SymbolIdRegistry, element: PsiElement): String =
        ReadAction.compute<String, Throwable> { registry.bind(project, element) }

    private fun assertResolved(registry: SymbolIdRegistry, symbolId: String) {
        val result = ReadAction.compute<Result<PsiElement>, Throwable> {
            registry.resolve(project, symbolId)
        }
        assertTrue("Expected $symbolId to resolve, got ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue(result.getOrThrow().isValid)
    }

    private fun assertExpired(registry: SymbolIdRegistry, symbolId: String) {
        val result = ReadAction.compute<Result<PsiElement>, Throwable> {
            registry.resolve(project, symbolId)
        }
        assertTrue("Expected $symbolId to be expired", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SYMBOL_ID_EXPIRED"))
    }
}
