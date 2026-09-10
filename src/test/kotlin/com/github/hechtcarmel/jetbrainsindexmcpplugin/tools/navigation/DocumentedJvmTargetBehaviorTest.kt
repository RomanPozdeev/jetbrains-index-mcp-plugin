package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Executes the copyable JVM target examples, using Kotlin PSI when its plugin is enabled. */
class DocumentedJvmTargetBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var fixturePath: String
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("doc-target-src")
        val kotlin = PluginDetectors.kotlin.isAvailable
        fixturePath = "doc-target-src/Foo.${if (kotlin) "kt" else "java"}"
        writeProjectFile(fixturePath, if (kotlin) {
            "package com.example\nclass Foo { fun bar(): Int = 1 }"
        } else {
            "package com.example; public class Foo { public int bar() { return 1; } }"
        })
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testReadmeQualifiedTargetExampleResolvesJvmDeclaration() = runBlocking {
        assertDocumentedTargetResolves("README.md")
    }

    fun testUsageQualifiedTargetExampleResolvesJvmDeclaration() = runBlocking {
        assertDocumentedTargetResolves("USAGE.md")
    }

    fun testToolsReferenceQualifiedTargetExampleResolvesJvmDeclaration() = runBlocking {
        assertDocumentedTargetResolves("src/main/resources/skill/ide-index-mcp/references/tools-reference.md")
    }

    private suspend fun assertDocumentedTargetResolves(document: String) {
        // Control: prove that the same documented qualifiedName identifies real JVM source
        // through the supported JVM symbol-reference selector, including its omitted parentheses.
        val control = FindDefinitionTool().execute(project, buildJsonObject {
            put("target", buildJsonObject {
                put("qualifiedName", "com.example.Foo#bar")
                put("language", "Java")
            })
        })
        assertToolSucceeded("Supported Java qualified selector must resolve the JVM fixture", control)
        assertEquals("bar", json.decodeFromString<DefinitionResult>(toolText(control)).symbolName)

        val documentText = Files.readString(Path.of(System.getProperty("user.dir"), document))
        val examplePattern = Regex("""\{\s*"target"\s*:\s*\{\s*"qualifiedName"\s*:\s*"com\.example\.Foo#bar"\s*,\s*"language"\s*:\s*"[^"]+"\s*\}\s*\}""")
        val example = requireNotNull(examplePattern.find(documentText)) {
            "The actual documented qualified-target example must be present in $document"
        }.value
        val result = FindDefinitionTool().execute(project, Json.parseToJsonElement(example).jsonObject)
        assertToolSucceeded("Copyable qualified-target example from $document must execute: $example", result)
        val definition = json.decodeFromString<DefinitionResult>(toolText(result))
        assertEquals("bar", definition.symbolName)
        assertEquals(fixturePath, definition.file)
    }
}
