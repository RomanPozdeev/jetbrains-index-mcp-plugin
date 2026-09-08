package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.refactoring.changeSignature.ChangeSignatureUsageProcessor
import com.intellij.refactoring.rename.ResolveSnapshotProvider
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

class RefactoringDryRunBehaviorTest : McpPlatformTestCase() {

    // MCP requests arrive on Ktor worker threads. Running these previews on the test EDT would
    // keep it blocked in runBlocking while RenameProcessor coordinates platform progress with
    // the EDT, creating a fixture-only deadlock that production does not have.
    override fun runInDispatchThread(): Boolean = false

    fun testRenameDryRunDiscoversCrossFileUsageAndPreservesFilesByteForByte() = runBlocking {
        registerSourceRoot("dry-rename-src")
        val declaration = writeProjectFile(
            "dry-rename-src/preview/RenameTarget.java",
            """
            package preview;
            class RenameTarget {
                String calculate(String input) { return input; }
            }
            """.trimIndent()
        )
        val caller = writeProjectFile(
            "dry-rename-src/preview/RenameCaller.java",
            """
            package preview;
            class RenameCaller {
                String call(RenameTarget target) { return target.calculate("value"); }
            }
            """.trimIndent()
        )
        val before = snapshotBytes(declaration, caller)
        val tool = RenameSymbolTool().also {
            it.processorRunHook = { error("RenameProcessor.run() must not be called by dry-run") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-rename-src/preview/RenameTarget.java")
            put("line", 3)
            put("column", 12)
            put("newName", "compute")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = true)
        assertTrue(preview.getValue("usageCount").jsonPrimitive.int >= 1)
        assertEquals(0, preview.getValue("conflictCount").jsonPrimitive.int)
        val affected = preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(affected.contains("dry-rename-src/preview/RenameTarget.java"))
        assertTrue(affected.contains("dry-rename-src/preview/RenameCaller.java"))
        assertBytesUnchanged(before)
        assertProjectFileExists("dry-rename-src/preview/RenameTarget.java")
        assertProjectFileAbsent("dry-rename-src/preview/compute.java")
    }

    fun testFileRenameDryRunPreservesFileAndReferences() = runBlocking {
        registerSourceRoot("dry-file-rename-src")
        val declaration = writeProjectFile(
            "dry-file-rename-src/preview/PreviewTarget.java",
            """
            package preview;
            public class PreviewTarget {}
            """.trimIndent()
        )
        val caller = writeProjectFile(
            "dry-file-rename-src/preview/PreviewCaller.java",
            """
            package preview;
            class PreviewCaller { PreviewTarget target; }
            """.trimIndent()
        )
        val before = snapshotBytes(declaration, caller)

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "dry-file-rename-src/preview/PreviewTarget.java")
            put("targetType", "file")
            put("newName", "RenamedTarget.java")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = true)
        assertEquals("file", preview.getValue("plannedChange").jsonObject.getValue("targetType").jsonPrimitive.content)
        assertProjectFileExists("dry-file-rename-src/preview/PreviewTarget.java")
        assertProjectFileAbsent("dry-file-rename-src/preview/RenamedTarget.java")
        assertBytesUnchanged(before)
    }

    fun testFileRenameDryRunFailsClosedForExistingSiblingWithoutChangingFiles() = runBlocking {
        val source = writeProjectFile("dry-file-rename-collision/Original.txt", "original\n")
        val existingSibling = writeProjectFile("dry-file-rename-collision/Target.txt", "existing\n")
        val before = snapshotBytes(source, existingSibling)

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "dry-file-rename-collision/Original.txt")
            put("targetType", "file")
            put("newName", "Target.txt")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertTrue(preview.getValue("conflictCount").jsonPrimitive.int >= 1)
        assertPreviewWarningContains(preview, "already exists in its containing directory")
        assertProjectFileExists("dry-file-rename-collision/Original.txt")
        assertProjectFileExists("dry-file-rename-collision/Target.txt")
        assertBytesUnchanged(before)
    }

    fun testSafeDeleteDryRunChecksUsagesAndPreservesFilesByteForByte() = runBlocking {
        registerSourceRoot("dry-delete-src")
        val declaration = writeProjectFile(
            "dry-delete-src/preview/DeleteTarget.java",
            """
            package preview;
            class DeleteTarget {
                String doomed() { return "value"; }
            }
            """.trimIndent()
        )
        val caller = writeProjectFile(
            "dry-delete-src/preview/DeleteCaller.java",
            """
            package preview;
            class DeleteCaller {
                String call(DeleteTarget target) { return target.doomed(); }
            }
            """.trimIndent()
        )
        val before = snapshotBytes(declaration, caller)
        val tool = SafeDeleteTool().also {
            it.beforeDeletionHook = { error("Deletion write phase must not be reached by dry-run") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-delete-src/preview/DeleteTarget.java")
            put("line", 3)
            put("column", 12)
            put("force", true)
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = true)
        assertTrue(preview.getValue("usageCount").jsonPrimitive.int >= 1)
        assertTrue(preview.getValue("conflictCount").jsonPrimitive.int >= 1)
        assertBytesUnchanged(before)
        assertProjectFileVfsContains("dry-delete-src/preview/DeleteTarget.java", "String doomed()")
    }

    fun testFileSafeDeleteDryRunPreservesTargetFile() = runBlocking {
        registerSourceRoot("dry-file-delete-src")
        val target = writeProjectFile(
            "dry-file-delete-src/preview/UnusedPreview.java",
            """
            package preview;
            public class UnusedPreview {}
            """.trimIndent()
        )
        val before = snapshotBytes(target)

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "dry-file-delete-src/preview/UnusedPreview.java")
            put("target_type", "file")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = true)
        assertEquals("file", preview.getValue("plannedChange").jsonObject.getValue("targetType").jsonPrimitive.content)
        assertProjectFileExists("dry-file-delete-src/preview/UnusedPreview.java")
        assertBytesUnchanged(before)
    }

    fun testFileSafeDeleteDryRunWithoutDeclarationsFailsClosed() = runBlocking {
        registerSourceRoot("dry-file-delete-unknown-src")
        val target = writeProjectFile(
            "dry-file-delete-unknown-src/preview/opaque.data",
            "opaque non-code payload"
        )
        val before = snapshotBytes(target)

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "dry-file-delete-unknown-src/preview/opaque.data")
            put("target_type", "file")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertPreviewWarningContains(preview, "complete usage discovery cannot be proven")
        assertEquals(0, preview.getValue("usageCount").jsonPrimitive.int)
        assertEquals(0, preview.getValue("conflictCount").jsonPrimitive.int)
        assertProjectFileExists("dry-file-delete-unknown-src/preview/opaque.data")
        assertBytesUnchanged(before)
    }

    fun testRenameDryRunReportsConflictsWithoutOpeningApplyPath() = runBlocking {
        val file = writeProjectFile(
            "dry-rename-conflict/RenameConflict.java",
            """
            class RenameConflict {
                void existing() {}
                void candidate() {}
            }
            """.trimIndent()
        )
        val before = snapshotBytes(file)
        val tool = RenameSymbolTool().also {
            it.processorRunHook = { error("RenameProcessor.run() must not be called by dry-run") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-rename-conflict/RenameConflict.java")
            put("line", 3)
            put("column", 10)
            put("newName", "existing")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertTrue(preview.getValue("conflictCount").jsonPrimitive.int >= 1)
        assertBytesUnchanged(before)
    }

    fun testRenameDryRunFailsClosedWhenAutomaticRenamerHasCandidates() = runBlocking {
        val path = writeProjectFile(
            "dry-rename-related/RelatedRename.java",
            """
            class RelatedRename {
                void target() {}
                void related() {}
            }
            """.trimIndent()
        )
        val before = snapshotBytes(path)
        val relatedMethod = ReadAction.compute<com.intellij.psi.PsiMethod, RuntimeException> {
            val virtualFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path.toString()))
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) as PsiJavaFile
            psiFile.classes.single().findMethodsByName("related", false).single()
        }
        val factory = object : AutomaticRenamerFactory {
            override fun isApplicable(element: com.intellij.psi.PsiElement): Boolean =
                (element as? com.intellij.psi.PsiMethod)?.name == "target"

            // The tool deliberately registers only user-selectable factories. A non-null option
            // therefore proves the preview sees a factory supplied through its normal setup,
            // rather than relying on RenameProcessor's separate built-in-factory fallback.
            override fun getOptionName(): String = "Synthetic related rename"
            override fun isEnabled(): Boolean = true
            override fun setEnabled(enabled: Boolean) = Unit

            override fun createRenamer(
                element: com.intellij.psi.PsiElement,
                newName: String,
                usages: Collection<UsageInfo>
            ): AutomaticRenamer = object : AutomaticRenamer() {
                init {
                    myElements.add(relatedMethod)
                    suggestAllNames("related", "renamedRelated")
                }

                override fun getDialogTitle(): String = "Synthetic related rename"
                override fun getDialogDescription(): String = "Synthetic related rename"
                override fun entityName(): String = "method"
            }
        }
        AutomaticRenamerFactory.EP_NAME.point.registerExtension(factory, testRootDisposable)

        val result = RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "dry-rename-related/RelatedRename.java")
            put("line", 2)
            put("column", 10)
            put("newName", "renamedTarget")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertPreviewWarningContains(preview, "found related symbols")
        assertBytesUnchanged(before)
    }

    fun testChangeSignatureDryRunDiscoversCallerAndPreservesFilesByteForByte() = runBlocking {
        registerSourceRoot("dry-signature-src")
        val declaration = writeProjectFile(
            "dry-signature-src/preview/SignatureTarget.java",
            """
            package preview;
            class SignatureTarget {
                String format(String input) { return input; }
            }
            """.trimIndent()
        )
        val caller = writeProjectFile(
            "dry-signature-src/preview/SignatureCaller.java",
            """
            package preview;
            class SignatureCaller {
                String call(SignatureTarget target) { return target.format("value"); }
            }
            """.trimIndent()
        )
        val before = snapshotBytes(declaration, caller)
        val tool = ChangeSignatureTool().also {
            it.processorRunHook = { error("ChangeSignatureProcessor.run() must not be called by dry-run") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-signature-src/preview/SignatureTarget.java")
            put("line", 3)
            put("column", 12)
            put("newParameters", buildJsonArray {
                add(buildJsonObject {
                    put("oldIndex", 0)
                    put("name", "input")
                    put("type", "String")
                })
                add(buildJsonObject {
                    put("oldIndex", -1)
                    put("name", "uppercase")
                    put("type", "boolean")
                    put("defaultValue", "false")
                })
            })
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = true)
        assertTrue(preview.getValue("usageCount").jsonPrimitive.int >= 1)
        val affected = preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(affected.contains("dry-signature-src/preview/SignatureTarget.java"))
        assertTrue(affected.contains("dry-signature-src/preview/SignatureCaller.java"))
        assertBytesUnchanged(before)
        assertProjectFileVfsDoesNotContain("dry-signature-src/preview/SignatureTarget.java", "uppercase")
        assertProjectFileVfsDoesNotContain("dry-signature-src/preview/SignatureCaller.java", "false")
    }

    fun testChangeSignatureDryRunMetadataUsesUsagesFilteredByConflictExtension() = runBlocking {
        registerSourceRoot("dry-signature-extension-src")
        val declaration = writeProjectFile(
            "dry-signature-extension-src/preview/ExtensionTarget.java",
            """
            package preview;
            class ExtensionTarget { String format(String input) { return input; } }
            """.trimIndent()
        )
        val caller = writeProjectFile(
            "dry-signature-extension-src/preview/ExtensionCaller.java",
            """
            package preview;
            class ExtensionCaller {
                String call(ExtensionTarget target) { return target.format("value"); }
            }
            """.trimIndent()
        )
        val before = snapshotBytes(declaration, caller)
        val filteringProcessor = object : ChangeSignatureUsageProcessor {
            override fun findUsages(changeInfo: ChangeInfo): Array<UsageInfo> = emptyArray()

            override fun findConflicts(
                changeInfo: ChangeInfo,
                usages: Ref<Array<UsageInfo>>
            ): MultiMap<PsiElement, String> {
                usages.set(emptyArray())
                return MultiMap.empty<PsiElement, String>()
            }

            override fun processUsage(
                changeInfo: ChangeInfo,
                usageInfo: UsageInfo,
                beforeMethodChange: Boolean,
                usages: Array<UsageInfo>
            ): Boolean = true

            override fun processPrimaryMethod(changeInfo: ChangeInfo): Boolean = true

            override fun shouldPreviewUsages(
                changeInfo: ChangeInfo,
                usages: Array<UsageInfo>
            ): Boolean = false

            override fun setupDefaultValues(
                changeInfo: ChangeInfo,
                usages: Ref<Array<UsageInfo>>,
                project: Project
            ): Boolean = true

            override fun registerConflictResolvers(
                conflictResolvers: MutableList<in ResolveSnapshotProvider.ResolveSnapshot>,
                resolveSnapshotProvider: ResolveSnapshotProvider,
                usages: Array<UsageInfo>,
                changeInfo: ChangeInfo
            ) = Unit
        }
        ChangeSignatureUsageProcessor.EP_NAME.point.registerExtension(
            filteringProcessor,
            testRootDisposable
        )

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "dry-signature-extension-src/preview/ExtensionTarget.java")
            put("line", 2)
            put("column", 32)
            put("newName", "render")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = true)
        assertEquals(0, preview.getValue("usageCount").jsonPrimitive.int)
        val affected = preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(affected.contains("dry-signature-extension-src/preview/ExtensionTarget.java"))
        assertFalse(affected.contains("dry-signature-extension-src/preview/ExtensionCaller.java"))
        assertBytesUnchanged(before)
    }

    fun testChangeSignaturePreviewAndApplyPreserveExistingThrowsForVisibilityChange() = runBlocking {
        registerSourceRoot("dry-signature-throws-src")
        val declaration = writeProjectFile(
            "dry-signature-throws-src/preview/ThrowsTarget.java",
            """
            package preview;
            import java.io.IOException;
            class ThrowsTarget {
                String work(String value) throws IOException { return value; }
            }
            """.trimIndent()
        )
        val before = snapshotBytes(declaration)
        val tool = ChangeSignatureTool()

        val previewResult = tool.execute(project, buildJsonObject {
            put("file", "dry-signature-throws-src/preview/ThrowsTarget.java")
            put("line", 4)
            put("column", 12)
            put("newVisibility", "public")
            put("dryRun", true)
        })

        val preview = assertPreview(previewResult, expectedCanApply = true)
        val plannedChange = preview.getValue("plannedChange").jsonObject
        assertEquals("changeSignature", plannedChange.getValue("operation").jsonPrimitive.content)
        assertEquals(
            "package-private",
            plannedChange.getValue("before").jsonObject.getValue("visibility").jsonPrimitive.content
        )
        assertEquals(
            "public",
            plannedChange.getValue("requested").jsonObject.getValue("newVisibility").jsonPrimitive.content
        )
        assertBytesUnchanged(before)
        assertProjectFileVfsContains(
            "dry-signature-throws-src/preview/ThrowsTarget.java",
            "throws IOException"
        )

        val applyResult = tool.execute(project, buildJsonObject {
            put("file", "dry-signature-throws-src/preview/ThrowsTarget.java")
            put("line", 4)
            put("column", 12)
            put("newVisibility", "public")
        })

        assertToolSucceeded("Change Signature apply should preserve existing throws", applyResult)
        assertProjectFileVfsContains(
            "dry-signature-throws-src/preview/ThrowsTarget.java",
            "public String work(String value) throws IOException"
        )
    }

    fun testChangeSignatureDryRunWithNewParameterWithoutDefaultFailsClosed() = runBlocking {
        registerSourceRoot("dry-signature-default-src")
        val declaration = writeProjectFile(
            "dry-signature-default-src/preview/DefaultTarget.java",
            """
            package preview;
            class DefaultTarget { void format(String input) {} }
            """.trimIndent()
        )
        val caller = writeProjectFile(
            "dry-signature-default-src/preview/DefaultCaller.java",
            """
            package preview;
            class DefaultCaller { void call(DefaultTarget target) { target.format("value"); } }
            """.trimIndent()
        )
        val before = snapshotBytes(declaration, caller)

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "dry-signature-default-src/preview/DefaultTarget.java")
            put("line", 2)
            put("column", 35)
            put("newParameters", buildJsonArray {
                add(buildJsonObject {
                    put("oldIndex", 0)
                    put("name", "input")
                    put("type", "String")
                })
                add(buildJsonObject {
                    put("oldIndex", -1)
                    put("name", "required")
                    put("type", "boolean")
                })
            })
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertPreviewWarningContains(preview, "no explicit non-blank defaultValue")
        assertBytesUnchanged(before)
    }

    fun testChangeSignatureDryRunWithCovariantOverriderFailsClosed() = runBlocking {
        registerSourceRoot("dry-signature-overrider-src")
        val source = writeProjectFile(
            "dry-signature-overrider-src/preview/SignatureInheritance.java",
            """
            package preview;
            class BaseSignature {
                CharSequence value() { return "base"; }
            }
            class ChildSignature extends BaseSignature {
                @Override String value() { return "child"; }
            }
            """.trimIndent()
        )
        val before = snapshotBytes(source)

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("file", "dry-signature-overrider-src/preview/SignatureInheritance.java")
            put("line", 3)
            put("column", 18)
            put("newReturnType", "Object")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertPreviewWarningContains(preview, "covariant-overrider choice")
        assertBytesUnchanged(before)
    }

    fun testDryRunDoesNotSaveAnUnrelatedDirtyDocument() = runBlocking {
        registerSourceRoot("dry-dirty-src")
        val target = writeProjectFile(
            "dry-dirty-src/preview/DirtyTarget.java",
            """
            package preview;
            class DirtyTarget { void work() {} }
            """.trimIndent()
        )
        val unrelated = writeProjectFile(
            "dry-dirty-src/preview/Unrelated.java",
            """
            package preview;
            class Unrelated {}
            """.trimIndent()
        )
        val targetBefore = snapshotBytes(target)
        val originalDiskText = Files.readString(unrelated)
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath(unrelated.toString())
        )
        val document = ReadAction.compute<com.intellij.openapi.editor.Document, RuntimeException> {
            requireNotNull(FileDocumentManager.getInstance().getDocument(virtualFile))
        }
        val dirtyText = "$originalDiskText\n// unsaved editor text"
        val settings = McpSettings.getInstance()
        val originalSyncSetting = settings.syncExternalChanges

        try {
            settings.syncExternalChanges = true
            WriteAction.runAndWait<Throwable> { document.setText(dirtyText) }
            assertTrue("Precondition: unrelated document must be dirty", FileDocumentManager.getInstance().isFileModified(virtualFile))

            val result = RenameSymbolTool().execute(project, buildJsonObject {
                put("file", "dry-dirty-src/preview/DirtyTarget.java")
                put("line", 2)
                put("column", 26)
                put("newName", "renamedWork")
                put("dryRun", true)
            })

            assertPreview(result, expectedCanApply = true)
            assertEquals("Dry-run must retain unrelated in-memory text", dirtyText, document.text)
            assertTrue("Dry-run must not save unrelated documents", FileDocumentManager.getInstance().isFileModified(virtualFile))
            assertEquals("Dry-run must not write unrelated document to disk", originalDiskText, Files.readString(unrelated))
            assertBytesUnchanged(targetBefore)
        } finally {
            settings.syncExternalChanges = originalSyncSetting
            WriteAction.runAndWait<Throwable> { document.setText(originalDiskText) }
            ApplicationManager.getApplication().invokeAndWait {
                FileDocumentManager.getInstance().saveDocument(document)
            }
        }
    }

    fun testRenameDryRunRefreshesExternalUsageWhenAutoSyncEnabled() = runBlocking {
        registerSourceRoot("dry-external-sync-src")
        val target = writeProjectFile(
            "dry-external-sync-src/preview/ExternalSyncTarget.java",
            """
            package preview;
            class ExternalSyncTarget { void work() {} }
            """.trimIndent()
        )
        val caller = writeProjectFile(
            "dry-external-sync-src/preview/ExternalSyncCaller.java",
            """
            package preview;
            class ExternalSyncCaller { void call() {} }
            """.trimIndent()
        )
        val targetBefore = snapshotBytes(target)
        val settings = McpSettings.getInstance()
        val originalSyncSetting = settings.syncExternalChanges
        val tool = RenameSymbolTool().also {
            it.processorRunHook = { error("RenameProcessor.run() must not be called by dry-run") }
        }
        fun arguments() = buildJsonObject {
            put("file", "dry-external-sync-src/preview/ExternalSyncTarget.java")
            put("line", 2)
            put("column", 37)
            put("newName", "run")
            put("dryRun", true)
        }

        try {
            settings.syncExternalChanges = false
            val baseline = assertPreview(tool.execute(project, arguments()), expectedCanApply = true)
            assertEquals(0, baseline.getValue("usageCount").jsonPrimitive.int)

            Files.writeString(
                caller,
                """
                package preview;
                class ExternalSyncCaller {
                    void call() { new ExternalSyncTarget().work(); }
                }
                """.trimIndent()
            )
            val externallyWrittenBytes = Files.readAllBytes(caller)
            settings.syncExternalChanges = true

            val refreshed = assertPreview(tool.execute(project, arguments()), expectedCanApply = true)
            assertTrue(refreshed.getValue("usageCount").jsonPrimitive.int >= 1)
            assertTrue(
                refreshed.getValue("affectedFiles").jsonArray
                    .map { it.jsonPrimitive.content }
                    .contains("dry-external-sync-src/preview/ExternalSyncCaller.java")
            )
            assertTrue(
                "Dry-run must not rewrite the externally changed caller",
                externallyWrittenBytes.contentEquals(Files.readAllBytes(caller))
            )
            assertBytesUnchanged(targetBefore)
        } finally {
            settings.syncExternalChanges = originalSyncSetting
        }
    }

    fun testDryRunFailsClosedWhenUsageDiscoveryBreaks() = runBlocking {
        val file = writeProjectFile(
            "dry-fail-closed/FailureTarget.java",
            """
            class FailureTarget {
                void work() {}
            }
            """.trimIndent()
        )
        val before = snapshotBytes(file)
        val tool = RenameSymbolTool().also {
            it.previewUsageSearchHook = { error("simulated index failure") }
            it.processorRunHook = { error("RenameProcessor.run() must not be called by dry-run") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-fail-closed/FailureTarget.java")
            put("line", 2)
            put("column", 10)
            put("newName", "renamedWork")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        val warnings = preview.getValue("warnings").jsonArray.joinToString { it.jsonPrimitive.content }
        assertTrue(warnings.contains("simulated index failure"))
        assertBytesUnchanged(before)
    }

    fun testRenameBaseRethrowsProcessCancellationFromReflectiveDeepSuperLookup() = runBlocking {
        val file = writeProjectFile(
            "rename-base-cancel/Child.java",
            """
            class Base { void work() {} }
            class Child extends Base { @Override void work() {} }
            """.trimIndent()
        )
        val before = snapshotBytes(file)
        val tool = RenameSymbolTool().also {
            it.deepestSuperMethodResolutionHook = {
                throw InvocationTargetException(ProcessCanceledException())
            }
        }

        val thrown = runCatching {
            tool.execute(project, buildJsonObject {
                put("file", "rename-base-cancel/Child.java")
                put("line", 2)
                put("column", 44)
                put("newName", "renamedWork")
                put("dryRun", true)
            })
        }.exceptionOrNull()

        assertTrue("rename_base must propagate cancellation, got: $thrown", thrown is ProcessCanceledException)
        assertBytesUnchanged(before)
    }

    fun testSafeDeleteDryRunFailsClosedWhenUsageDiscoveryBreaks() = runBlocking {
        val file = writeProjectFile(
            "dry-delete-fail-closed/DeleteFailureTarget.java",
            """
            class DeleteFailureTarget {
                void doomed() {}
            }
            """.trimIndent()
        )
        val before = snapshotBytes(file)
        val tool = SafeDeleteTool().also {
            it.usageSearchHook = { error("simulated delete search failure") }
            it.beforeDeletionHook = { error("Deletion write phase must not be reached by dry-run") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-delete-fail-closed/DeleteFailureTarget.java")
            put("line", 2)
            put("column", 10)
            put("force", true)
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertPreviewWarningContains(preview, "simulated delete search failure")
        assertBytesUnchanged(before)
    }

    fun testFileSafeDeleteDryRunFailsClosedWhenUsageDiscoveryBreaks() = runBlocking {
        registerSourceRoot("dry-file-delete-fail-src")
        val target = writeProjectFile(
            "dry-file-delete-fail-src/preview/FailedFileDelete.java",
            """
            package preview;
            class FailedFileDelete {}
            """.trimIndent()
        )
        val before = snapshotBytes(target)
        val tool = SafeDeleteTool().also {
            it.usageSearchHook = { error("simulated file delete search failure") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-file-delete-fail-src/preview/FailedFileDelete.java")
            put("target_type", "file")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertPreviewWarningContains(preview, "simulated file delete search failure")
        assertProjectFileExists("dry-file-delete-fail-src/preview/FailedFileDelete.java")
        assertBytesUnchanged(before)
    }

    fun testChangeSignatureDryRunFailsClosedWhenUsageDiscoveryBreaks() = runBlocking {
        val file = writeProjectFile(
            "dry-signature-fail-closed/SignatureFailureTarget.java",
            """
            class SignatureFailureTarget {
                void work() {}
            }
            """.trimIndent()
        )
        val before = snapshotBytes(file)
        val tool = ChangeSignatureTool().also {
            it.previewUsageSearchHook = { error("simulated signature search failure") }
            it.processorRunHook = { error("ChangeSignatureProcessor.run() must not be called by dry-run") }
        }

        val result = tool.execute(project, buildJsonObject {
            put("file", "dry-signature-fail-closed/SignatureFailureTarget.java")
            put("line", 2)
            put("column", 10)
            put("newName", "renamedWork")
            put("dryRun", true)
        })

        val preview = assertPreview(result, expectedCanApply = false)
        assertPreviewWarningContains(preview, "simulated signature search failure")
        assertBytesUnchanged(before)
    }

    private fun assertPreview(result: CallToolResult, expectedCanApply: Boolean): JsonObject {
        assertToolSucceeded("Dry-run should return a preview payload", result)
        val payload = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue(payload.getValue("dryRun").jsonPrimitive.boolean)
        assertEquals(payload.toString(), expectedCanApply, payload.getValue("canApply").jsonPrimitive.boolean)
        assertTrue(payload.containsKey("target"))
        assertTrue(payload.containsKey("plannedChange"))
        assertTrue(payload.containsKey("affectedFiles"))
        assertTrue(payload.containsKey("usageCount"))
        assertTrue(payload.containsKey("conflictCount"))
        assertTrue(payload.containsKey("warnings"))
        assertTrue(payload.containsKey("elapsedMs"))
        return payload
    }

    private fun assertPreviewWarningContains(preview: JsonObject, expected: String) {
        val warnings = preview.getValue("warnings").jsonArray.joinToString { it.jsonPrimitive.content }
        assertTrue("Expected preview warning containing '$expected', got: $warnings", warnings.contains(expected))
    }

    private data class FileSnapshot(
        val diskBytes: ByteArray,
        val documentText: String
    )

    private fun snapshotBytes(vararg paths: Path): Map<Path, FileSnapshot> =
        paths.associateWith { path ->
            FileSnapshot(
                diskBytes = Files.readAllBytes(path),
                documentText = readProjectFileVfsUnderReadAction(path)
            )
        }

    private fun assertBytesUnchanged(before: Map<Path, FileSnapshot>) {
        for ((path, expected) in before) {
            assertTrue(
                "Dry-run changed bytes in $path",
                expected.diskBytes.contentEquals(Files.readAllBytes(path))
            )
            assertEquals(
                "Dry-run changed in-memory Document/VFS text in $path",
                expected.documentText,
                readProjectFileVfsUnderReadAction(path)
            )
        }
    }

    private fun readProjectFileVfsUnderReadAction(path: Path): String =
        ReadAction.compute<String, RuntimeException> {
            readProjectFileVfs(relativeProjectPath(path))
        }

    private fun assertProjectFileVfsContains(relativePath: String, expected: String) {
        val text = ReadAction.compute<String, RuntimeException> { readProjectFileVfs(relativePath) }
        assertTrue("Expected $relativePath to contain '$expected'", text.contains(expected))
    }

    private fun assertProjectFileVfsDoesNotContain(relativePath: String, unexpected: String) {
        val text = ReadAction.compute<String, RuntimeException> { readProjectFileVfs(relativePath) }
        assertFalse("Expected $relativePath not to contain '$unexpected'", text.contains(unexpected))
    }

    private fun relativeProjectPath(path: Path): String =
        Path.of(requireNotNull(project.basePath)).relativize(path).toString()
}
