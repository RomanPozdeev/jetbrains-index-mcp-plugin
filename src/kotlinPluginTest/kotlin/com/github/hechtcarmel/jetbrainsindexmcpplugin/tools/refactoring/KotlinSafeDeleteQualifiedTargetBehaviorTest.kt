package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KotlinSafeDeleteQualifiedTargetBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("semantic-src")
        writeProjectFile("semantic-src/Service.kt", """
            package lighttarget
            class Service {
                fun compute(): Int { return 1 }
                fun keep(): Int { return 7 }
            }
        """.trimIndent())
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    private fun source(): String = ReadAction.compute<String, Throwable> {
        readProjectFileVfs("semantic-src/Service.kt")
    }

    fun testSafeDeleteQualifiedJavaSelectorDeletesKotlinSource() = runBlocking {
        val args = buildJsonObject {
            put("language", "Java")
            put("symbol", "lighttarget.Service#compute()")
        }
        val before = source()
        val preview = SafeDeleteTool().execute(project, JsonObject(args + ("dryRun" to JsonPrimitive(true))))
        assertToolSucceeded("Preview Kotlin source through a Java signature", preview)
        val plan = Json { ignoreUnknownKeys = true }.decodeFromString<RefactoringPreviewResult>(toolText(preview))
        assertTrue(plan.toString(), plan.canApply)
        assertEquals(before, source())
        val applied = SafeDeleteTool().execute(project, args)
        assertToolSucceeded("Apply must delete the same declaration that was previewed", applied)
        assertFalse(source().contains("fun compute"))
        assertTrue(source().contains("fun keep(): Int { return 7 }"))
    }
}
