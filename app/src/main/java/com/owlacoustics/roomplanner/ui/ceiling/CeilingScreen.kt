package com.owlacoustics.roomplanner.ui.ceiling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.ceiling.CeilingGeometry
import com.owlacoustics.domain.ceiling.CeilingSupport
import com.owlacoustics.domain.edit.CeilingEdits
import com.owlacoustics.domain.geometry.Polygons
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.mesh.MeshBuilder
import com.owlacoustics.domain.model.ArchCurve
import com.owlacoustics.domain.model.CeilingDetailKind
import com.owlacoustics.domain.model.CeilingProfile
import com.owlacoustics.domain.model.ProfileSample
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.TrayTransition
import com.owlacoustics.roomplanner.ui.RoomViewModel
import com.owlacoustics.roomplanner.ui.common.DegreesField
import com.owlacoustics.roomplanner.ui.common.MeasureField
import com.owlacoustics.roomplanner.ui.common.Note
import com.owlacoustics.roomplanner.ui.common.SectionTitle
import com.owlacoustics.roomplanner.ui.common.fmt
import com.owlacoustics.roomplanner.ui.plan.Sel

private enum class Shape(val label: String) {
    FLAT("Flat"), SLOPE("Single slope"), PEAKED("Peaked / vaulted / cathedral"), FLAT_CENTER("Vaulted with flat center"),
    ARCHED("Arched"), TRAY("Tray / raised center"), SPECIAL("Other / special / mixed"),
}

private fun shapeOf(p: CeilingProfile) = when (p) {
    is CeilingProfile.Flat -> Shape.FLAT
    is CeilingProfile.SingleSlope -> Shape.SLOPE
    is CeilingProfile.Peaked -> Shape.PEAKED
    is CeilingProfile.FlatCenterVault -> Shape.FLAT_CENTER
    is CeilingProfile.Arched -> Shape.ARCHED
    is CeilingProfile.Tray -> Shape.TRAY
    is CeilingProfile.Special -> Shape.SPECIAL
    CeilingProfile.Unset -> null
}

private fun empty(s: Shape): CeilingProfile = when (s) {
    Shape.FLAT -> CeilingProfile.Flat()
    Shape.SLOPE -> CeilingProfile.SingleSlope()
    Shape.PEAKED -> CeilingProfile.Peaked()
    Shape.FLAT_CENTER -> CeilingProfile.FlatCenterVault()
    Shape.ARCHED -> CeilingProfile.Arched()
    Shape.TRAY -> CeilingProfile.Tray()
    Shape.SPECIAL -> CeilingProfile.Special()
}

/** Illustrated section profile for each primary shape (decorative cross-section, not the room's dimensions). */
@Composable
private fun ShapeIcon(s: Shape, color: Color) {
    Canvas(Modifier.size(width = 72.dp, height = 40.dp)) {
        val w = size.width; val h = size.height
        val p = Path()
        fun pt(x: Float, y: Float) = Offset(x * w, h - y * h)
        val pts = when (s) {
            Shape.FLAT -> listOf(pt(0f, 0f), pt(0f, .7f), pt(1f, .7f), pt(1f, 0f))
            Shape.SLOPE -> listOf(pt(0f, 0f), pt(0f, .45f), pt(1f, .9f), pt(1f, 0f))
            Shape.PEAKED -> listOf(pt(0f, 0f), pt(0f, .5f), pt(.5f, .95f), pt(1f, .5f), pt(1f, 0f))
            Shape.FLAT_CENTER -> listOf(pt(0f, 0f), pt(0f, .5f), pt(.3f, .9f), pt(.7f, .9f), pt(1f, .5f), pt(1f, 0f))
            Shape.ARCHED -> listOf(pt(0f, 0f), pt(0f, .5f)) + (0..12).map { i -> val a = Math.PI * i / 12; pt((0.5 - 0.5 * kotlin.math.cos(a)).toFloat(), (0.5 + 0.42 * kotlin.math.sin(a)).toFloat()) } + listOf(pt(1f, 0f))
            Shape.TRAY -> listOf(pt(0f, 0f), pt(0f, .6f), pt(.25f, .6f), pt(.25f, .85f), pt(.75f, .85f), pt(.75f, .6f), pt(1f, .6f), pt(1f, 0f))
            Shape.SPECIAL -> listOf(pt(0f, 0f), pt(0f, .5f), pt(.3f, .8f), pt(.45f, .55f), pt(.7f, .9f), pt(1f, .6f), pt(1f, 0f))
        }
        pts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
        drawPath(p, color, style = Stroke(4f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CeilingScreen(vm: RoomViewModel, m: RoomModel, onCorrectInPlan: (Sel) -> Unit, onExpertHelp: () -> Unit) {
    val u = m.units
    val support = CeilingGeometry.support(m.outline, m.ceiling)
    val mesh = remember(m) { MeshBuilder.build(m) }
    val current = shapeOf(m.ceiling.profile)
    fun set(p: CeilingProfile) = vm.edit { CeilingEdits.setProfile(it, p) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (!m.planConfirmed) Note("Tip: confirm the floor plan first. You can still describe the ceiling now.")
        SectionTitle("Ceiling shape")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in Shape.values()) {
                val selected = current == s
                Card(Modifier.width(150.dp)
                    .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)
                    .clickable { if (!selected) set(empty(s)) }
                    .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = s.label }
                    .testTag("ceiling-${s.name}")) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ShapeIcon(s, MaterialTheme.colorScheme.onSurface)
                        Text((if (selected) "✓ " else "") + s.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        val span = { axis: Double? -> axis?.let { com.owlacoustics.domain.ceiling.ProfileFrame.of(m.outline, it).span } }
        when (val p = m.ceiling.profile) {
            is CeilingProfile.Flat -> MeasureField("Ceiling height above floor", p.height, u) { set(p.copy(height = it)) }
            is CeilingProfile.SingleSlope -> {
                DegreesField("Direction the ceiling rises (degrees, 0 = along wall 1)", p.bearingDeg) { set(p.copy(bearingDeg = it)) }
                MeasureField("Height on the low side", p.low, u) { set(p.copy(low = it)) }
                MeasureField("Height on the high side", p.high, u) { set(p.copy(high = it)) }
            }
            is CeilingProfile.Peaked -> {
                DegreesField("Ridge direction (degrees)", p.ridgeBearingDeg) { set(p.copy(ridgeBearingDeg = it)) }
                span(p.ridgeBearingDeg)?.let { Note("Room width across the ridge: ${fmt(it, u)}") }
                MeasureField("Ridge position from side A", p.ridgeOffset, u) { set(p.copy(ridgeOffset = it)) }
                MeasureField("Ridge height", p.ridgeHeight, u) { set(p.copy(ridgeHeight = it)) }
                MeasureField("Side A eave height", p.eaveA, u) { set(p.copy(eaveA = it)) }
                MeasureField("Side B eave height", p.eaveB, u) { set(p.copy(eaveB = it)) }
                TextButton({ set(p.copy(eaveB = p.eaveA)) }, enabled = p.eaveA != null) { Text("Side B is the same as side A") }
                Note("Symmetry is never assumed: use the button only if both sides really match.")
            }
            is CeilingProfile.FlatCenterVault -> {
                DegreesField("Ridge direction (degrees)", p.ridgeBearingDeg) { set(p.copy(ridgeBearingDeg = it)) }
                span(p.ridgeBearingDeg)?.let { Note("Room width across the ridge: ${fmt(it, u)}") }
                MeasureField("Flat centre starts from side A", p.plateauOffset, u) { set(p.copy(plateauOffset = it)) }
                MeasureField("Flat centre width", p.plateauWidth, u) { set(p.copy(plateauWidth = it)) }
                MeasureField("Flat centre height", p.plateauHeight, u) { set(p.copy(plateauHeight = it)) }
                MeasureField("Side A eave height", p.eaveA, u) { set(p.copy(eaveA = it)) }
                MeasureField("Side B eave height", p.eaveB, u) { set(p.copy(eaveB = it)) }
            }
            is CeilingProfile.Arched -> ArchFields(p, u) { set(it) }
            is CeilingProfile.Tray -> {
                MeasureField("Base (outer) ceiling height", p.baseHeight, u) { set(p.copy(baseHeight = it)) }
                MeasureField("Raised centre height", p.raisedHeight, u) { set(p.copy(raisedHeight = it)) }
                MeasureField("Inset from the walls", p.inset, u) { set(p.copy(inset = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(p.transition == TrayTransition.VERTICAL, { set(p.copy(transition = TrayTransition.VERTICAL)) }, { Text("Vertical step") })
                    FilterChip(p.transition == TrayTransition.SLOPED, { set(p.copy(transition = TrayTransition.SLOPED)) }, { Text("Sloped step") })
                }
                if (p.transition == TrayTransition.SLOPED) MeasureField("Horizontal run of the slope", p.slopedRun, u) { set(p.copy(slopedRun = it)) }
            }
            is CeilingProfile.Special -> {
                var notes by remember(p) { mutableStateOf(p.notes) }
                OutlinedTextField(notes, { notes = it }, label = { Text("Describe the ceiling") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton({ set(p.copy(notes = notes)) }) { Text("Save description") }
                Note("This ceiling stays an editable draft. Its geometry is not modeled, so ceiling-dependent 3D and reflections stay unavailable. Everything else keeps working, and editing is never payment-gated.")
                OutlinedButton(onExpertHelp) { Text("About expert help") }
            }
            CeilingProfile.Unset -> Note("Choose the shape that matches your ceiling. You will only be asked for the dimensions that shape needs.")
        }
        when (support) {
            is CeilingSupport.Missing -> Text("Still needed: " + support.inputs.joinToString(), color = MaterialTheme.colorScheme.tertiary)
            is CeilingSupport.Defined -> Text("Ceiling model: ${support.description}" + if (support.approximate) " (shown as an approximation)" else "")
            is CeilingSupport.Draft -> Text(support.reason)
        }
        DetailsSection(vm, m)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My ceiling dimensions are confirmed", Modifier.weight(1f))
            Switch(m.ceiling.confirmed, { c -> vm.edit { CeilingEdits.confirm(it, c) } }, modifier = Modifier.testTag("ceiling-confirm"))
        }
        Note("This is separate from confirming the floor plan outline.")
        SectionTitle("3D review")
        Text(if (mesh.partial) "Partial view — missing inputs are listed below." else "All represented surfaces are complete for this model.",
            style = MaterialTheme.typography.bodyMedium)
        Room3DView(mesh, Modifier.fillMaxWidth().height(300.dp).testTag("room-3d")) { ref ->
            if (m.wall(ref) != null) onCorrectInPlan(Sel.Wall(ref))
        }
        Note("Drag to turn the view. Tap a wall to correct it in the 2D plan. A convincing picture is not a measurement.")
        for (miss in mesh.missing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• $miss", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                val openingId = m.openings.firstOrNull { miss.contains(it.id) }?.id
                if (openingId != null) TextButton({ onCorrectInPlan(Sel.Opening(openingId)) }) { Text("Fix in plan") }
            }
        }
    }
}

@Composable
private fun ArchFields(p: CeilingProfile.Arched, u: com.owlacoustics.domain.units.DisplayUnits, set: (CeilingProfile) -> Unit) {
    DegreesField("Arch axis direction (degrees)", p.axisBearingDeg) { set(p.copy(axisBearingDeg = it)) }
    MeasureField("Spring height (where the curve starts)", p.springHeight, u) { set(p.copy(springHeight = it)) }
    Text("Curve type", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in ArchCurve.values()) FilterChip(p.curve == c, { set(p.copy(curve = c)) }, { Text(c.label) })
    }
    Note("No curve is assumed. Choose the one that actually matches, or enter measured points.")
    if (p.curve != ArchCurve.SAMPLED) MeasureField("Crown height", p.crownHeight, u) { set(p.copy(crownHeight = it)) }
    else {
        var text by remember(p) { mutableStateOf(p.samples.joinToString("\n") { "%.3f, %.3f".format(it.fraction, it.height) }) }
        OutlinedTextField(text, { text = it }, label = { Text("Profile points: fraction across (0–1), height in metres") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        OutlinedButton({
            val pts = text.lines().mapNotNull { l -> l.split(',').map { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 && it.all { v -> v != null && v.isFinite() } }?.let { ProfileSample(it[0]!!, it[1]!!) } }
            set(p.copy(samples = pts))
        }) { Text("Save points") }
        Note("Sampled profiles are drawn as straight segments between your points (an approximation).")
    }
}

@Composable
private fun DetailsSection(vm: RoomViewModel, m: RoomModel) {
    SectionTitle("Ceiling details (optional)")
    Note("Dropped areas, exposed beams and coffers need their location and vertical size. Unknown depth stays unknown.")
    val c = Polygons.triangulate(m.outline).firstOrNull()?.let { t -> (m.outline[t[0]] + m.outline[t[1]] + m.outline[t[2]]) / 3.0 } ?: Vec2(0.0, 0.0)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in CeilingDetailKind.values()) OutlinedButton({ vm.edit { CeilingEdits.addDetail(it, k, c, 1.0, 0.3, 0.0, null) } }) { Text("+ ${k.label.substringBefore(' ')}") }
    }
    for (d in m.ceiling.details) {
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text(d.kind.label, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeasureField("Centre x", com.owlacoustics.domain.model.Measure(d.center.x, com.owlacoustics.domain.model.Provenance.USER_ENTERED), m.units, Modifier.weight(1f)) { v ->
                        v?.let { x -> vm.edit { CeilingEdits.updateDetail(it, d.copy(center = Vec2(x.meters, d.center.y))) } } }
                    MeasureField("Centre y", com.owlacoustics.domain.model.Measure(d.center.y, com.owlacoustics.domain.model.Provenance.USER_ENTERED), m.units, Modifier.weight(1f)) { v ->
                        v?.let { y -> vm.edit { CeilingEdits.updateDetail(it, d.copy(center = Vec2(d.center.x, y.meters))) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeasureField("Length", com.owlacoustics.domain.model.Measure(d.sizeAlong, com.owlacoustics.domain.model.Provenance.USER_ENTERED), m.units, Modifier.weight(1f)) { v ->
                        v?.let { s -> vm.edit { CeilingEdits.updateDetail(it, d.copy(sizeAlong = s.meters)) } } }
                    MeasureField("Width", com.owlacoustics.domain.model.Measure(d.sizeAcross, com.owlacoustics.domain.model.Provenance.USER_ENTERED), m.units, Modifier.weight(1f)) { v ->
                        v?.let { s -> vm.edit { CeilingEdits.updateDetail(it, d.copy(sizeAcross = s.meters)) } } }
                }
                DegreesField("Direction (degrees)", d.bearingDeg) { b -> b?.let { deg -> vm.edit { CeilingEdits.updateDetail(it, d.copy(bearingDeg = deg)) } } }
                MeasureField(when (d.kind) { CeilingDetailKind.DROPPED -> "Underside height above floor"; CeilingDetailKind.BEAM -> "Beam depth below ceiling"; CeilingDetailKind.COFFER -> "Recess depth" },
                    d.vertical, m.units) { v -> vm.edit { CeilingEdits.updateDetail(it, d.copy(vertical = v)) } }
                TextButton({ vm.edit { CeilingEdits.removeDetail(it, d.id) } }) { Text("Remove") }
            }
        }
    }
}
