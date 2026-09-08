package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.UnifiedTargetArguments
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class EditMemberTool : AbstractMcpTool() {

    override val supportsUnifiedTarget: Boolean = true

    override val name = ToolNames.EDIT_MEMBER

    override val description = """
        Replace an entire member or class declaration with new content.

        Replaces the complete declaration from the first modifier/annotation through the closing brace.
        Use for method signature changes, field rewrites, or class/interface declaration changes
        (adding type parameters, changing extends/implements, adding annotations).

        To edit a class/interface declaration itself, set member to the class name:
        {"file": "src/Worker.java", "class": "Worker", "member": "Worker", "content": "public interface Worker<T> extends Runnable { ... }"}

        The content should be the complete replacement including modifiers, type, name, and body.
        Auto-reformats the changed range by default.

        Examples:
        - {"symbolId": "<opaque-id>", "content": "public void renamed() {}"}
        - {"file": "src/Main.java", "class": "Main", "member": "process", "content": "public void process(String input, boolean validate) {\n    if (validate) check(input);\n}"}
        - {"file": "src/Config.kt", "class": "Config", "member": "timeout", "content": "val timeout: Duration = Duration.ofSeconds(30)"}
        - {"file": "src/Service.java", "member": "Service", "content": "public class Service<T> implements Serializable { ... }"}
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .file(required = false, description = "Path to file relative to project root. Required with member selectors; omit when symbolId is used.")
        .stringProperty(ParamNames.CLASS, "Class/interface name containing the member. Optional for top-level members (Kotlin).")
        .stringProperty(ParamNames.MEMBER, "Name of the method, function, field, or property to replace entirely. Required unless symbolId is used.")
        .intProperty(ParamNames.PARAMETER_COUNT, "Number of parameters (for disambiguating overloaded methods).")
        .intProperty(ParamNames.LINE, "1-based line number of the member (for disambiguation when multiple members share the same name).")
        .stringProperty(ParamNames.CONTENT, "The complete replacement member declaration including modifiers, type, name, and body.", required = true)
        .booleanProperty(ParamNames.REFORMAT, "Auto-reformat the changed range and optimize imports. Default: true.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val requestedSymbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)
        val hasQualifiedTarget = optionalStringArg(arguments, ParamNames.LANGUAGE) != null ||
            optionalStringArg(arguments, ParamNames.SYMBOL) != null
        val hasStructuredTarget = optionalStringArg(arguments, UnifiedTargetArguments.NORMALIZED_VARIANT) != null
        val memberName = optionalStringArg(arguments, ParamNames.MEMBER) ?: "<symbolId>"
        val content = arguments[ParamNames.CONTENT]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: content")
        if (content.isBlank()) {
            return createErrorResult("content must not be empty. To delete a member, use ide_refactor_safe_delete.")
        }
        val className = MemberEditingUtils.getOptionalString(arguments, ParamNames.CLASS)
        val parameterCount = MemberEditingUtils.getOptionalInt(arguments, ParamNames.PARAMETER_COUNT)
        val line = MemberEditingUtils.getOptionalInt(arguments, ParamNames.LINE)
        val reformat = MemberEditingUtils.getOptionalBoolean(arguments, ParamNames.REFORMAT)
        val hasLegacyMemberSelector = listOf(ParamNames.FILE, ParamNames.CLASS, ParamNames.MEMBER).any {
            optionalStringArg(arguments, it) != null
        } || parameterCount != null || line != null

        val prep = suspendingReadAction {
            if (requestedSymbolId != null || hasQualifiedTarget || hasStructuredTarget) {
                if (!hasStructuredTarget && hasLegacyMemberSelector) {
                    Result.failure(IllegalArgumentException(ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE))
                } else {
                    prepareMemberEditBySemanticTarget(project, arguments, requestedSymbolId)
                }
            } else {
                val filePath = optionalStringArg(arguments, ParamNames.FILE)
                    ?: return@suspendingReadAction Result.failure(
                        IllegalArgumentException("Missing required parameter: ${ParamNames.FILE}")
                    )
                if (memberName == "<symbolId>") {
                    return@suspendingReadAction Result.failure(
                        IllegalArgumentException("Missing required parameter: ${ParamNames.MEMBER}")
                    )
                }
                val virtualFile = resolveFile(project, filePath)
                    ?: return@suspendingReadAction Result.failure(
                        IllegalArgumentException("File not found: $filePath")
                    )
                if (!virtualFile.isWritable) {
                    return@suspendingReadAction Result.failure(
                        IllegalArgumentException("File is read-only and cannot be modified: ${virtualFile.path}")
                    )
                }
                prepareMemberEdit(project, virtualFile, filePath, className, memberName, parameterCount, line)
            }
        }

        return when {
            prep.isFailure -> prep.exceptionOrNull()!!.let { handleError(it, memberName) }
            else -> {
                val p = prep.getOrThrow()
                applyFullReplacement(project, p, content, reformat, requestedSymbolId)
            }
        }
    }

    private fun prepareMemberEditBySemanticTarget(
        project: Project,
        arguments: JsonObject,
        symbolId: String?
    ): Result<MemberEditPreparation> {
        val rawElement = resolveElementFromArguments(project, arguments).getOrElse { return Result.failure(it) }
        // A position resolves initially to the leaf token under the cursor. Convert that token to
        // its semantic declaration before asking the language-specific member resolver; symbolId
        // and qualified-name selectors already resolve directly to their declaration.
        val element = if (
            optionalStringArg(arguments, UnifiedTargetArguments.NORMALIZED_VARIANT) == UnifiedTargetArguments.POSITION
        ) {
            PsiUtils.resolveTargetElement(rawElement) ?: rawElement
        } else {
            rawElement
        }
        val psiFile = element.containingFile
            ?: return Result.failure(
                IllegalArgumentException(symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target has no source file")
            )
        val virtualFile = psiFile.virtualFile
            ?: return Result.failure(
                IllegalArgumentException(symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target has no editable source file")
            )
        if (!virtualFile.isWritable) {
            return Result.failure(IllegalArgumentException("File is read-only and cannot be modified: ${virtualFile.path}"))
        }
        val resolver = MemberEditingUtils.getResolver(psiFile, project)
            ?: return Result.failure(
                IllegalArgumentException("Member editing not supported for ${psiFile.language.displayName}. Supported: Java, Kotlin.")
            )
        val member = resolver.resolveMember(element)
            ?: return Result.failure(IllegalArgumentException("Target does not identify an editable Java/Kotlin member"))
        val document = MemberEditingUtils.getDocument(psiFile)
            ?: return Result.failure(IllegalArgumentException("Cannot get document for file: ${virtualFile.path}"))
        return Result.success(
            MemberEditPreparation(psiFile, document, member, ProjectUtils.getToolFilePath(project, virtualFile))
        )
    }

    private fun prepareMemberEdit(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        filePath: String,
        className: String?,
        memberName: String,
        parameterCount: Int?,
        line: Int?
    ): Result<MemberEditPreparation> {
        val psiFile = MemberEditingUtils.resolvePsiFile(project, filePath, virtualFile)
            ?: return Result.failure(Exception("File not found: $filePath"))

        val resolver = MemberEditingUtils.getResolver(psiFile, project)
            ?: return Result.failure(Exception("Member editing not supported for ${psiFile.language.displayName}. Supported: Java, Kotlin."))

        val scope = resolver.findClass(psiFile, className)
            ?: return Result.failure(
                MemberClassNotFoundException(
                    className ?: "",
                    emptyList()
                )
            )

        val members = resolver.findMembers(scope, memberName)
        val disambiguated = MemberResolverUtils.disambiguate(members, memberName, parameterCount, line)
        if (disambiguated.isFailure) return Result.failure(disambiguated.exceptionOrNull()!!)

        val member = disambiguated.getOrThrow()
        val document = MemberEditingUtils.getDocument(psiFile)
            ?: return Result.failure(Exception("Cannot get document for file: $filePath"))

        val relativePath = ProjectUtils.getToolFilePath(project, psiFile.virtualFile)
        return Result.success(MemberEditPreparation(psiFile, document, member, relativePath))
    }

    private suspend fun applyFullReplacement(
        project: Project,
        prep: MemberEditPreparation,
        content: String,
        reformat: Boolean,
        requestedSymbolId: String?
    ): CallToolResult {
        val member = prep.member
        val pointer = suspendingReadAction {
            SmartPointerManager.getInstance(project).createSmartPsiElementPointer(member.element)
        }

        var startLine = 0
        var endLine = 0
        var error: String? = null

        suspendingWriteAction(project, "Edit member: ${member.name}") {
            if (!member.element.isValid) {
                error =
                    "PSI element for '${member.name}' is no longer valid. The document may have been modified externally — retry the operation."
                return@suspendingWriteAction
            }
            val range = member.element.textRange
            val startOffset = range.startOffset
            val endOffset = range.endOffset

            prep.document.replaceString(startOffset, endOffset, content)
            MemberEditingUtils.commitDocuments(project)
            if (reformat) {
                MemberEditingUtils.reformatRange(project, prep.psiFile, startOffset, startOffset + content.length)
                MemberEditingUtils.commitDocuments(project)
            }
            startLine = MemberEditingUtils.safeLineNumber(prep.document, startOffset)
            endLine = MemberEditingUtils.safeLineNumber(prep.document, startOffset + content.length)
        }

        if (error != null) {
            return createErrorResult(error!!)
        }

        edtAction { MemberEditingUtils.saveToDisk() }

        val updatedSymbol = suspendingReadAction {
            val target = pointer.element
                ?: prep.psiFile.findElementAt(member.startOffset.coerceAtMost(prep.psiFile.textLength - 1))
                    ?.let(PsiUtils::findNamedElement)
            target?.let { resolvedSymbolInfo(project, it, requestedSymbolId) }
        }

        return createJsonResult(
            MemberEditResult(
                success = true,
                file = prep.relativePath,
                message = "Replaced ${member.kind} '${member.name}' entirely",
                startLine = startLine,
                endLine = endLine,
                updatedSymbol = updatedSymbol
            )
        )
    }

    private fun handleError(error: Throwable, memberName: String): CallToolResult {
        return when (error) {
            is MemberNotFoundException -> createJsonResult(MemberErrorResult(
                error = "member_not_found",
                member = memberName,
                hint = "Member '$memberName' not found in the specified scope."
            ))
            is AmbiguousMemberException -> createJsonResult(MemberErrorResult(
                error = "ambiguous_member",
                member = memberName,
                candidates = error.candidates.map {
                    MemberCandidate(it.name, it.kind, it.signature, it.parameterCount, it.line)
                },
                hint = error.hint
            ))
            is MemberClassNotFoundException -> createErrorResult(
                if (error.className.isEmpty()) "Could not determine target class. The file may have multiple classes — specify the 'class' parameter."
                else "Class '${error.className}' not found in file."
            )
            else -> createErrorResult(error.message ?: "Unknown error")
        }
    }
}
