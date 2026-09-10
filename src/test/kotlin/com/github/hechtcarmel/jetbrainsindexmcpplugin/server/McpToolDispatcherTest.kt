package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files

/**
 * Covers the seam between "a client asked for tool X" and the tool running: the enabled/disabled
 * gate, project resolution and history recording.
 *
 * The JSON-RPC envelope around it belongs to the MCP Kotlin SDK and is verified end-to-end over
 * real HTTP by
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.McpProtocolConformanceTest].
 */
class McpToolDispatcherTest : BasePlatformTestCase() {

    // Production dispatch starts on Ktor worker threads. Running off the EDT also lets the
    // multi-project routing fixture suspend while project opening performs its EDT work.
    override fun runInDispatchThread(): Boolean = false

    private lateinit var dispatcher: McpToolDispatcher
    private lateinit var toolRegistry: ToolRegistry

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry().apply { registerBuiltInTools() }
        dispatcher = McpToolDispatcher(toolRegistry)
    }

    fun testUndeclaredSymbolIdDoesNotRouteIndexStatus() = runBlocking {
        val routedDispatcher = McpToolDispatcher(
            toolRegistry = toolRegistry,
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> },
            symbolIdRegistryProvider = { error("undeclared symbolId must not consult the registry") }
        )

        val result = routedDispatcher.call(ToolNames.INDEX_STATUS, buildJsonObject {
            put("symbolId", "not-a-handle")
        })

        assertFalse("an undeclared symbolId must be ignored by the dispatcher: ${result.text}", result.isFailure)
    }

    fun testUndeclaredNestedTargetDoesNotRouteIndexStatus() = runBlocking {
        val routedDispatcher = McpToolDispatcher(
            toolRegistry = toolRegistry,
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> },
            symbolIdRegistryProvider = { error("undeclared target must not consult the registry") }
        )

        val result = routedDispatcher.call(ToolNames.INDEX_STATUS, buildJsonObject {
            putJsonObject("target") { put("symbolId", "not-a-handle") }
        })

        assertFalse("an undeclared target must be ignored by the dispatcher: ${result.text}", result.isFailure)
    }

    fun testStringValuedTargetSchemaDoesNotEnableNestedSymbolRouting() = runBlocking {
        val stringTargetTool = object : McpTool {
            override val name = "ide_string_target_probe"
            override val description = "Test-only string target probe"
            override val inputSchema = SchemaBuilder.tool()
                .stringProperty("target", "Unrelated string-valued target")
                .build()

            override suspend fun execute(project: Project, arguments: JsonObject): CallToolResult =
                CallToolResult(content = listOf(TextContent("executed")))
        }
        val probeRegistry = ToolRegistry().apply { register(stringTargetTool) }
        val routedDispatcher = McpToolDispatcher(
            toolRegistry = probeRegistry,
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> },
            symbolIdRegistryProvider = { error("a string-valued target must not consult the symbol registry") }
        )

        val result = routedDispatcher.call(stringTargetTool.name, buildJsonObject {
            putJsonObject("target") { put("symbolId", "not-a-handle") }
        })

        assertFalse("a string-valued target schema must not enable nested routing: ${result.text}", result.isFailure)
    }

    fun testNullSymbolIdIsTreatedAsAbsent() = runBlocking {
        val routedDispatcher = McpToolDispatcher(
            toolRegistry = toolRegistry,
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> },
            symbolIdRegistryProvider = { error("null symbolId must not consult the registry") }
        )

        val result = routedDispatcher.call(ToolNames.FIND_DEFINITION, buildJsonObject {
            put("symbolId", JsonNull)
        })

        assertTrue("the tool should report its missing target", result.isFailure)
        assertTrue(result.text, result.text.contains(ErrorMessages.SYMBOL_ID_OR_SYMBOL_OR_POSITION_REQUIRED))
    }

    fun testSymbolIdRoutesToItsOwningProjectWhenMultipleProjectsAreOpen() = runBlocking {
        var generated = 0
        val serverEpoch = McpServerEpoch()
        val registry = SymbolIdRegistry(
            idGenerator = { "multi-project-route-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val psiFile = myFixture.addFileToProject("src/MultiProjectRoute.java", "class MultiProjectRoute {}")
        val symbolId = ReadAction.compute<String, Throwable> { registry.bind(project, psiFile) }
        var executedProject: Project? = null
        val probe = RoutingProbeTool { executedProject = it }
        val probeRegistry = ToolRegistry().apply { register(probe) }
        val routedDispatcher = McpToolDispatcher(
            toolRegistry = probeRegistry,
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> },
            symbolIdRegistryProvider = { registry },
            serverEpochProvider = { serverEpoch }
        )
        val secondProjectRoot = Files.createTempDirectory("symbol-id-routing-project")
        var secondProject: Project? = null

        try {
            secondProject = requireNotNull(
                ProjectManagerEx.getInstanceEx().openProjectAsync(
                    secondProjectRoot,
                    com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils.openTask()
                )
            )
            assertTrue(
                "test precondition: two non-default projects must be open",
                ProjectManager.getInstance().openProjects.count { !it.isDefault } >= 2
            )

            val result = routedDispatcher.call(probe.name, buildJsonObject {
                put("symbolId", symbolId)
            })

            assertFalse("valid handle should route to its owning project: ${result.text}", result.isFailure)
            assertSame(project, executedProject)
        } finally {
            secondProject?.let { ProjectManagerEx.getInstanceEx().forceCloseProjectAsync(it, false) }
            secondProjectRoot.toFile().deleteRecursively()
        }
    }

    fun testNestedSymbolIdIsUsedForProjectRoutingBeforeToolExecution() = runBlocking {
        var generated = 0
        val serverEpoch = McpServerEpoch()
        val registry = SymbolIdRegistry(
            idGenerator = { "nested-route-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val psiFile = myFixture.addFileToProject("src/NestedRoute.java", "class NestedRoute {}")
        val symbolId = ReadAction.compute<String, Throwable> { registry.bind(project, psiFile) }
        var executedProject: Project? = null
        val probe = RoutingProbeTool { executedProject = it }
        val probeRegistry = ToolRegistry().apply { register(probe) }
        val routedDispatcher = McpToolDispatcher(
            toolRegistry = probeRegistry,
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> },
            symbolIdRegistryProvider = { registry },
            serverEpochProvider = { serverEpoch }
        )

        val success = routedDispatcher.call(probe.name, buildJsonObject {
            putJsonObject("target") { put("symbolId", symbolId) }
        })
        assertFalse("valid nested handle should route to its owning project", success.isFailure)
        assertSame(project, executedProject)

        executedProject = null
        val mixed = routedDispatcher.call(probe.name, buildJsonObject {
            put("file", "src/NestedRoute.java")
            putJsonObject("target") { put("symbolId", symbolId) }
        })
        assertTrue("mixed nested and flat selectors must fail", mixed.isFailure)
        assertTrue(mixed.text, mixed.text.contains("mutually exclusive"))
        assertTrue(mixed.text, mixed.text.contains("file"))
        assertNull("normalization must reject the request before tool execution", executedProject)

        val expired = routedDispatcher.call(probe.name, buildJsonObject {
            putJsonObject("target") { put("symbolId", "missing-handle") }
        })
        assertTrue("unknown nested handle must fail routing even with one project open", expired.isFailure)
        assertTrue(expired.text.contains("SYMBOL_ID_EXPIRED"))
    }

    fun testToolCallWithValidTool() = runBlocking {
        val result = dispatcher.call(ToolNames.INDEX_STATUS, buildJsonObject { })

        assertFalse("${ToolNames.INDEX_STATUS} should succeed: ${result.text}", result.isFailure)
        assertTrue("Result should carry content", result.content.isNotEmpty())
    }

    fun testUnknownToolReportsErrorInResultNotAsAProtocolFailure() = runBlocking {
        val result = dispatcher.call("ide_does_not_exist", buildJsonObject { })

        assertTrue("Unknown tool must be an error result", result.isFailure)
        assertTrue(
            "Error text should name the tool, was: ${result.text}",
            result.text.contains("ide_does_not_exist")
        )
    }

    fun testDisabledToolIsHiddenFromTheToolListAndRefusedWhenCalled() = runBlocking {
        val settings = McpSettings.getInstance()
        val originalDisabled = settings.disabledTools
        try {
            settings.setToolEnabled(ToolNames.INDEX_STATUS, false)

            val advertised = McpServerFactory(toolRegistry, dispatcher).newServer().tools.keys
            assertFalse(
                "A disabled tool must not be advertised in tools/list",
                advertised.contains(ToolNames.INDEX_STATUS)
            )

            val result = dispatcher.call(ToolNames.INDEX_STATUS, buildJsonObject { })
            assertTrue("Calling a disabled tool must fail", result.isFailure)
            assertTrue(
                "Error text should explain how to re-enable it, was: ${result.text}",
                result.text.contains("disabled")
            )
        } finally {
            settings.disabledTools = originalDisabled
        }
    }

    fun testEnabledToolIsAdvertised() {
        val advertised = McpServerFactory(toolRegistry, dispatcher).newServer().tools.keys

        assertTrue("Registry should expose built-in tools", advertised.isNotEmpty())
        assertTrue(
            "${ToolNames.INDEX_STATUS} should be advertised when enabled",
            advertised.contains(ToolNames.INDEX_STATUS)
        )
    }

    private class RoutingProbeTool(
        private val onExecute: (Project) -> Unit
    ) : AbstractMcpTool() {
        override val name: String = "ide_symbol_id_routing_probe"
        override val description: String = "Test-only symbolId routing probe"
        override val inputSchema: ToolSchema = SchemaBuilder.tool()
            .target()
            .symbolId()
            .file(required = false)
            .build()

        override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
            onExecute(project)
            return CallToolResult(content = listOf(TextContent("routed")))
        }
    }
}
