package com.owlacoustics.domain.importing

import com.owlacoustics.domain.edit.EditResult
import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.allocId
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.CalibrationRef
import com.owlacoustics.domain.model.ImportCalibration
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.ScaleBasis
import com.owlacoustics.domain.model.ScaleKind
import com.owlacoustics.domain.units.EnteredValue
import kotlin.math.abs

/** Floor-plan import calibration (C-23): known-length references define a pixel → metre similarity transform. */
object Calibration {
    /** Relative disagreement above which two references are shown as a scale conflict (display threshold, ENG). */
    const val CONFLICT_RATIO = 0.01

    fun metersPerPixel(ref: CalibrationRef): Double? {
        val px = ref.pixelA.distanceTo(ref.pixelB)
        if (px < 1.0) return null
        val m = ref.known.meters
        if (!m.isFinite() || m <= 0) return null
        return m / px
    }

    /** Source pixel (y down) → room metres (y up), origin at the image's bottom-left. */
    fun toRoom(pixel: Vec2, mpp: Double, imageHeightPx: Double) = Vec2(pixel.x * mpp, (imageHeightPx - pixel.y) * mpp)

    fun toPixel(room: Vec2, mpp: Double, imageHeightPx: Double) = Vec2(room.x / mpp, imageHeightPx - room.y / mpp)

    data class Conflict(val a: String, val b: String, val ratio: Double)

    fun conflicts(c: ImportCalibration): List<Conflict> {
        val out = ArrayList<Conflict>()
        val vals = c.references.mapNotNull { r -> metersPerPixel(r)?.let { r.id to it } }
        for (i in vals.indices) for (j in i + 1 until vals.size) {
            val ratio = abs(vals[i].second / vals[j].second - 1)
            if (ratio > CONFLICT_RATIO) out += Conflict(vals[i].first, vals[j].first, ratio)
        }
        return out
    }

    fun startImport(m: RoomModel, mediaId: String, page: Int): EditResult {
        val cal = ImportCalibration(mediaId, page)
        return Edits.finish(m, m.copy(importCalibration = cal, scale = ScaleBasis(ScaleKind.UNKNOWN, "Imported plan not yet calibrated")), "Import floor plan")
    }

    /** Adds a reference. The first becomes active; later ones are compared and never silently applied. */
    fun addReference(m: RoomModel, a: Vec2, b: Vec2, known: EnteredValue): EditResult {
        val cal = m.importCalibration ?: return EditResult.Rejected("Import a plan first.")
        val (mm, id) = m.allocId("ref")
        val ref = CalibrationRef(id, a, b, known)
        val mpp = metersPerPixel(ref) ?: return EditResult.Rejected("Mark two distinct points and a positive length.")
        val first = cal.activeReferenceId == null
        val nc = cal.copy(references = cal.references + ref, activeReferenceId = if (first) id else cal.activeReferenceId,
            metersPerPixel = if (first) mpp else cal.metersPerPixel)
        val scale = if (first) ScaleBasis(ScaleKind.IMPORT_CALIBRATED, "Known length ${known.text}", id) else mm.scale
        // traced geometry (if any) is rescaled when the first reference arrives after tracing
        val rescaled = if (first && cal.metersPerPixel != null) rescale(mm, cal.metersPerPixel, mpp) else mm
        return Edits.finish(m, rescaled.copy(importCalibration = nc, scale = scale), if (first) "Calibrate plan" else "Add check reference")
    }

    /** Switches the active reference: an undoable correction that rescales traced geometry and invalidates results. */
    fun recalibrate(m: RoomModel, refId: String): EditResult {
        val cal = m.importCalibration ?: return EditResult.Rejected("No imported plan.")
        val ref = cal.references.firstOrNull { it.id == refId } ?: return EditResult.Rejected("Reference not found.")
        val mpp = metersPerPixel(ref) ?: return EditResult.Rejected("Reference is invalid.")
        val old = cal.metersPerPixel ?: return EditResult.Rejected("Plan has no scale yet.")
        val mm = rescale(m, old, mpp)
        return Edits.finish(m, mm.copy(importCalibration = cal.copy(metersPerPixel = mpp, activeReferenceId = refId),
            scale = ScaleBasis(ScaleKind.IMPORT_CALIBRATED, "Known length ${ref.known.text}", refId)), "Recalibrate plan")
    }

    private fun rescale(m: RoomModel, oldMpp: Double, newMpp: Double): RoomModel {
        val k = newMpp / oldMpp
        return m.copy(
            vertices = m.vertices.map { it.copy(x = it.x * k, y = it.y * k) },
            openings = m.openings.map { it.copy(offset = it.offset * k, width = it.width * k) },
            setup = m.setup.copy(items = m.setup.items.map { i -> i.copy(position = i.position?.let { it * k }) }),
            entries = emptyMap(),
        )
    }

    /** Replaces the outline with corners traced over the source (assisted/manual import). */
    fun traceOutline(m: RoomModel, pixels: List<Vec2>, imageHeightPx: Double): EditResult {
        val cal = m.importCalibration ?: return EditResult.Rejected("Import a plan first.")
        val mpp = cal.metersPerPixel ?: return EditResult.Rejected("Calibrate the plan with a known length first.")
        val pts = pixels.map { toRoom(it, mpp, imageHeightPx) }
        return Edits.replaceOutline(m, pts, Provenance.USER_ENTERED, "Trace room outline")
    }
}
