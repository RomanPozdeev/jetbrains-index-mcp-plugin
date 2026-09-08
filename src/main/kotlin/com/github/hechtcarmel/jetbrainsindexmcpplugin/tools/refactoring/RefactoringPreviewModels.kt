package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ResolvedSymbolInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Common wire shape returned by refactoring tools when `dryRun=true`.
 *
 * [plannedChange] deliberately remains a JSON object: rename, safe delete, and change
 * signature have materially different plans, while the safety/discovery metadata is shared.
 */
@Serializable
data class RefactoringPreviewResult(
    val dryRun: Boolean = true,
    val canApply: Boolean,
    val target: ResolvedSymbolInfo,
    val plannedChange: JsonObject,
    val affectedFiles: List<String>,
    val usageCount: Int,
    val conflictCount: Int,
    val warnings: List<String>,
    val elapsedMs: Long
)

internal fun elapsedMillisSince(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000
