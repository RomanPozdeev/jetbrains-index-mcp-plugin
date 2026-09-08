package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

class SyncFilesTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.SYNC_FILES

    override val description = """
        Force the IDE to synchronize its virtual file system and PSI cache with external file changes. Use when files were created, modified, or deleted outside the IDE (e.g., by coding agents) and other IDE tools report stale results or miss references in recently changed files.
        call it on-demand only when needed.
        Parameters: paths (optional array of relative file/directory paths to sync; if omitted, syncs entire project), project_path (optional).
        Example: {} or {"paths": ["src/main/java/com/example/NewFile.java", "src/main/java/com/example/ModifiedFile.java"]}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .property("paths", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
            }
            put(
                "description",
                "File or directory paths relative to the selected project/content root. Absolute paths, traversal, and symlink escapes are rejected. Deleted paths refresh their nearest existing parent. If omitted, syncs the entire selected root."
            )
        })
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        if (project.basePath == null) {
            return createErrorResult("Project base path is not available.")
        }

        val requestedPaths = arguments["paths"]?.jsonArray?.map { it.jsonPrimitive.content }
        val projectPathArg = arguments["project_path"]?.jsonPrimitive?.content
        val effectiveRoot = resolveEffectiveRoot(project, projectPathArg)
            ?: return createErrorResult(
                "The selected project_path is not inside the project base path or one of its content roots."
            )

        val syncedPaths: List<String>
        val refreshedRoots: List<String>
        val deletedPaths: List<String>
        val syncedAll: Boolean

        if (requestedPaths != null && requestedPaths.isNotEmpty()) {
            // Validate and resolve the complete batch before touching the VFS. A bad path must not
            // leave the call half-applied.
            val targets = try {
                requestedPaths.map { relativePath -> resolveTarget(effectiveRoot, relativePath) }
            } catch (e: IllegalArgumentException) {
                return createErrorResult(e.message ?: "Invalid sync path.")
            }

            val refreshTargets = minimalRefreshRoots(targets.map { target ->
                findVfsRefreshRoot(effectiveRoot, target.diskRefreshPath)
                    ?: return createErrorResult(
                        "Could not find an existing VFS parent for '${target.requestedPath}' inside the selected project root."
                    )
            })

            if (refreshTargets.isNotEmpty()) {
                VfsUtil.markDirtyAndRefresh(
                    false,
                    true,
                    true,
                    *refreshTargets.map { it.virtualFile }.toTypedArray()
                )
            }

            syncedPaths = requestedPaths
            refreshedRoots = refreshTargets.map { displayPath(effectiveRoot, it.path) }
            deletedPaths = targets.filterNot { it.existed }.map { it.requestedPath }
            syncedAll = false
        } else {
            val projectDir = LocalFileSystem.getInstance().findFileByPath(effectiveRoot.toString())
                ?: return createErrorResult("Selected project root is not available in the IDE VFS: $effectiveRoot")
            VfsUtil.markDirtyAndRefresh(false, true, true, projectDir)
            syncedPaths = listOf(effectiveRoot.toString())
            refreshedRoots = listOf(effectiveRoot.toString())
            deletedPaths = emptyList()
            syncedAll = true
        }

        commitDocuments(project)

        val message = when {
            syncedAll -> "Synchronized entire project."
            deletedPaths.isNotEmpty() ->
                "Synchronized ${syncedPaths.size} path(s), including ${deletedPaths.size} deleted path(s) via existing parent directories."
            else -> "Synchronized ${syncedPaths.size} path(s)."
        }

        return createJsonResult(SyncFilesResult(
            syncedPaths = syncedPaths,
            syncedAll = syncedAll,
            message = message,
            requestedPaths = requestedPaths.orEmpty(),
            refreshedRoots = refreshedRoots,
            deletedPaths = deletedPaths
        ))
    }

    /** Resolve the routing hint to the most specific project/content root that contains it. */
    private fun resolveEffectiveRoot(project: Project, projectPathArg: String?): Path? {
        val baseRoot = project.basePath?.let(::realPathOrNull) ?: return null
        if (projectPathArg == null) return baseRoot

        val requestedRoot = realPathOrNull(projectPathArg) ?: return null
        val allowedRoots = (listOfNotNull(project.basePath) + ProjectUtils.getModuleContentRoots(project))
            .mapNotNull(::realPathOrNull)
            .distinct()

        return allowedRoots
            .filter { requestedRoot == it || requestedRoot.startsWith(it) }
            .maxByOrNull { it.nameCount }
    }

    private fun resolveTarget(effectiveRoot: Path, requestedPath: String): SyncTarget {
        val relativePath = parseSafeRelativePath(requestedPath)
        val targetPath = effectiveRoot.resolve(relativePath).normalize()
        if (targetPath != effectiveRoot && !targetPath.startsWith(effectiveRoot)) {
            throw IllegalArgumentException("Path escapes the selected project root: $requestedPath")
        }

        val existed = Files.exists(targetPath)
        val nearestExisting = nearestExistingPath(effectiveRoot, targetPath)
            ?: throw IllegalArgumentException(
                "No existing parent inside the selected project root for path: $requestedPath"
            )
        val realExisting = try {
            nearestExisting.toRealPath()
        } catch (_: Exception) {
            throw IllegalArgumentException(
                "Path cannot be resolved safely; it may contain a dangling symbolic link: $requestedPath"
            )
        }
        if (realExisting != effectiveRoot && !realExisting.startsWith(effectiveRoot)) {
            throw IllegalArgumentException("Path escapes the selected project root through a symbolic link: $requestedPath")
        }

        return SyncTarget(
            requestedPath = requestedPath,
            diskRefreshPath = if (existed) targetPath else nearestExisting,
            existed = existed
        )
    }

    private fun parseSafeRelativePath(requestedPath: String): Path {
        if (requestedPath.isBlank()) {
            throw IllegalArgumentException("Sync paths must not be blank.")
        }
        if (requestedPath.split('/', '\\').any { it == ".." }) {
            throw IllegalArgumentException("Path traversal is not allowed in sync paths: $requestedPath")
        }
        if (
            requestedPath.startsWith('/') ||
            requestedPath.startsWith('\\') ||
            WINDOWS_DRIVE_PATH.matches(requestedPath)
        ) {
            throw IllegalArgumentException("Sync paths must be relative to the selected project root: $requestedPath")
        }

        val path = try {
            Path.of(requestedPath)
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("Invalid sync path: $requestedPath")
        }
        if (path.isAbsolute) {
            throw IllegalArgumentException("Sync paths must be relative to the selected project root: $requestedPath")
        }
        return path
    }

    private fun nearestExistingPath(effectiveRoot: Path, targetPath: Path): Path? {
        var candidate: Path? = targetPath
        while (candidate != null && (candidate == effectiveRoot || candidate.startsWith(effectiveRoot))) {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate
            }
            if (candidate == effectiveRoot) break
            candidate = candidate.parent
        }
        return null
    }

    private fun findVfsRefreshRoot(effectiveRoot: Path, diskRefreshPath: Path): RefreshRoot? {
        val localFileSystem = LocalFileSystem.getInstance()
        var candidate: Path? = diskRefreshPath
        while (candidate != null && (candidate == effectiveRoot || candidate.startsWith(effectiveRoot))) {
            val virtualFile = localFileSystem.findFileByPath(candidate.toString())
            if (virtualFile != null) {
                return RefreshRoot(candidate, virtualFile)
            }
            if (candidate == effectiveRoot) break
            candidate = candidate.parent
        }
        return null
    }

    /** Recursive refresh of an ancestor already covers all requested descendants. */
    private fun minimalRefreshRoots(roots: List<RefreshRoot>): List<RefreshRoot> {
        val selected = mutableListOf<RefreshRoot>()
        for (candidate in roots.distinctBy { it.path }.sortedBy { it.path.nameCount }) {
            if (selected.none { candidate.path == it.path || candidate.path.startsWith(it.path) }) {
                selected += candidate
            }
        }
        return selected
    }

    private fun displayPath(effectiveRoot: Path, path: Path): String {
        if (path == effectiveRoot) return effectiveRoot.toString()
        return effectiveRoot.relativize(path).joinToString("/") { it.toString() }
    }

    private fun realPathOrNull(path: String): Path? {
        return try {
            Path.of(path).toRealPath()
        } catch (_: Exception) {
            null
        }
    }

    private data class SyncTarget(
        val requestedPath: String,
        val diskRefreshPath: Path,
        val existed: Boolean
    )

    private data class RefreshRoot(
        val path: Path,
        val virtualFile: VirtualFile
    )

    private companion object {
        val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
    }
}
