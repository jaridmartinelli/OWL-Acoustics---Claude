package com.owlacoustics.domain.workflow

import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.ceiling.CeilingSupport
import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.model.Issue
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.Severity
import com.owlacoustics.domain.model.Validation

/** Distinct workflow states (§2, C-31); derived from the model, never stored loosely. */
data class Stages(
    val planReviewed: Boolean,
    val ceilingSupported: Boolean,
    val setupReady: Boolean,
    val planBlocking: List<Issue>,
    val planReview: List<Issue>,
    val setupMissing: List<String>,
)

object Workflow {
    fun stages(m: RoomModel): Stages {
        val blocking = Validation.blocking(m)
        val review = Validation.reviewItems(m)
        val planBlocking = blocking + review.filter { it.severity == Severity.BLOCKING }
        val ceiling = CeilingGeometry.support(m.outline, m.ceiling) is CeilingSupport.Defined
        val missing = ArrayList<String>()
        if (m.setup.purpose == null) missing += "purpose"
        if (m.setup.arrangement == null) missing += "setup arrangement"
        if (m.setup.items.isEmpty() && m.setup.arrangement != null) missing += "at least one source and receiver"
        val unplaced = m.setup.items.filter { it.position == null }
        if (unplaced.isNotEmpty()) missing += "positions for " + unplaced.joinToString { it.label }
        return Stages(m.planConfirmed && planBlocking.isEmpty(), ceiling, missing.isEmpty(), planBlocking, review.filter { it.severity == Severity.REVIEW }, missing)
    }

    /** Confirms the drawing (not surveyed accuracy). Requires a valid closed outline with a scale basis; no ceiling needed. */
    fun confirmPlan(m: RoomModel): EditResult {
        val s = stages(m)
        if (s.planBlocking.isNotEmpty()) return EditResult.Rejected(s.planBlocking.first().message)
        return Edits.finish(m, m.copy(planConfirmed = true, reviewedIssues = s.planReview.map { it.code }), "Confirm floor plan")
    }

    fun setFinalized(m: RoomModel, finalized: Boolean): EditResult =
        Edits.finish(m, m.copy(finalized = finalized), if (finalized) "Finalize room" else "Reopen for editing")
}
