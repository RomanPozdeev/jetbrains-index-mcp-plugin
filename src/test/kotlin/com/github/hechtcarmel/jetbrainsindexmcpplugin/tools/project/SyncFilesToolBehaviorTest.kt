package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Behavior coverage for `ide_sync_files`.
 *
 * This tool is enabled by default and previously had no execution coverage at all — only schema
 * and registration assertions. Its entire reason to exist is making externally-created files
 * visible to the IDE, so that is what these tests exercise: write a file behind the VFS's back,
 * then assert the tool surfaces it.
 */
class SyncFilesToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(text: String): SyncFilesResult = json.decodeFromString(text)

    /** Writes straight to disk, deliberately bypassing the VFS refresh in `writeProjectFile`. */
    private fun writeBehindVfs(relativePath: String, content: String) {
        val basePath = requireNotNull(project.basePath)
        val path = java.nio.file.Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    /**
     * The tool's entire reason to exist: a file created behind the IDE's back becomes visible.
     *
     * Exercised through the whole-project path rather than an explicit `paths` list. Resolving an
     * explicit path goes through `AbstractMcpTool.resolveFile`, whose `refreshAndFindFileByPath`
     * fallback is skipped whenever `isReadAccessAllowed` — always true on the EDT, where platform
     * tests run. In production MCP requests arrive on Ktor worker threads, so that fallback does
     * run; it simply cannot be reached from here.
     */
    fun testSyncMakesAnExternallyCreatedFileVisibleToTheVfs() = runBlocking {
        writeBehindVfs("synced/External.java", "public class External {}\n")
        assertNull(
            "Precondition: the file must be invisible to the VFS before syncing, " +
                "otherwise this test proves nothing",
            LocalFileSystem.getInstance().findFileByPath("${project.basePath}/synced/External.java")
        )

        val result = SyncFilesTool().execute(project, buildJsonObject { })

        assertToolSucceeded("sync_files should succeed", result)
        assertNotNull(
            "After ide_sync_files the externally created file must be resolvable through the VFS",
            LocalFileSystem.getInstance().findFileByPath("${project.basePath}/synced/External.java")
        )
    }

    fun testSyncReportsTheExactPathsItSynchronized() = runBlocking {
        writeProjectFile("synced/A.java", "public class A {}\n")
        writeProjectFile("synced/B.java", "public class B {}\n")

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive("synced/A.java"))
                add(JsonPrimitive("synced/B.java"))
            })
        })

        assertToolSucceeded("sync_files should succeed", result)
        val payload = decode(toolText(result))
        assertEquals(listOf("synced/A.java", "synced/B.java"), payload.syncedPaths)
        assertEquals(listOf("synced/A.java", "synced/B.java"), payload.requestedPaths)
        assertEquals(listOf("synced/A.java", "synced/B.java"), payload.refreshedRoots)
        assertTrue(payload.deletedPaths.isEmpty())
        assertFalse("An explicit path list is not a whole-project sync", payload.syncedAll)
        assertEquals("Synchronized 2 path(s).", payload.message)
    }

    fun testSyncWithoutPathsSynchronizesTheWholeProject() = runBlocking {
        val result = SyncFilesTool().execute(project, buildJsonObject { })

        assertToolSucceeded("sync_files should succeed with no arguments", result)
        val payload = decode(toolText(result))
        assertTrue("Omitting paths must sync the whole project", payload.syncedAll)
        assertEquals(listOf(project.basePath), payload.syncedPaths)
        assertTrue(payload.requestedPaths.isEmpty())
        assertEquals(listOf(project.basePath), payload.refreshedRoots)
        assertTrue(payload.deletedPaths.isEmpty())
        assertEquals("Synchronized entire project.", payload.message)
    }

    /**
     * A deleted path is refreshed through its nearest existing parent. This invalidates the stale
     * VFS entry while keeping the requested and actually-refreshed paths unambiguous in the result.
     */
    fun testSyncRefreshesDeletedPathThroughNearestExistingParent() = runBlocking {
        val deletedFile = writeProjectFile("synced/deleted/Old.java", "public class Old {}\n")
        val absolutePath = deletedFile.toString()
        val staleVirtualFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath(absolutePath))
        val stalePsiFile = requireNotNull(PsiManager.getInstance(project).findFile(staleVirtualFile))
        Files.delete(deletedFile)

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive("synced/deleted/Old.java"))
            })
        })

        assertToolSucceeded("A deleted target should be synchronized via its parent", result)
        val payload = decode(toolText(result))
        assertEquals(listOf("synced/deleted/Old.java"), payload.syncedPaths)
        assertEquals(listOf("synced/deleted/Old.java"), payload.requestedPaths)
        assertEquals(listOf("synced/deleted"), payload.refreshedRoots)
        assertEquals(listOf("synced/deleted/Old.java"), payload.deletedPaths)
        assertEquals(
            "Synchronized 1 path(s), including 1 deleted path(s) via existing parent directories.",
            payload.message
        )
        assertNull("The deleted file must be evicted from the VFS", LocalFileSystem.getInstance().findFileByPath(absolutePath))
        assertFalse("The deleted file's PSI must be invalidated", stalePsiFile.isValid)
    }

    fun testSyncWithEmptyPathListFallsBackToWholeProject() = runBlocking {
        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { })
        })

        assertToolSucceeded("An empty path list should not error", result)
        val payload = decode(toolText(result))
        assertTrue("An empty list is treated as 'sync everything'", payload.syncedAll)
    }

    fun testSyncRejectsTraversalAndAbsolutePaths() = runBlocking {
        val traversal = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add(JsonPrimitive("synced/../Outside.java")) })
        })
        assertToolFailed("Path traversal must be rejected", traversal)
        assertTrue(toolText(traversal).contains("traversal"))

        val absolute = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add(JsonPrimitive("${project.basePath}/Outside.java")) })
        })
        assertToolFailed("Absolute paths must be rejected", absolute)
        assertTrue(toolText(absolute).contains("relative"))
    }

    fun testSyncRejectsSymbolicLinkEscape() = runBlocking {
        val outsideDirectory = Files.createTempDirectory("ide-sync-files-outside")
        val link = java.nio.file.Path.of(requireNotNull(project.basePath), "outside-link")
        Files.createSymbolicLink(link, outsideDirectory)

        try {
            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("paths", buildJsonArray { add(JsonPrimitive("outside-link/Deleted.java")) })
            })

            assertToolFailed("A symlink must not escape the selected project root", result)
            assertTrue(toolText(result).contains("symbolic link"))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outsideDirectory)
        }
    }

    fun testSyncResolvesPathsAgainstSelectedWorkspaceContentRoot() = runBlocking {
        // A same-named file under the workspace base makes a base-first implementation lie: the
        // selected sub-project target is absent and must be reported as deleted.
        writeProjectFile("Shared.java", "public class Shared {}\n")
        val workspaceRootPath = java.nio.file.Path.of(requireNotNull(project.basePath), "workspace-module")
        Files.createDirectories(workspaceRootPath)
        val workspaceRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath(workspaceRootPath.toString())
        )
        PsiTestUtil.addContentRoot(module, workspaceRoot)

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("project_path", workspaceRootPath.toString())
            put("paths", buildJsonArray { add(JsonPrimitive("Shared.java")) })
        })

        assertToolSucceeded("Workspace sync should use the selected content root", result)
        val payload = decode(toolText(result))
        assertEquals(listOf("Shared.java"), payload.requestedPaths)
        assertEquals(listOf("Shared.java"), payload.deletedPaths)
        assertEquals(listOf(workspaceRootPath.toRealPath().toString()), payload.refreshedRoots)
    }
}
