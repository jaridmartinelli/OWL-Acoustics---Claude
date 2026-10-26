package com.owlacoustics.domain.edit

import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.ceiling.CeilingSupport
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.CeilingDetail
import com.owlacoustics.domain.model.CeilingDetailKind
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.Measure
import com.owlacoustics.domain.model.RoomModel

object CeilingEdits {
    /** Stores the profile as entered; unknown inputs stay unknown (no default height, symmetry or curve). */
    fun setProfile(m: RoomModel, p: CeilingProfile): EditResult {
        val heights = measures(p)
        if (heights.any { it.meters < 0 }) return EditResult.Rejected("Heights and distances cannot be negative.")
        val changed = p != m.ceiling.profile
        return Edits.finish(m, m.copy(ceiling = m.ceiling.copy(profile = p, confirmed = if (changed) false else m.ceiling.confirmed)), "Edit ceiling")
    }

    private fun measures(p: CeilingProfile): List<Measure> = when (p) {
        is CeilingProfile.Flat -> listOfNotNull(p.height)
        is CeilingProfile.SingleSlope -> listOfNotNull(p.low, p.high)
        is CeilingProfile.Peaked -> listOfNotNull(p.ridgeOffset, p.ridgeHeight, p.eaveA, p.eaveB)
        is CeilingProfile.FlatCenterVault -> listOfNotNull(p.plateauOffset, p.plateauWidth, p.plateauHeight, p.eaveA, p.eaveB)
        is CeilingProfile.Arched -> listOfNotNull(p.springHeight, p.crownHeight)
        is CeilingProfile.Tray -> listOfNotNull(p.baseHeight, p.raisedHeight, p.inset, p.slopedRun)
        else -> emptyList()
    }

    fun addDetail(m: RoomModel, kind: CeilingDetailKind, center: Vec2, along: Double, across: Double, bearing: Double, vertical: Measure?): EditResult {
        if (along <= 0 || across <= 0) return EditResult.Rejected("Detail size must be positive.")
        val (mm, id) = m.allocId("cd")
        val d = CeilingDetail(id, kind, center, along, across, bearing, vertical)
        if (CeilingGeometry.detailsFit(m.outline, listOf(d)).isNotEmpty()) return EditResult.Rejected("The detail must fit inside the room outline.")
        return Edits.finish(m, mm.copy(ceiling = mm.ceiling.copy(details = mm.ceiling.details + d, confirmed = false)), "Add ${kind.label.lowercase()}")
    }

    fun updateDetail(m: RoomModel, d: CeilingDetail): EditResult {
        if (m.ceiling.details.none { it.id == d.id }) return EditResult.Rejected("Detail not found.")
        if (CeilingGeometry.detailsFit(m.outline, listOf(d)).isNotEmpty()) return EditResult.Rejected("The detail must fit inside the room outline.")
        return Edits.finish(m, m.copy(ceiling = m.ceiling.copy(details = m.ceiling.details.map { if (it.id == d.id) d else it }, confirmed = false)), "Edit ceiling detail")
    }

    fun removeDetail(m: RoomModel, id: String) =
        Edits.finish(m, m.copy(ceiling = m.ceiling.copy(details = m.ceiling.details.filter { it.id != id }, confirmed = false)), "Remove ceiling detail")

    /** "My ceiling dimensions are confirmed" — only for a fully defined profile. */
    fun confirm(m: RoomModel, confirmed: Boolean): EditResult {
        if (confirmed && CeilingGeometry.support(m.outline, m.ceiling) !is CeilingSupport.Defined) {
            return EditResult.Rejected("Enter every required ceiling dimension before confirming.")
        }
        return Edits.finish(m, m.copy(ceiling = m.ceiling.copy(confirmed = confirmed)), if (confirmed) "Confirm ceiling" else "Unconfirm ceiling")
    }
}
