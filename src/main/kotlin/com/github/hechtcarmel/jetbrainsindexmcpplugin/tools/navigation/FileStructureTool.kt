package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TreeFormatter
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for analyzing the hierarchical structure of source files.
 *
 * Provides a tree-formatted view of file structure similar to IDE's Structure view,
 * showing classes, methods, fields, Markdown headings, and their nesting relationships.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Markdown
 */
class FileStructureTool : AbstractMcpTool() {

    override val name = "ide_file_structure"

    override val description = """
        Get the hierarchical structure of a source file (similar to IDE's Structure view).

        Shows classes, methods, fields, functions, PHP namespaces, constants, enum cases, Markdown headings, and their nesting relationships in a tree format.

        Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Markdown

        Returns: The legacy formatted tree string plus structured nodes with element types,
        modifiers, signatures, source ranges, children, and optional symbolId handles.

        Parameters: file (required) - Path relative to project root

        Example: {"file": "src/main/java/com/example/MyClass.java"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val file = requiredStringArg(arguments, "file").getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }

        return suspendingReadAction {
            val psiFile = getPsiFile(project, file)
                ?: return@suspendingReadAction createErrorResult("File not found: $file")

            // Get structure handler for this file's language
            val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)
                ?: return@suspendingReadAction createErrorResult(
                    "Language not supported for file structure. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForStructure().joinToString(", ")}"
                )

            // Extract structure
            val nodes = handler.getFileStructure(psiFile, project)

            // Preserve the previous human-readable payload even when no nodes were found.
            val treeString = if (nodes.isEmpty()) {
                "File is empty or has no parseable structure.\n\n" +
                    "File: ${psiFile.name}\n" +
                    "Language: ${psiFile.language.id}"
            } else {
                TreeFormatter.format(nodes, psiFile.name, psiFile.language.id)
            }
            val structuredNodes = bindStructureNodes(project, nodes)

            createJsonResult(FileStructureResult(
                file = file,
                language = psiFile.language.id,
                structure = treeString,
                nodes = structuredNodes
            ))
        }
    }

    /**
     * Attaches handles while the exact PSI elements produced by the language handler are still
     * available. Re-resolving a node later by line would be ambiguous for overloads and nested
     * declarations that share a line.
     */
    private fun bindStructureNodes(project: Project, nodes: List<StructureNode>): List<StructureNode> =
        nodes.map { node ->
            node.copy(
                children = bindStructureNodes(project, node.children),
                symbolId = node.pointerTarget
                    ?.takeIf { it.isValid }
                    ?.let { bindSymbolId(project, it) }
            )
        }
}
