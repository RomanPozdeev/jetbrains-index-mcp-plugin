package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DispatcherCursorRoutingBehaviorTest : McpPlatformTestCase() {
    fun testUnrelatedCursorMustNotRemoveDiagnosticsFileSelector() = runBlocking {
        val settings = McpSettings.getInstance()
        val wasEnabled = settings.isToolEnabled(ToolNames.DIAGNOSTICS)
        try {
            settings.setToolEnabled(ToolNames.DIAGNOSTICS, true)
            val dispatcher = McpToolDispatcher(
                toolRegistry = ToolRegistry().apply { registerBuiltInTools() },
                recordHistory = { _, _ -> },
                updateHistory = { _, _, _, _, _ -> }
            )
            val requestedFile = "selector-probe/DoesNotExist.java"
            val control = dispatcher.call(ToolNames.DIAGNOSTICS, buildJsonObject {
                put("project_path", project.basePath)
                put("file", requestedFile)
                put("includeBuildErrors", true)
            })
            assertTrue("Control request must check the requested file: ${control.text}", control.isFailure)
            assertTrue(control.text, control.text.contains("File not found: $requestedFile"))

            // ide_diagnostics has no cursor parameter and must not switch to build-only
            // diagnostics merely because the caller retains an unrelated pagination field.
            val withCursor = dispatcher.call(ToolNames.DIAGNOSTICS, buildJsonObject {
                put("project_path", project.basePath)
                put("file", requestedFile)
                put("includeBuildErrors", true)
                put("cursor", "retained-cursor")
            })
            assertTrue(
                "The same missing file must still be reported when an unrelated cursor is present: ${withCursor.text}",
                withCursor.isFailure
            )
            assertTrue(withCursor.text, withCursor.text.contains("File not found: $requestedFile"))
        } finally {
            settings.setToolEnabled(ToolNames.DIAGNOSTICS, wasEnabled)
        }
    }
}
