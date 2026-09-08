package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.BuildDiagnosticsCacheService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.BuildMessage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileDiagnosticsAnalysis
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.IntentionInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProblemInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestResultInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * MCP tool that analyzes files for code problems and available intentions.
 *
 * This tool leverages public IntelliJ diagnostics APIs to detect:
 * - Compilation errors
 * - Code warnings and weak warnings
 * - Available quick fixes and intentions
 *
 * Additionally supports:
 * - Build errors/warnings from the last build
 * - Test results from open test run tabs
 *
 * File diagnostics use open-editor daemon highlights when the file is already
 * open, and public batch code-smell analysis for closed files. Either way the file is
 * re-read from disk first, so an out-of-band edit is analyzed as written.
 */
class GetDiagnosticsTool : AbstractMcpTool() {

    companion object {
        private const val MAX_PROBLEMS = 100
        private const val MAX_INTENTIONS = 50
        private const val MAX_FILES = 100
        private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
    }

    override val name = "ide_diagnostics"

    override val description = """
        Get code diagnostics from multiple sources: file analysis (errors, warnings, intentions), build output (compiler errors/warnings from last build), and test results (from open test run tabs).

        Returns: problems with severity and location, available intentions/quick fixes, build errors, and test results with error messages and stack traces. Single-file calls report legacy top-level analysis metadata. Multi-file calls return one aggregate problems list plus fileAnalyses entries with mode, freshness, timeout, message, returned problemCount, and problemsTruncated metadata for each file. Code problems share a 100-item response cap; top-level problemsTruncated reports omitted problems. Fresh analysis does not imply complete output: re-query truncated files individually, narrowing startLine/endLine if needed.

        At least one source must be active: provide exactly one of 'file' or 'files' for code analysis, 'includeBuildErrors' for build output, or 'includeTestResults' for test results. Can combine file analysis with build and test results. A multi-file call accepts at most 100 supplied paths, all sharing one analysis timeout budget; lexical aliases are analyzed only once.

        File analysis uses fresh daemon highlights for files that are already open in an editor. Closed files use public batch analysis, so weak warnings and quick-fix intentions may be less complete unless the file is open. The analyzed file is re-read from disk first, so results reflect edits made outside the IDE without calling ide_sync_files. For diagnostics across many files or the whole project with per-file coverage metadata, use ide_project_diagnostics.

        Parameters: file or files (mutually exclusive, enable code analysis), line + column (optional, for intentions, single file only), startLine/endLine (optional, single file only), includeBuildErrors (optional), includeTestResults (optional), severity (optional, default 'all'), testResultFilter (optional, default 'failed'), maxBuildErrors (optional, default 100), maxTestResults (optional, default 100).

        Example: {"file": "src/MyClass.java"} or {"files": ["src/A.java", "src/B.java"]} or {"includeBuildErrors": true, "severity": "errors"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(required = false, description = "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). Optional — enables per-file code analysis.")
        .property("files", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
            }
            put("minItems", 1)
            put("maxItems", MAX_FILES)
            put("uniqueItems", true)
            put("description", "Project-relative file paths to analyze under one shared timeout budget (max $MAX_FILES). Mutually exclusive with 'file'.")
        })
        .intProperty("line", "1-based line number for intention lookup. Optional, defaults to 1. Requires file.")
        .intProperty("column", "1-based column number for intention lookup. Optional, defaults to 1. Requires file.")
        .intProperty("startLine", "Filter problems to start from this line. Optional. Requires file.")
        .intProperty("endLine", "Filter problems to end at this line. Optional. Requires file.")
        .booleanProperty(ParamNames.INCLUDE_BUILD_ERRORS, "Include errors/warnings from the last build. Default: false.")
        .booleanProperty(ParamNames.INCLUDE_TEST_RESULTS, "Include test results from open test run tabs. Default: false.")
        .enumProperty(ParamNames.SEVERITY, "Filter by severity across all sources. Default: all.", listOf("all", "errors", "warnings"))
        .enumProperty(ParamNames.TEST_RESULT_FILTER, "Filter test results: 'failed' (default) or 'all'.", listOf("failed", "all"))
        .intProperty(ParamNames.MAX_BUILD_ERRORS, "Max build errors to return. Default: 100, max: 500.")
        .intProperty(ParamNames.MAX_TEST_RESULTS, "Max test results to return. Default: 100, max: 500.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        // Parse arguments
        val rawFile = arguments["file"]
        val rawFiles = arguments["files"]
        val hasFileArgument = rawFile != null && rawFile != JsonNull
        val hasFilesArgument = rawFiles != null && rawFiles != JsonNull
        val filePath = if (hasFileArgument) {
            (rawFile as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return createErrorResult("Parameter 'file' must be a non-blank project-relative path.")
        } else {
            null
        }
        val filePaths = if (hasFilesArgument) {
            val paths = (rawFiles as? JsonArray)
                ?: return createErrorResult("Parameter 'files' must be an array of project-relative paths.")
            if (paths.size > MAX_FILES) {
                return createErrorResult("Parameter 'files' supports at most $MAX_FILES paths per request.")
            }
            // Use the normalized path only as the deduplication key. Keeping the first supplied
            // representation makes fileAnalyses and problem locations echo a caller's request,
            // while preventing aliases such as src/A.java and src/./A.java from consuming the
            // shared analysis budget twice.
            val firstPathByNormalizedPath = linkedMapOf<String, String>()
            paths.forEachIndexed { index, element ->
                val requestedPath = (element as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return createErrorResult("Parameter 'files[$index]' must be a non-blank project-relative path.")
                val normalizedPath = try {
                    normalizeProjectRelativePath(requestedPath)
                } catch (e: IllegalArgumentException) {
                    return createErrorResult("Parameter 'files[$index]' ${e.message}")
                }
                firstPathByNormalizedPath.putIfAbsent(normalizedPath, requestedPath)
            }
            firstPathByNormalizedPath.values.toList()
        } else {
            null
        }
        val line = arguments["line"]?.jsonPrimitive?.intOrNull ?: 1
        val column = arguments["column"]?.jsonPrimitive?.intOrNull ?: 1
        val startLine = arguments["startLine"]?.jsonPrimitive?.intOrNull
        val endLine = arguments["endLine"]?.jsonPrimitive?.intOrNull
        val includeBuildErrors = arguments[ParamNames.INCLUDE_BUILD_ERRORS]?.jsonPrimitive?.booleanOrNull ?: false
        val includeTestResults = arguments[ParamNames.INCLUDE_TEST_RESULTS]?.jsonPrimitive?.booleanOrNull ?: false
        val severity = arguments[ParamNames.SEVERITY]?.jsonPrimitive?.content ?: "all"
        val testResultFilter = arguments[ParamNames.TEST_RESULT_FILTER]?.jsonPrimitive?.content ?: "failed"
        val maxBuildErrors = (arguments[ParamNames.MAX_BUILD_ERRORS]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 500)
        val maxTestResults = (arguments[ParamNames.MAX_TEST_RESULTS]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 500)

        if (hasFileArgument && hasFilesArgument) {
            return createErrorResult("Parameters 'file' and 'files' are mutually exclusive; provide exactly one of them for code analysis.")
        }

        if (hasFilesArgument && filePaths.isNullOrEmpty()) {
            return createErrorResult("Parameter 'files' must contain at least one project-relative file path.")
        }

        // Location filters and intention lookup only have unambiguous semantics for one file.
        val hasLocationArguments = listOf("line", "column", "startLine", "endLine")
            .any { name -> arguments[name]?.let { it != JsonNull } == true }
        if (filePath == null && hasLocationArguments) {
            return createErrorResult("Parameters 'line', 'column', 'startLine', and 'endLine' are only supported with the single 'file' parameter.")
        }

        // Validate: at least one source must be active
        if (filePath == null && filePaths.isNullOrEmpty() && !includeBuildErrors && !includeTestResults) {
            return createErrorResult("At least one source must be active: provide 'file' or 'files' for code analysis, 'includeBuildErrors' for build output, or 'includeTestResults' for test results.")
        }

        // File diagnostics
        var problems: List<ProblemInfo>? = null
        var problemsTruncated: Boolean? = null
        var intentions: List<IntentionInfo>? = null
        var analysisFresh: Boolean? = null
        var analysisTimedOut: Boolean? = null
        var analysisMessage: String? = null
        var analysisMode: String? = null
        var fileAnalyses: List<FileDiagnosticsAnalysis>? = null

        if (filePath != null) {
            requireSmartMode(project)

            val virtualFile = resolveFile(project, filePath)
                ?: return createErrorResult("File not found: $filePath")

            val fileEditorManager = FileEditorManager.getInstance(project)
            val analysisResult = DiagnosticsAnalysisService.getInstance(project).analyzeFile(
                virtualFile = virtualFile,
                filePath = filePath,
                severity = severity,
                startLine = startLine,
                endLine = endLine,
                // One-item lookahead distinguishes exactly-at-limit output from truncation.
                maxProblems = MAX_PROBLEMS + 1
            )
            // analyzeFile refreshes the file from disk, so it can discover the file is gone; the
            // VirtualFile is then invalid and every PSI lookup below it throws.
            if (!virtualFile.isValid) {
                return createErrorResult("File no longer exists on disk: $filePath")
            }

            problems = analysisResult.problems.take(MAX_PROBLEMS)
            problemsTruncated = analysisResult.problems.size > MAX_PROBLEMS
            analysisFresh = analysisResult.analysisFresh
            analysisTimedOut = analysisResult.analysisTimedOut
            analysisMessage = analysisResult.analysisMessage
            if (problemsTruncated) {
                analysisMessage = appendAnalysisMessage(
                    analysisMessage,
                    "Problem output was truncated at $MAX_PROBLEMS items. Re-query with narrower startLine/endLine filters."
                )
            }
            analysisMode = analysisResult.analysisMode
            intentions = analyzeIntentions(
                project = project,
                fileEditorManager = fileEditorManager,
                virtualFile = virtualFile,
                line = line,
                column = column,
                highlights = analysisResult.highlights
            )

            if (intentions.isNullOrEmpty() && fileEditorManager.getEditors(virtualFile).filterIsInstance<TextEditor>().firstOrNull()?.editor == null) {
                analysisMessage = appendAnalysisMessage(
                    analysisMessage,
                    "Intentions are unavailable because the file is not open in an editor."
                )
            }
        } else if (filePaths != null) {
            requireSmartMode(project)

            val analysisService = DiagnosticsAnalysisService.getInstance(project)
            val deadlineNanos = System.nanoTime() + analysisService.configuredAnalysisTimeoutMs() * 1_000_000L
            val aggregateProblems = mutableListOf<ProblemInfo>()
            val perFileAnalyses = mutableListOf<FileDiagnosticsAnalysis>()
            problemsTruncated = false

            for (path in filePaths) {
                val remainingMs = remainingBudgetMs(deadlineNanos)
                if (remainingMs == null) {
                    perFileAnalyses += sharedBudgetExhausted(path)
                    continue
                }

                val virtualFile = resolveFile(project, path)
                if (virtualFile == null) {
                    perFileAnalyses += FileDiagnosticsAnalysis(
                        file = path,
                        mode = null,
                        fresh = false,
                        timedOut = false,
                        message = "File not found: $path"
                    )
                    continue
                }

                val analysisRemainingMs = remainingBudgetMs(deadlineNanos)
                if (analysisRemainingMs == null) {
                    perFileAnalyses += sharedBudgetExhausted(path)
                    continue
                }

                val remainingProblemSlots = MAX_PROBLEMS - aggregateProblems.size
                val analysisResult = analysisService.analyzeFile(
                    virtualFile = virtualFile,
                    filePath = path,
                    severity = severity,
                    startLine = null,
                    endLine = null,
                    // Still probe a file when no slots remain, so hidden errors are never
                    // presented as an empty, complete result for that file.
                    maxProblems = remainingProblemSlots + 1,
                    timeoutMs = analysisRemainingMs
                )

                val returnedProblems = analysisResult.problems.take(remainingProblemSlots)
                val fileProblemsTruncated = analysisResult.problems.size > remainingProblemSlots
                aggregateProblems += returnedProblems
                if (fileProblemsTruncated) problemsTruncated = true
                var message = when {
                    !virtualFile.isValid -> "File no longer exists on disk: $path"
                    else -> analysisResult.analysisMessage
                }
                if (fileProblemsTruncated) {
                    message = appendAnalysisMessage(
                        message,
                        "Problems from this file were omitted by the shared $MAX_PROBLEMS-item response cap. " +
                            "Re-query this path using 'file', narrowing startLine/endLine if needed."
                    )
                }
                perFileAnalyses += FileDiagnosticsAnalysis(
                    file = path,
                    mode = analysisResult.analysisMode,
                    fresh = analysisResult.analysisFresh,
                    timedOut = analysisResult.analysisTimedOut,
                    message = message,
                    problemCount = returnedProblems.size,
                    problemsTruncated = fileProblemsTruncated
                )
            }

            problems = aggregateProblems
            fileAnalyses = perFileAnalyses
        }

        // Build errors
        var buildErrors: List<BuildMessage>? = null
        var buildErrorCount: Int? = null
        var buildWarningCount: Int? = null
        var buildErrorsTruncated: Boolean? = null
        var buildTimestamp: Long? = null

        if (includeBuildErrors) {
            val cacheService = BuildDiagnosticsCacheService.getInstance(project)
            val allBuildMessages = cacheService.getLastBuildDiagnostics()
            val filteredBuildMessages = filterBuildMessagesBySeverity(allBuildMessages, severity)
            buildErrorsTruncated = filteredBuildMessages.size > maxBuildErrors
            buildErrors = filteredBuildMessages.take(maxBuildErrors)
            buildErrorCount = filteredBuildMessages.count { it.category == "ERROR" }
            buildWarningCount = filteredBuildMessages.count { it.category == "WARNING" }
            buildTimestamp = cacheService.getLastBuildTimestamp()
        }

        // Test results
        var testResults: List<TestResultInfo>? = null
        var testSummary: TestSummary? = null
        var testResultsTruncated: Boolean? = null

        if (includeTestResults) {
            val collectionResult = TestResultsCollector.collect(project, testResultFilter, severity, maxTestResults)
            if (collectionResult != null) {
                testResults = collectionResult.testResults
                testSummary = collectionResult.testSummary
                testResultsTruncated = collectionResult.truncated
            } else {
                testResults = emptyList()
                testSummary = TestSummary(total = 0, passed = 0, failed = 0, ignored = 0, runConfigName = null)
                testResultsTruncated = false
            }
        }

        return createJsonResult(DiagnosticsResult(
            problems = problems,
            intentions = intentions,
            problemCount = problems?.size,
            problemsTruncated = problemsTruncated,
            intentionCount = intentions?.size,
            analysisFresh = analysisFresh,
            analysisTimedOut = analysisTimedOut,
            analysisMessage = analysisMessage,
            analysisMode = analysisMode,
            fileAnalyses = fileAnalyses,
            buildErrors = buildErrors,
            buildErrorCount = buildErrorCount,
            buildWarningCount = buildWarningCount,
            buildErrorsTruncated = buildErrorsTruncated,
            buildTimestamp = buildTimestamp,
            testResults = testResults,
            testSummary = testSummary,
            testResultsTruncated = testResultsTruncated
        ))
    }

    private fun remainingBudgetMs(deadlineNanos: Long): Long? {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return null
        return ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    /**
     * Returns a lexical, project-relative key without consulting the filesystem. This is used
     * before analysis so aliases cannot repeat work, and it deliberately permits an internal
     * `dir/../File.java` segment while rejecting a path that still escapes after normalization.
     */
    private fun normalizeProjectRelativePath(requestedPath: String): String {
        if (
            requestedPath.startsWith('/') ||
            requestedPath.startsWith('\\') ||
            WINDOWS_DRIVE_PATH.matches(requestedPath)
        ) {
            throw IllegalArgumentException("must be a project-relative path.")
        }

        val path = try {
            Path.of(requestedPath)
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("is not a valid project-relative path.")
        }
        if (path.isAbsolute) {
            throw IllegalArgumentException("must be a project-relative path.")
        }

        val normalized = path.normalize()
        if (normalized.nameCount > 0 && normalized.getName(0).toString() == "..") {
            throw IllegalArgumentException("must not escape the project root through path traversal.")
        }
        return normalized.toString()
    }

    private fun sharedBudgetExhausted(file: String) = FileDiagnosticsAnalysis(
        file = file,
        mode = null,
        fresh = false,
        timedOut = true,
        message = "Shared diagnostics analysis timeout budget was exhausted before this file could be analyzed."
    )

    private suspend fun analyzeIntentions(
        project: Project,
        fileEditorManager: FileEditorManager,
        virtualFile: VirtualFile,
        line: Int,
        column: Int,
        highlights: List<HighlightInfo>
    ): List<IntentionInfo> = suspendingReadAction {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        if (psiFile == null) {
            return@suspendingReadAction emptyList()
        }

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        if (document == null) {
            return@suspendingReadAction emptyList()
        }

        val editor = fileEditorManager.getEditors(virtualFile)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor

        if (editor == null) {
            return@suspendingReadAction emptyList()
        }

        collectIntentions(project, psiFile, document, editor, line, column, highlights)
    }

    private fun filterBuildMessagesBySeverity(messages: List<BuildMessage>, severity: String): List<BuildMessage> {
        return when (severity) {
            "errors" -> messages.filter { it.category == "ERROR" }
            "warnings" -> messages.filter { it.category == "WARNING" }
            else -> messages
        }
    }

    // ========== Intention Collection ==========

    private fun collectIntentions(
        project: Project,
        psiFile: PsiFile,
        document: Document,
        editor: Editor,
        line: Int,
        column: Int,
        highlights: List<HighlightInfo>
    ): List<IntentionInfo> {
        val intentions = mutableListOf<IntentionInfo>()

        try {
            val offset = getOffset(document, line, column) ?: 0

            // Collect quick fixes from highlights at this position
            collectQuickFixes(project, editor, psiFile, offset, highlights, intentions)

            // Collect general intention actions
            if (psiFile.findElementAt(offset) != null) {
                collectGeneralIntentions(project, editor, psiFile, intentions)
            }
        } catch (_: Exception) {
            // Intention discovery might fail
        }

        return intentions.distinctBy { it.name }
    }

    private fun collectQuickFixes(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        offset: Int,
        highlights: List<HighlightInfo>,
        intentions: MutableList<IntentionInfo>
    ) {
        highlights
            .asSequence()
            .filter { it.startOffset <= offset && it.endOffset >= offset }
            .forEach { highlightInfo ->
            highlightInfo.findRegisteredQuickFix<Any> { descriptor, _ ->
                val action = descriptor.action
                try {
                    if (action.isAvailable(project, editor, psiFile)) {
                        intentions.add(IntentionInfo(
                            name = action.text,
                            description = action.familyName.takeIf { it != action.text }
                        ))
                    }
                } catch (_: Exception) {
                    // Availability check might fail
                }
                null
            }
            }
    }

    private fun collectGeneralIntentions(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        intentions: MutableList<IntentionInfo>
    ) {
        IntentionManager.getInstance()
            .getAvailableIntentions()
            .take(MAX_INTENTIONS)
            .forEach { action ->
                try {
                    val isAvailable = action.isAvailable(project, editor, psiFile)
                    if (isAvailable) {
                        intentions.add(IntentionInfo(
                            name = action.text,
                            description = action.familyName.takeIf { it != action.text }
                        ))
                    }
                } catch (_: Exception) {
                    // Individual intention check might fail
                }
            }
    }

    private fun appendAnalysisMessage(existing: String?, additional: String): String {
        if (existing.isNullOrBlank()) return additional
        if (existing.contains(additional)) return existing
        return "$existing $additional"
    }
}
