package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.ConflictsDialogBase
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * Forces IntelliJ's automatic renamers to apply without opening modal UI.
 */
internal class HeadlessRenameProcessor(
    project: Project,
    private val previewElement: PsiElement,
    private val previewNewName: String,
    searchInComments: Boolean,
    searchTextOccurrences: Boolean,
    private val trackAutomaticRenameCandidates: Boolean = false
) : RenameProcessor(project, previewElement, previewNewName, searchInComments, searchTextOccurrences) {

    /**
     * Recorded while the platform creates renamers in [findUsages].  Candidate generation can
     * be thread-affine for a language plugin, so it must happen only in that normal platform
     * flow, never in a second best-effort pass after the usage search.
     */
    @Volatile
    private var hasRecordedAutomaticRenameCandidates = false

    private var implicitFactoriesTrackedForPreview = false

    /**
     * Sanitized conflict messages collected instead of showing the conflicts dialog.
     * Surfaced by [RenameSymbolTool] as result warnings.
     */
    val capturedConflicts = mutableListOf<String>()

    override fun addRenamerFactory(factory: AutomaticRenamerFactory) {
        super.addRenamerFactory(
            if (trackAutomaticRenameCandidates) TrackingAutomaticRenamerFactory(factory) else factory
        )
    }

    /**
     * Mirrors the non-mutating first step of [RenameProcessor.doRun].  It is intentionally
     * separate from [findUsages], because the platform's public findUsages method does not call
     * prepareRenaming itself.
     *
     * Call this from the EDT without an enclosing read action, matching [RenameProcessor.doRun]:
     * language processors may run synchronous progress that temporarily yields the EDT, which is
     * forbidden while a caller-held read lock is active. A processor that cannot prepare its
     * complete rename map must make the caller fail the preview closed rather than report a
     * partial plan.
     */
    fun preparePreviewRenaming() {
        prepareRenaming(previewElement, previewNewName, myAllRenames)
        if (trackAutomaticRenameCandidates && !implicitFactoriesTrackedForPreview) {
            // RenameProcessor evaluates null-option extension factories implicitly. Add tracking
            // delegates before findUsages so the preview observes the same call and usage list.
            // The platform will also invoke the original implicit factory; this is read-only and
            // avoids relying on its private renamer list.
            AutomaticRenamerFactory.EP_NAME.extensionList
                .filter { it.optionName == null }
                .forEach { super.addRenamerFactory(TrackingAutomaticRenamerFactory(it)) }
            implicitFactoriesTrackedForPreview = true
        }
    }

    /**
     * Automatic renamers are finalized in preprocessUsages(), which may display a chooser.
     * The dry-run does not call that method, so a renamer that actually found related elements
     * makes its complete affected set impossible to promise. Merely applicable factories are not
     * enough: several platform factories apply broadly but produce an empty renamer for ordinary
     * declarations.
     *
     * This deliberately uses only public APIs. [TrackingAutomaticRenamerFactory] records the
     * result while [RenameProcessor.findUsages] invokes its normal configured factories with
     * the platform-selected element and per-element usages. No factory code is run here.
     */
    fun hasPotentialAutomaticRenames(): Boolean = hasRecordedAutomaticRenameCandidates

    private inner class TrackingAutomaticRenamerFactory(
        private val delegate: AutomaticRenamerFactory
    ) : AutomaticRenamerFactory {
        override fun isApplicable(element: PsiElement): Boolean = delegate.isApplicable(element)

        override fun getOptionName(): String? = delegate.optionName

        override fun isEnabled(): Boolean = delegate.isEnabled

        override fun setEnabled(enabled: Boolean) {
            delegate.isEnabled = enabled
        }

        override fun createRenamer(
            element: PsiElement,
            newName: String,
            usages: Collection<UsageInfo>
        ): AutomaticRenamer {
            return delegate.createRenamer(element, newName, usages).also { renamer ->
                hasRecordedAutomaticRenameCandidates =
                    hasRecordedAutomaticRenameCandidates || renamer.hasAnythingToRename()
            }
        }
    }

    /**
     * Collects the same non-UI rename conflicts used by the processor without calling
     * `preprocessUsages()` or `run()`. The former can open language-specific dialogs and the
     * latter mutates PSI, neither of which is permitted during a dry-run.
     */
    fun collectPreviewConflicts(usages: Array<UsageInfo>): List<String> {
        val conflicts = MultiMap<PsiElement, String>()
        RenameUtil.addConflictDescriptions(usages, conflicts)

        val allRenames = linkedMapOf<PsiElement, String>()
        for (element in elements) {
            allRenames[element] = getNewName(element)
        }
        allRenames.putIfAbsent(previewElement, previewNewName)

        // RenameProcessor's file-existence check is part of its apply path and may display UI or
        // perform VFS work. A preview needs the same fail-closed signal without entering that
        // path, so inspect only the current directory PSI under the caller's read action.
        for ((element, newName) in allRenames) {
            val file = element as? PsiFile ?: continue
            val existingSibling = file.containingDirectory?.findFile(newName)
            if (existingSibling != null && existingSibling != file) {
                conflicts.putValue(
                    file,
                    "Cannot rename '${file.name}' to '$newName': a file with that name already exists in its containing directory."
                )
            }
        }

        for ((element, newName) in allRenames) {
            RenamePsiElementProcessor.forElement(element)
                .findExistingNameConflicts(element, newName, conflicts, allRenames)
        }
        return ConflictMessages.sanitizeAll(conflicts.values())
    }

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer): Boolean {
        for (element in automaticVariableRenamer.elements) {
            val suggestedName = automaticVariableRenamer.getNewName(element) ?: continue
            val namedElement = element as? PsiNamedElement ?: continue
            automaticVariableRenamer.setRename(namedElement, suggestedName)
        }
        return true
    }

    /**
     * `RenameProcessor.preprocessUsages` builds the conflicts dialog directly through this
     * method (it never routes through the overridable `showConflicts`), and on cancel the
     * whole refactoring silently aborts. Returning a stub that always proceeds keeps the
     * rename headless — matching [showAutomaticRenamingDialog] and `HeadlessMoveProcessor` —
     * while the sanitized conflicts are captured for the tool's result warnings.
     *
     * Public (base is protected) so tests can exercise it: in unit-test mode
     * `preprocessUsages` throws `ConflictsInTestsException` before ever reaching this path.
     */
    public override fun prepareConflictsDialog(
        conflicts: MultiMap<PsiElement, String>,
        usages: Array<out UsageInfo>?
    ): ConflictsDialogBase {
        capturedConflicts.addAll(ConflictMessages.sanitizeAll(conflicts.values()))
        return object : ConflictsDialogBase {
            override fun setCommandName(commandName: String?) {}
            override fun showAndGet(): Boolean = true
            override fun isShowConflicts(): Boolean = false
        }
    }
}
