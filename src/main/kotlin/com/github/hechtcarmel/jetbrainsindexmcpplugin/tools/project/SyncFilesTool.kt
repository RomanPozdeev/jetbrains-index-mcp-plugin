package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
                "Paths relative to the selected project/content root. Rejects absolute paths, traversal, symlink escapes and unknown missing paths. Deleted paths known to VFS refresh their nearest existing parent. Omit to sync the entire root."
            )
        })
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        if (project.basePath == null) {
            return createErrorResult("Project base path is not available.")
        }

        val requestedPaths = arguments["paths"]?.jsonArray?.map { it.jsonPrimitive.content }
        val projectPathArg = arguments["project_path"]?.jsonPrimitive?.contentOrNull
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

            val knownRoots = targets.map { target ->
                findVfsRefreshRoot(effectiveRoot.path, target.diskRefreshPath)
                    ?: return createErrorResult(
                        "Could not find an existing VFS parent for '${target.requestedPath}' inside the selected project root."
                    )
            }
            val discoveryRoots = mutableListOf<RefreshRoot>()
            val refreshTargets = minimalRefreshRoots(targets.zip(knownRoots).map { (target, knownRoot) ->
                var current = knownRoot
                while (current.path != target.diskRefreshPath) {
                    ProgressManager.checkCanceled()
                    // Discover only the requested path components. Recursing from the nearest
                    // cached ancestor could refresh the whole project for one newly created file.
                    VfsUtil.markDirtyAndRefresh(false, false, true, current.virtualFile)
                    discoveryRoots += current.copy(recursive = false)
                    val nextPath = current.path.resolve(current.path.relativize(target.diskRefreshPath).getName(0))
                    val nextFile = LocalFileSystem.getInstance().findFileByPath(nextPath.toString())
                        ?: return createErrorResult("Path disappeared while synchronizing '${target.requestedPath}'.")
                    current = RefreshRoot(nextPath, nextFile)
                }
                // Deletion only needs a shallow parent refresh; VFS invalidates the removed
                // child's whole subtree. Explicit directory targets still receive a full refresh.
                current.copy(recursive = target.existed)
            })

            for ((recursive, roots) in refreshTargets.groupBy { it.recursive }) {
                VfsUtil.markDirtyAndRefresh(
                    false,
                    recursive,
                    true,
                    *roots.map { it.virtualFile }.toTypedArray()
                )
            }

            syncedPaths = requestedPaths
            refreshedRoots = minimalRefreshRoots(discoveryRoots + refreshTargets)
                .map { displayPath(effectiveRoot.path, it.path) }
            deletedPaths = targets.filterNot { it.existed }.map { it.requestedPath }
            syncedAll = false
        } else {
            val projectDir = LocalFileSystem.getInstance().findFileByPath(effectiveRoot.path.toString())
                ?: return createErrorResult("Selected project root is not available in the IDE VFS: ${effectiveRoot.path}")
            VfsUtil.markDirtyAndRefresh(false, true, true, projectDir)
            syncedPaths = listOf(effectiveRoot.path.toString())
            refreshedRoots = listOf(effectiveRoot.path.toString())
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
    private fun resolveEffectiveRoot(project: Project, projectPathArg: String?): SyncRoot? {
        val baseRoot = project.basePath?.let(::syncRootOrNull) ?: return null
        if (projectPathArg == null) return baseRoot

        val requestedRoot = syncRootOrNull(projectPathArg) ?: return null
        val allowedRoots = (listOfNotNull(project.basePath) + ProjectUtils.getModuleContentRoots(project))
            .mapNotNull(::syncRootOrNull)
            .distinctBy { it.path }

        return allowedRoots
            .filter { requestedRoot.realPath.startsWith(it.realPath) }
            .maxWithOrNull(
                compareBy<SyncRoot> { it.realPath.nameCount }
                    // Several registered aliases may have the same canonical root. Refresh
                    // the VFS tree selected by the caller, not another alias of those files.
                    .thenBy { if (requestedRoot.path.startsWith(it.path)) it.path.nameCount else -1 }
            )
    }

    private fun resolveTarget(effectiveRoot: SyncRoot, requestedPath: String): SyncTarget {
        val relativePath = parseSafeRelativePath(requestedPath)
        val targetPath = effectiveRoot.path.resolve(relativePath).normalize()
        if (!targetPath.startsWith(effectiveRoot.path)) {
            throw IllegalArgumentException("Path escapes the selected project root: $requestedPath")
        }

        val existed = Files.exists(targetPath)
        val nearestExisting = nearestExistingPath(effectiveRoot.path, targetPath)
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
        if (!realExisting.startsWith(effectiveRoot.realPath)) {
            throw IllegalArgumentException("Path escapes the selected project root through a symbolic link: $requestedPath")
        }
        if (!existed && LocalFileSystem.getInstance().findFileByPath(targetPath.toString()) == null) {
            throw IllegalArgumentException("Not found: $requestedPath. Only files known to the IDE can be synchronized after deletion.")
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
        val combined = roots.groupBy { it.path }.values.map { duplicates ->
            duplicates.first().copy(recursive = duplicates.any { it.recursive })
        }
        for (candidate in combined.sortedBy { it.path.nameCount }) {
            if (selected.none { candidate.path == it.path || it.recursive && candidate.path.startsWith(it.path) }) {
                selected += candidate
            }
        }
        return selected
    }

    private fun displayPath(effectiveRoot: Path, path: Path): String {
        if (path == effectiveRoot) return effectiveRoot.toString()
        return effectiveRoot.relativize(path).joinToString("/") { it.toString() }
    }

    private fun syncRootOrNull(path: String): SyncRoot? {
        return try {
            val absolutePath = Path.of(path).toAbsolutePath()
            // Routing also accepts deleted subdirectories/content roots that remain in the VFS.
            // Resolve the surviving prefix first, so missing suffixes retain symlink containment
            // checks without requiring the very directory being synchronized to still exist.
            var existing = absolutePath
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.parent ?: return null
            }
            val realPath = existing.toRealPath().resolve(existing.relativize(absolutePath)).normalize()
            SyncRoot(absolutePath.normalize(), realPath)
        } catch (_: Exception) {
            null
        }
    }

    /** VFS keeps separate cached trees for symlink aliases; canonical paths only define containment. */
    private data class SyncRoot(
        val path: Path,
        val realPath: Path
    )

    private data class SyncTarget(
        val requestedPath: String,
        val diskRefreshPath: Path,
        val existed: Boolean
    )

    private data class RefreshRoot(
        val path: Path,
        val virtualFile: VirtualFile,
        val recursive: Boolean = true
    )

    private companion object {
        val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
    }
}
