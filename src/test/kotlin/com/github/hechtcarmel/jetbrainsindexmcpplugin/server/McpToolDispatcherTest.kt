package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import kotlinx.serialization.json.put
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ReadAction
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject

/**
 * Covers the seam between "a client asked for tool X" and the tool running: the enabled/disabled
 * gate, project resolution and history recording.
 *
 * The JSON-RPC envelope around it belongs to the MCP Kotlin SDK and is verified end-to-end over
 * real HTTP by
 * [com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.McpProtocolConformanceTest].
 */
class McpToolDispatcherTest : BasePlatformTestCase() {

    private lateinit var dispatcher: McpToolDispatcher
    private lateinit var toolRegistry: ToolRegistry

    override fun setUp() {
        super.setUp()
        toolRegistry = ToolRegistry().apply { registerBuiltInTools() }
        dispatcher = McpToolDispatcher(toolRegistry)
    }

    fun testSymbolIdIsUsedForProjectRoutingBeforeToolExecution() = runBlocking {
        var generated = 0
        val serverEpoch = McpServerEpoch()
        val registry = SymbolIdRegistry(
            idGenerator = { "nested-route-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val psiFile = myFixture.addFileToProject("src/NestedRoute.java", "class NestedRoute {}")
        val symbolId = ReadAction.compute<String, Throwable> { registry.bind(project, psiFile) }
        val routedDispatcher = McpToolDispatcher(
            toolRegistry = toolRegistry,
            recordHistory = { _, _ -> },
            updateHistory = { _, _, _, _, _ -> },
            symbolIdRegistryProvider = { registry },
            serverEpochProvider = { serverEpoch }
        )

        val success = routedDispatcher.call(ToolNames.INDEX_STATUS, buildJsonObject {
            put("symbolId", symbolId)
        })
        assertFalse("valid handle should route to its owning project", success.isFailure)

        val expired = routedDispatcher.call(ToolNames.INDEX_STATUS, buildJsonObject {
            put("symbolId", "missing-handle")
        })
        assertTrue("unknown handle must fail routing even with one project open", expired.isFailure)
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
}
