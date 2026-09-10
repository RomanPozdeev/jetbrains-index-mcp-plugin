package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FileStructureNodesBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        SymbolIdRegistry.getInstance().resetSession()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testFileStructureKeepsLegacyTextAndReturnsExactStructuredHandles() = runBlocking {
        writeProjectFile(
            "structure-src/OverloadedStructure.java",
            """
            public class OverloadedStructure {
                public void choose(int value) {} public void choose(long value) {}
            }
            """.trimIndent()
        )

        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/OverloadedStructure.java")
        })
        assertToolSucceeded("file_structure should return Java structure", result)

        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        assertTrue("Legacy formatted output must remain available", payload.structure.contains("OverloadedStructure"))

        val classNode = payload.nodes.single()
        assertEquals("OverloadedStructure", classNode.name)
        assertEquals("CLASS", classNode.kind.name)
        assertEquals(listOf("public"), classNode.modifiers)
        assertEquals(1, classNode.line)
        assertEquals(3, classNode.endLine)
        assertNotNull("The class node should expose a symbol handle", classNode.symbolId)

        val overloads = classNode.children.filter { it.name == "choose" }
        assertEquals("Both same-line overloads must be represented", 2, overloads.size)
        assertEquals("Both overloads intentionally share a source line", 1, overloads.map { it.line }.toSet().size)
        assertEquals(listOf("public"), overloads.first().modifiers)
        assertTrue(overloads.all { !it.signature.isNullOrBlank() })

        val ids = overloads.map { requireNotNull(it.symbolId) }
        assertEquals("Exact PSI declarations need distinct handles", 2, ids.toSet().size)

        val parameterTypes = ReadAction.compute<Set<String>, RuntimeException> {
            ids.map { symbolId ->
                val method = SymbolIdRegistry.getInstance().resolve(project, symbolId).getOrThrow() as PsiMethod
                method.parameterList.parameters.single().type.canonicalText
            }.toSet()
        }
        assertEquals(setOf("int", "long"), parameterTypes)
        assertFalse(payload.symbolIdsTruncated)
        assertEquals(0, payload.symbolIdsOmitted)
    }

    fun testLargeOutlinePreservesEveryNodeWithoutReturningAlreadyEvictedHandles() = runBlocking {
        val fieldCount = SymbolIdRegistry.DEFAULT_MAX_ENTRIES
        writeProjectFile("structure-src/LargeStructure.java", buildString {
            appendLine("class LargeStructure {")
            repeat(fieldCount) { appendLine("  int field$it;") }
            appendLine("}")
        })

        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/LargeStructure.java")
        })
        assertToolSucceeded("large outlines should retain the complete structure", result)
        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        val root = payload.nodes.single()
        assertEquals(fieldCount, root.children.size)
        assertEquals("field${fieldCount - 1}", root.children.last().name)
        assertTrue(payload.structure.contains("field${fieldCount - 1}"))
        val allNodes = listOf(root) + root.children
        val registry = SymbolIdRegistry.getInstance()
        val handles = allNodes.mapNotNull { it.symbolId }
        assertEquals(registry.responseHandleBudget, handles.size)
        assertTrue(payload.symbolIdsTruncated)
        assertEquals(allNodes.size - handles.size, payload.symbolIdsOmitted)
        assertNotNull("preorder gives the root a usable handle", root.symbolId)
        ReadAction.run<RuntimeException> {
            for (handle in handles) {
                assertTrue("every returned handle must still resolve", registry.resolve(project, handle).isSuccess)
            }
        }
    }
}
