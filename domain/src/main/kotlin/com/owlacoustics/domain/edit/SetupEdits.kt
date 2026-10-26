package com.owlacoustics.domain.edit

import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.Arrangement
import com.owlacoustics.domain.model.ItemKind
import com.owlacoustics.domain.model.KeepOut
import com.owlacoustics.domain.model.Measure
import com.owlacoustics.domain.model.Purpose
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.SetupItem

object SetupEdits {
    /** Arrangements relevant to a purpose; Stereo is offered as the default only for Mixing and Hi-Fi (D-14). */
    fun arrangementsFor(p: Purpose): List<Arrangement> = when (p) {
        Purpose.MIXING, Purpose.HIFI -> listOf(Arrangement.STEREO, Arrangement.MONO, Arrangement.LCR, Arrangement.CUSTOM)
        Purpose.HOME_THEATER -> listOf(Arrangement.SURROUND_5_1, Arrangement.SURROUND_7_1, Arrangement.LCR, Arrangement.STEREO, Arrangement.CUSTOM)
        Purpose.PODCAST -> listOf(Arrangement.PODCAST, Arrangement.TALKER_MIC, Arrangement.CUSTOM)
        Purpose.AUDIOBOOK, Purpose.VOICE_ACTING, Purpose.ASMR -> listOf(Arrangement.TALKER_MIC, Arrangement.CUSTOM)
        Purpose.INSTRUMENT -> listOf(Arrangement.INSTRUMENT_MIC, Arrangement.CUSTOM)
        Purpose.STREAMING -> listOf(Arrangement.TALKER_MIC, Arrangement.STEREO, Arrangement.CUSTOM)
        Purpose.OTHER -> listOf(Arrangement.CUSTOM, Arrangement.TALKER_MIC, Arrangement.STEREO)
    }

    fun defaultArrangement(p: Purpose): Arrangement? = if (p == Purpose.MIXING || p == Purpose.HIFI) Arrangement.STEREO else null

    fun setPurpose(m: RoomModel, p: Purpose, otherUse: String = m.setup.otherUse): EditResult {
        val current = m.setup.arrangement
        val arr = if (current != null && current in arrangementsFor(p)) current else defaultArrangement(p)
        var mm = m.copy(setup = m.setup.copy(purpose = p, otherUse = otherUse))
        if (arr != current) {
            mm = (setArrangement(mm, arr, podcastPairs = 2) as? EditResult.Ok)?.model ?: mm.copy(setup = mm.setup.copy(arrangement = arr))
        }
        return Edits.finish(m, mm, "Choose purpose")
    }

    private data class Spec(val kind: ItemKind, val label: String, val angle: Double? = null, val group: Int = 0)

    private fun specs(a: Arrangement, pairs: Int): List<Spec> = when (a) {
        Arrangement.STEREO -> listOf(Spec(ItemKind.SPEAKER, "L", 30.0), Spec(ItemKind.SPEAKER, "R", -30.0), Spec(ItemKind.LISTENER, "Listener"))
        Arrangement.MONO -> listOf(Spec(ItemKind.SPEAKER, "Speaker", 0.0), Spec(ItemKind.LISTENER, "Listener"))
        Arrangement.LCR -> listOf(Spec(ItemKind.SPEAKER, "L", 30.0), Spec(ItemKind.SPEAKER, "C", 0.0), Spec(ItemKind.SPEAKER, "R", -30.0), Spec(ItemKind.LISTENER, "Listener"))
        // ITU-R BS.775 nominal angles
        Arrangement.SURROUND_5_1 -> listOf(
            Spec(ItemKind.SPEAKER, "L", 30.0), Spec(ItemKind.SPEAKER, "C", 0.0), Spec(ItemKind.SPEAKER, "R", -30.0),
            Spec(ItemKind.SPEAKER, "SL", 110.0), Spec(ItemKind.SPEAKER, "SR", -110.0), Spec(ItemKind.SUBWOOFER, "LFE"), Spec(ItemKind.LISTENER, "Listener"),
        )
        // ITU-R BS.2051 nominal angles within its ranges
        Arrangement.SURROUND_7_1 -> listOf(
            Spec(ItemKind.SPEAKER, "L", 30.0), Spec(ItemKind.SPEAKER, "C", 0.0), Spec(ItemKind.SPEAKER, "R", -30.0),
            Spec(ItemKind.SPEAKER, "SL", 90.0), Spec(ItemKind.SPEAKER, "SR", -90.0), Spec(ItemKind.SPEAKER, "BL", 150.0), Spec(ItemKind.SPEAKER, "BR", -150.0),
            Spec(ItemKind.SUBWOOFER, "LFE"), Spec(ItemKind.LISTENER, "Listener"),
        )
        Arrangement.TALKER_MIC -> listOf(Spec(ItemKind.TALKER, "Voice", group = 1), Spec(ItemKind.MICROPHONE, "Mic", group = 1))
        Arrangement.PODCAST -> (1..pairs.coerceIn(1, 8)).flatMap {
            listOf(Spec(ItemKind.TALKER, "Talker $it", group = it), Spec(ItemKind.MICROPHONE, "Mic $it", group = it))
        }
        Arrangement.INSTRUMENT_MIC -> listOf(Spec(ItemKind.INSTRUMENT, "Instrument", group = 1), Spec(ItemKind.MICROPHONE, "Mic", group = 1))
        Arrangement.CUSTOM -> emptyList()
    }

    /** Rebuilds items for the arrangement, keeping positions/heights/locks of items with the same label and kind. */
    fun setArrangement(m: RoomModel, a: Arrangement?, podcastPairs: Int = 2): EditResult {
        if (a == null) return Edits.finish(m, m.copy(setup = m.setup.copy(arrangement = null, items = emptyList())), "Clear setup")
        var mm = m
        val old = m.setup.items
        val items = specs(a, podcastPairs).map { s ->
            val prev = old.firstOrNull { it.kind == s.kind && it.label == s.label }
            if (prev != null) prev.copy(layoutAngleDeg = s.angle, group = s.group)
            else {
                val (m1, id) = mm.allocId("item"); mm = m1
                SetupItem(id, s.kind, s.label, layoutAngleDeg = s.angle, group = s.group)
            }
        }
        val keepCustom = if (a == Arrangement.CUSTOM) old else emptyList()
        return Edits.finish(m, mm.copy(setup = mm.setup.copy(arrangement = a, items = items + keepCustom)), "Choose setup")
    }

    fun addCustomItem(m: RoomModel, kind: ItemKind, label: String, group: Int = 0): EditResult {
        val (mm, id) = m.allocId("item")
        val item = SetupItem(id, kind, label.ifBlank { kind.label }, group = group)
        return Edits.finish(m, mm.copy(setup = mm.setup.copy(items = mm.setup.items + item)), "Add ${kind.label.lowercase()}")
    }

    fun removeItem(m: RoomModel, id: String) =
        Edits.finish(m, m.copy(setup = m.setup.copy(items = m.setup.items.filter { it.id != id })), "Remove item")

    fun moveItem(m: RoomModel, id: String, to: Vec2): EditResult {
        val it0 = m.setup.items.firstOrNull { it.id == id } ?: return EditResult.Rejected("Item not found.")
        if (it0.locked) return EditResult.Rejected("This item is locked in place. Unlock it to move it.")
        if (!Polygons.contains(m.outline, to)) return EditResult.Rejected("Items must be inside the room.")
        return update(m, id, "Move ${it0.label}") { it.copy(position = to) }
    }

    fun setHeight(m: RoomModel, id: String, h: Measure?): EditResult {
        if (h != null && h.meters < 0) return EditResult.Rejected("Height cannot be negative.")
        return update(m, id, "Set height") { it.copy(height = h) }
    }

    fun setFacing(m: RoomModel, id: String, deg: Double?) = update(m, id, "Set facing") { it.copy(facingDeg = deg) }

    fun setLocked(m: RoomModel, id: String, locked: Boolean) =
        update(m, id, if (locked) "Lock item" else "Unlock item") { it.copy(locked = locked) }

    private fun update(m: RoomModel, id: String, label: String, f: (SetupItem) -> SetupItem): EditResult {
        if (m.setup.items.none { it.id == id }) return EditResult.Rejected("Item not found.")
        return Edits.finish(m, m.copy(setup = m.setup.copy(items = m.setup.items.map { if (it.id == id) f(it) else it })), label)
    }

    fun addKeepOut(m: RoomModel, center: Vec2, sx: Double, sy: Double, label: String): EditResult {
        if (sx <= 0 || sy <= 0) return EditResult.Rejected("Size must be positive.")
        val (mm, id) = m.allocId("keep")
        val k = KeepOut(id, center, sx, sy, label)
        return Edits.finish(m, mm.copy(setup = mm.setup.copy(keepOuts = mm.setup.keepOuts + k)), "Add keep-clear area")
    }

    fun removeKeepOut(m: RoomModel, id: String) =
        Edits.finish(m, m.copy(setup = m.setup.copy(keepOuts = m.setup.keepOuts.filter { it.id != id })), "Remove keep-clear area")

    /** Applies an accepted placement proposal as one meaningful undoable edit (D-19). */
    fun applyPlacement(m: RoomModel, placements: Map<String, Pair<Vec2, Double?>>): EditResult {
        val items = m.setup.items.map { item ->
            val p = placements[item.id] ?: return@map item
            if (item.locked) return EditResult.Rejected("Proposal would move a locked item.")
            item.copy(position = p.first, facingDeg = p.second ?: item.facingDeg)
        }
        return Edits.finish(m, m.copy(setup = m.setup.copy(items = items)), "Apply placement proposal")
    }
}
