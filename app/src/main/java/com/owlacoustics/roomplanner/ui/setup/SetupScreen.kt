package com.owlacoustics.roomplanner.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.edit.SetupEdits
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.Arrangement as Layout
import com.owlacoustics.domain.model.ItemKind
import com.owlacoustics.domain.model.Measure
import com.owlacoustics.domain.model.Provenance
import com.owlacoustics.domain.model.Purpose
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.units.DisplayUnits
import com.owlacoustics.domain.workflow.Workflow
import com.owlacoustics.roomplanner.ui.RoomViewModel
import com.owlacoustics.roomplanner.ui.common.DegreesField
import com.owlacoustics.roomplanner.ui.common.MeasureField
import com.owlacoustics.roomplanner.ui.common.Note
import com.owlacoustics.roomplanner.ui.common.SectionTitle
import com.owlacoustics.roomplanner.ui.common.fmt
import com.owlacoustics.roomplanner.ui.plan.CanvasCallbacks
import com.owlacoustics.roomplanner.ui.plan.PlanCanvas
import com.owlacoustics.roomplanner.ui.plan.Sel
import com.owlacoustics.roomplanner.ui.plan.ViewTransform
import com.owlacoustics.roomplanner.ui.plan.WallAction

private sealed interface Placing {
    data class Item(val id: String) : Placing
    data object KeepOut : Placing
}

@Composable
fun RoomMiniMap(m: RoomModel, modifier: Modifier, onTap: ((Vec2) -> Unit)? = null) {
    var t by remember { mutableStateOf<ViewTransform?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        val tr = t ?: ViewTransform.fit(m.outline, w, h, margin = 40f).also { t = it }
        PlanCanvas(m, tr, Sel.None, WallAction.NONE, emptyList(), false, null, CanvasCallbacks(
            onTap = { _, world -> onTap?.invoke(world) },
            onDragStart = { _, _ -> false }, onDrag = { _, _ -> }, onDragEnd = {}, onCancel = {},
            onTransform = { c, p, z, r -> t = tr.transformed(c, p, z, r) },
        ), Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: RoomViewModel, m: RoomModel) {
    var placing by remember { mutableStateOf<Placing?>(null) }
    val u = m.units
    val step = if (u == DisplayUnits.IMPERIAL) 0.0254 * 3 else 0.05
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("What is this room for?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in Purpose.values()) FilterChip(m.setup.purpose == p, { vm.edit { SetupEdits.setPurpose(it, p) } }, { Text(p.label) }, modifier = Modifier.testTag("purpose-${p.name}"))
        }
        val purpose = m.setup.purpose
        if (purpose == Purpose.OTHER) {
            var text by remember(m.setup.otherUse) { mutableStateOf(m.setup.otherUse) }
            OutlinedTextField(text, { text = it }, label = { Text("Describe how you will use the room") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ vm.edit { SetupEdits.setPurpose(it, Purpose.OTHER, text) } }) { Text("Save") }
            Note("No quantitative target is assumed for “Other”. Add the sources and receivers you actually use.")
        }
        if (purpose != null) {
            SectionTitle("Your setup")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in SetupEdits.arrangementsFor(purpose)) FilterChip(m.setup.arrangement == a, { vm.edit { SetupEdits.setArrangement(it, a, 2) } }, { Text(a.label) })
            }
            if (m.setup.arrangement == null) Note("Choose the arrangement you actually use. " + if (purpose.playback) "" else "Recording setups use your real voice/instrument and microphone positions.")
            if (m.setup.arrangement == Layout.PODCAST) {
                val pairs = m.setup.items.count { it.kind == ItemKind.MICROPHONE }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Talker/microphone pairs: $pairs", Modifier.weight(1f))
                    TextButton({ vm.edit { SetupEdits.setArrangement(it, Layout.PODCAST, pairs - 1) } }, enabled = pairs > 1) { Text("−") }
                    TextButton({ vm.edit { SetupEdits.setArrangement(it, Layout.PODCAST, pairs + 1) } }, enabled = pairs < 8) { Text("+") }
                }
            }
            if (m.setup.arrangement == Layout.CUSTOM) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in ItemKind.values()) OutlinedButton({ vm.edit { SetupEdits.addCustomItem(it, k, k.label) } }) { Text("+ ${k.label}") }
                }
            }
        }
        if (m.setup.items.isNotEmpty() || m.setup.keepOuts.isNotEmpty()) {
            SectionTitle("Place items")
            Note(when (val p = placing) {
                is Placing.Item -> "Tap the plan where ${m.setup.items.firstOrNull { it.id == p.id }?.label} is (or should start)."
                Placing.KeepOut -> "Tap the plan to put a keep-clear area (for furniture or paths)."
                null -> "Existing positions are a starting point, not a locked optimum. Lock only what truly cannot move."
            })
            RoomMiniMap(m, Modifier.fillMaxWidth().height(260.dp).testTag("setup-map")) { world ->
                when (val p = placing) {
                    is Placing.Item -> vm.edit { SetupEdits.moveItem(it, p.id, world) }
                    Placing.KeepOut -> vm.edit { SetupEdits.addKeepOut(it, world, 0.6, 0.6, "Keep clear") }
                    null -> {}
                }
                placing = null
            }
            for (item in m.setup.items) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${item.label} · ${item.kind.label} (${if (item.kind.role == com.owlacoustics.domain.model.Role.SOURCE) "source" else "receiver"})",
                                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text("Locked")
                            Switch(item.locked, { lk -> vm.edit { SetupEdits.setLocked(it, item.id, lk) } })
                        }
                        Text(item.position?.let { "At x ${fmt(it.x, u)}, y ${fmt(it.y, u)}" } ?: "Not placed yet", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton({ placing = Placing.Item(item.id) }) { Text(if (item.position == null) "Place" else "Move") }
                            item.position?.let { pos ->
                                TextButton({ vm.edit { SetupEdits.moveItem(it, item.id, pos + Vec2(-step, 0.0)) } }) { Text("←") }
                                TextButton({ vm.edit { SetupEdits.moveItem(it, item.id, pos + Vec2(step, 0.0)) } }) { Text("→") }
                                TextButton({ vm.edit { SetupEdits.moveItem(it, item.id, pos + Vec2(0.0, step)) } }) { Text("↑") }
                                TextButton({ vm.edit { SetupEdits.moveItem(it, item.id, pos + Vec2(0.0, -step)) } }) { Text("↓") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MeasureField(if (item.kind == ItemKind.LISTENER) "Ear height" else "Height (acoustic centre)", item.height, u, Modifier.weight(1f)) { h ->
                                vm.edit { SetupEdits.setHeight(it, item.id, h) } }
                            DegreesField("Facing (degrees)", item.facingDeg, Modifier.weight(1f)) { d -> vm.edit { SetupEdits.setFacing(it, item.id, d) } }
                        }
                        if (m.setup.arrangement == Layout.CUSTOM) TextButton({ vm.edit { SetupEdits.removeItem(it, item.id) } }) { Text("Remove") }
                    }
                }
            }
            OutlinedButton({ placing = Placing.KeepOut }) { Text("+ Keep-clear area") }
            for (k in m.setup.keepOuts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MeasureField("Width", Measure(k.sizeX, Provenance.USER_ENTERED), u, Modifier.weight(1f)) { v ->
                        v?.let { s -> vm.edit { mm -> SetupEdits.removeKeepOut(mm, k.id).let { r -> (r as? com.owlacoustics.domain.edit.EditResult.Ok)?.let { SetupEdits.addKeepOut(it.model, k.center, s.meters, k.sizeY, k.label) } ?: r } } } }
                    MeasureField("Depth", Measure(k.sizeY, Provenance.USER_ENTERED), u, Modifier.weight(1f)) { v ->
                        v?.let { s -> vm.edit { mm -> SetupEdits.removeKeepOut(mm, k.id).let { r -> (r as? com.owlacoustics.domain.edit.EditResult.Ok)?.let { SetupEdits.addKeepOut(it.model, k.center, k.sizeX, s.meters, k.label) } ?: r } } } }
                    TextButton({ vm.edit { SetupEdits.removeKeepOut(it, k.id) } }) { Text("Remove") }
                }
            }
        }
        val st = Workflow.stages(m)
        if (st.setupMissing.isNotEmpty()) Text("Still needed: " + st.setupMissing.joinToString(), color = MaterialTheme.colorScheme.tertiary)
        else Text("Setup ready.", color = MaterialTheme.colorScheme.primary)
        if (m.setup.items.any { it.position != null && it.height == null }) Note("Unknown heights limit analysis to wall paths in plan.")
    }
}
