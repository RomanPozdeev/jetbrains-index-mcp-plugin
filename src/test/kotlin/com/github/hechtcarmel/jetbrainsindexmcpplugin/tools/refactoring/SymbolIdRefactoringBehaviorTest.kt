package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ResolvedSymbolInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SymbolIdRefactoringBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        SymbolIdRegistry.getInstance().resetSession()
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
        } finally {
            super.tearDown()
        }
    }

    fun testChangeSignatureByIdReturnsReboundMetadataAndUpdatesCaller() = runBlocking {
        registerSourceRoot("signature-id-src")
        val declaration = """
            package signatureid;
            class Service {
                int calculate(int input) { return input; }
            }
        """.trimIndent()
        writeProjectFile("signature-id-src/signatureid/Service.java", declaration)
        writeProjectFile(
            "signature-id-src/signatureid/Caller.java",
            """
            package signatureid;
            class Caller {
                int call(Service service) { return service.calculate(1); }
            }
            """.trimIndent()
        )
        val definition = definitionAt("signature-id-src/signatureid/Service.java", declaration, "calculate")

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
            put("newName", "compute")
        })
        assertToolSucceeded("change_signature should accept symbolId", result)
        val payload = json.parseToJsonElement(toolText(result)).jsonObject
        val updated = json.decodeFromJsonElement<ResolvedSymbolInfo>(payload.getValue("updatedSymbol"))
        assertEquals(definition.symbolId, updated.symbolId)
        assertEquals("compute", updated.name)
        assertRenamedInFile("signature-id-src/signatureid/Service.java", "calculate", "compute")
        assertRenamedInFile("signature-id-src/signatureid/Caller.java", "calculate", "compute")
    }

    fun testSafeDeleteByIdReturnsInvalidatedIdAndFurtherLookupsExpire() = runBlocking {
        registerSourceRoot("safe-delete-id-src")
        val source = """
            class SafeDeleteById {
                void doomed() {}
                void survivor() {}
            }
        """.trimIndent()
        writeProjectFile("safe-delete-id-src/SafeDeleteById.java", source)
        val definition = definitionAt("safe-delete-id-src/SafeDeleteById.java", source, "doomed")

        val deleteResult = SafeDeleteTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
            put("force", true)
        })
        assertToolSucceeded("safe_delete should accept symbolId", deleteResult)
        val deleted = decode<RefactoringResult>(deleteResult)
        assertEquals(definition.symbolId, deleted.invalidatedSymbolId)
        assertFileDoesNotContain("safe-delete-id-src/SafeDeleteById.java", "doomed")
        assertFileContains("safe-delete-id-src/SafeDeleteById.java", "survivor")

        val lookup = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        assertToolFailed("Safe-deleted symbol ID must be invalidated", lookup)
        assertTrue(toolText(lookup).contains("SYMBOL_ID_EXPIRED"))
    }

    private suspend fun definitionAt(file: String, source: String, marker: String): DefinitionResult {
        val result = FindDefinitionTool().execute(project, positionArguments(file, source, marker))
        assertToolSucceeded("find_definition at $file / $marker", result)
        return decode(result)
    }

    private fun positionArguments(file: String, source: String, marker: String): JsonObject {
        val offset = source.indexOf(marker)
        require(offset >= 0) { "Marker '$marker' is absent from fixture" }
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        return buildJsonObject {
            put("file", file)
            put("line", source.substring(0, offset).count { it == '\n' } + 1)
            put("column", offset - lineStart + 1)
        }
    }

    private fun nestedPositionArguments(
        file: String,
        source: String,
        marker: String,
        extras: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ): JsonObject {
        val offset = source.indexOf(marker)
        require(offset >= 0) { "Marker '$marker' is absent from fixture" }
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        return buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    put("file", file)
                    put("line", source.substring(0, offset).count { it == '\n' } + 1)
                    put("column", offset - lineStart + 1)
                }
            }
            extras()
        }
    }

    private inline fun <reified T> decode(result: CallToolResult): T =
        json.decodeFromString(toolText(result))
}
