package com.owlacoustics.roomplanner.ui.analysis

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.acoustics.CapabilityStatus
import com.owlacoustics.domain.acoustics.PathStatus
import com.owlacoustics.domain.catalog.Catalog
import com.owlacoustics.domain.catalog.ProductCategory
import com.owlacoustics.domain.edit.Edits
import com.owlacoustics.domain.edit.SetupEdits
import com.owlacoustics.domain.geometry.Vec2
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.model.TreatmentMode
import com.owlacoustics.domain.persistence.SessionState
import com.owlacoustics.domain.placement.PlacementOptimizer
import com.owlacoustics.domain.recommend.ActionKind
import com.owlacoustics.domain.workflow.AnalysisBundle
import com.owlacoustics.domain.workflow.AnalysisRunner
import com.owlacoustics.domain.workflow.RESULT_ANALYSIS
import com.owlacoustics.domain.workflow.RESULT_PLACEMENT
import com.owlacoustics.domain.workflow.RESULT_RECOMMENDATIONS
import com.owlacoustics.roomplanner.ui.RoomViewModel
import com.owlacoustics.roomplanner.ui.common.KeyValue
import com.owlacoustics.roomplanner.ui.common.Note
import com.owlacoustics.roomplanner.ui.common.SectionTitle
import com.owlacoustics.roomplanner.ui.common.fmt
import com.owlacoustics.roomplanner.ui.plan.CanvasCallbacks
import com.owlacoustics.roomplanner.ui.plan.PlanCanvas
import com.owlacoustics.roomplanner.ui.plan.Sel
import com.owlacoustics.roomplanner.ui.plan.ViewTransform
import com.owlacoustics.roomplanner.ui.plan.WallAction

fun SessionState.analysis(): AnalysisBundle? = currentResult(RESULT_ANALYSIS)?.let { runCatching { AnalysisRunner.decodeAnalysis(it.payload) }.getOrNull() }
fun SessionState.placement(): PlacementOptimizer.Result? = currentResult(RESULT_PLACEMENT)?.let { runCatching { AnalysisRunner.decodePlacement(it.payload) }.getOrNull() }

@Composable
private fun PlanWithPoints(m: RoomModel, points: List<Vec2>, modifier: Modifier) {
    var t by remember { mutableStateOf<ViewTransform?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        val tr = t ?: ViewTransform.fit(m.outline, w, h, margin = 40f).also { t = it }
        PlanCanvas(m, tr, Sel.None, WallAction.NONE, emptyList(), false, { vt ->
            for (p in points) {
                drawCircle(Color(0xFFB3261E), 9f, vt.toScreen(p))
                drawCircle(Color.White, 4f, vt.toScreen(p))
            }
        }, CanvasCallbacks({ _, _ -> }, { _, _ -> false }, { _, _ -> }, {}, {}, { c, p, z, r -> t = tr.transformed(c, p, z, r) }), Modifier.fillMaxSize())
    }
}

@Composable
fun AnalysisScreen(vm: RoomViewModel, state: SessionState) {
    val m = state.model
    val a = state.analysis()
    val stale = state.results.isEmpty() && a == null
    val placement = state.placement()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Supported geometric analysis", style = MaterialTheme.typography.titleMedium)
        Note("Geometry predicts reflection timing and room-mode frequencies. It does not measure loudness, decay, response or severity.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.runAnalysis() }, modifier = Modifier.testTag("run-analysis")) { Text(if (a == null) "Run analysis" else "Run again") }
            OutlinedButton({ vm.runPlacement() }, modifier = Modifier.testTag("run-placement")) { Text("Find free placement") }
        }
        if (a == null) Text(if (stale) "No current result. Inputs changed since the last run, or it has not been run yet." else "Not run yet.", color = MaterialTheme.colorScheme.tertiary)
        if (a != null) {
            Text("Current for this room revision.", color = MaterialTheme.colorScheme.primary)
            SectionTitle("What can be calculated")
            for (c in a.capabilities) {
                val mark = when (c.status) { CapabilityStatus.SUPPORTED -> "✓ Supported"; CapabilityStatus.PARTIAL -> "◐ Partial"; CapabilityStatus.UNAVAILABLE -> "✕ Unavailable" }
                Text("${c.operation}: $mark", style = MaterialTheme.typography.bodyMedium)
                Note(c.detail)
            }
            SectionTitle("Room modes")
            Note(a.modesNote)
            a.modes?.take(24)?.forEach { md -> KeyValue("(${md.nx},${md.ny},${md.nz}) ${md.type}", "%.1f Hz".format(md.hz)) }
            SectionTitle("First-order reflections")
            a.reflections.notes.forEach { Note(it) }
            Note("Speed of sound ${a.reflections.speedOfSound} m/s (dry air, about 20 °C).")
            val valid = a.reflections.paths.filter { it.status == PathStatus.VALID }
            PlanWithPoints(m, valid.map { Vec2(it.point.x, it.point.y) }, Modifier.fillMaxWidth().height(240.dp))
            val names = m.setup.items.associate { it.id to it.label }
            for (d in a.reflections.direct) {
                Text("${names[d.sourceId]} → ${names[d.receiverId]}: direct ${fmt(d.lengthM, m.units)}, %.2f ms".format(d.delayMs) +
                    (if (d.planOnly) " (plan only)" else "") + (if (d.occluded) " — direct path blocked by a wall" else ""), style = MaterialTheme.typography.titleSmall)
                for (p in a.reflections.paths.filter { it.sourceId == d.sourceId && it.receiverId == d.receiverId }.sortedBy { it.afterDirectMs }) {
                    val where = "x %.2f, y %.2f".format(p.point.x, p.point.y) + (p.point.z?.let { ", z %.2f m".format(it) } ?: "")
                    if (p.status == PathStatus.VALID) Text("• ${p.surface}: ${fmt(p.lengthM, m.units)}, +%.2f ms after direct at $where".format(p.afterDirectMs), style = MaterialTheme.typography.bodySmall)
                    else Text("• ${p.surface}: excluded — ${p.status.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.note.isNotEmpty()) Note("   ${p.note}")
                }
            }
            if (a.reflections.sbir.isNotEmpty()) {
                SectionTitle("Speaker-boundary interference (SBIR)")
                Note("First cancellation frequency where the boundary reflection arrives half a cycle late. Assumes a rigid boundary; no dip depth or audibility is implied.")
                for (s in a.reflections.sbir.take(16)) Text("• ${names[s.sourceId]} → ${names[s.receiverId]}, ${s.surface}: boundary ${fmt(s.boundaryDistanceM, m.units)} away, first null ≈ %.0f Hz".format(s.firstNullHz), style = MaterialTheme.typography.bodySmall)
            }
        }
        PlacementSection(vm, m, placement)
    }
}

@Composable
private fun PlacementSection(vm: RoomViewModel, m: RoomModel, r: PlacementOptimizer.Result?) {
    SectionTitle("Free placement")
    Note("Moving or turning what you already have comes before any purchase.")
    if (r == null) { Note("Run “Find free placement” to compare options for this room revision."); return }
    r.blocked?.let { Text(it, color = MaterialTheme.colorScheme.tertiary); return }
    r.inputs.forEach { Note(it) }
    if (r.currentIsNonDominated) Text("Your current placement is already among the best options under these criteria.", color = MaterialTheme.colorScheme.primary)
    r.current?.let { c -> Note("Now: earliest reflection " + (c.earliestMs?.let { "%.1f ms".format(it) } ?: "none found") + (c.asymmetryMs?.let { ", left/right difference %.2f ms".format(it) } ?: "")) }
    r.proposals.forEachIndexed { i, p ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text("Option ${i + 1}", style = MaterialTheme.typography.titleSmall)
                p.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                val after = m.copy(setup = m.setup.copy(items = m.setup.items.map { item ->
                    p.placements.firstOrNull { it.itemId == item.id }?.let { item.copy(position = Vec2(it.x, it.y), facingDeg = it.facingDeg) } ?: item
                }))
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    Column(Modifier.weight(1f)) { Note("Before"); PlanWithPoints(m, emptyList(), Modifier.fillMaxSize()) }
                    Column(Modifier.weight(1f)) { Note("After"); PlanWithPoints(after, emptyList(), Modifier.fillMaxSize()) }
                }
                Button({ vm.edit { SetupEdits.applyPlacement(it, p.placements.associate { pl -> pl.itemId to (Vec2(pl.x, pl.y) to pl.facingDeg) }) } },
                    modifier = Modifier.testTag("apply-proposal-$i")) { Text("Apply this placement") }
                Note("Applying is one undoable change.")
            }
        }
    }
    r.limits.forEach { Note(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecommendScreen(vm: RoomViewModel, state: SessionState, onExpertHelp: () -> Unit) {
    val m = state.model
    val a = state.analysis()
    val rec = state.currentResult(RESULT_RECOMMENDATIONS)?.let { runCatching { AnalysisRunner.decodeRecommendations(it.payload) }.getOrNull() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Actions and treatment", style = MaterialTheme.typography.titleMedium)
        Note("Buying nothing is a valid result. Products are suggested only where a geometric reason exists, never because a catalog item exists.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in TreatmentMode.values()) FilterChip(m.treatment.mode == mode, { vm.edit { Edits.finish(it, it.copy(treatment = it.treatment.copy(mode = mode)), "Change treatment mode") } }, { Text(mode.label) })
        }
        if (m.treatment.mode == TreatmentMode.ITEM_LIMIT) Row {
            Text("Maximum items: ${m.treatment.maxItems}", Modifier.weight(1f))
            TextButton({ vm.edit { Edits.finish(it, it.copy(treatment = it.treatment.copy(maxItems = (it.treatment.maxItems - 1).coerceAtLeast(0))), "Budget limit") } }) { Text("−") }
            TextButton({ vm.edit { Edits.finish(it, it.copy(treatment = it.treatment.copy(maxItems = it.treatment.maxItems + 1)), "Budget limit") } }) { Text("+") }
        }
        if (m.treatment.mode != TreatmentMode.BUY_NOTHING) {
            Text("Allowed products", style = MaterialTheme.typography.labelLarge)
            for (p in Catalog.products) {
                val excluded = p.id in m.treatment.excludedProductIds
                FilterChip(!excluded, {
                    vm.edit { it.copy(treatment = it.treatment.copy(excludedProductIds = if (excluded) it.treatment.excludedProductIds - p.id else it.treatment.excludedProductIds + p.id)).let { n -> Edits.finish(it, n, "Change allowed products") } }
                }, { Text(p.name + if (p.category == ProductCategory.DIFFUSER) " (reflective, not an absorber)" else "") })
            }
        }
        if (a == null) Text("Run the analysis first, then prepare recommendations.", color = MaterialTheme.colorScheme.tertiary)
        else Button({ vm.runRecommendations(a, state.placement()) }, modifier = Modifier.testTag("run-recommend")) { Text(if (rec == null) "Prepare recommendations" else "Recalculate") }
        if (rec != null) {
            Text(rec.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
            Note(rec.costNote)
            for (act in rec.actions) {
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Text(act.title, style = MaterialTheme.typography.titleSmall)
                        Text(act.reason, style = MaterialTheme.typography.bodySmall)
                        Note("Where: ${act.where}")
                        Note("Expected benefit: ${act.expectedBenefit}")
                        Note("Uncertainty: ${act.uncertainty}")
                        if (act.productOptions.isNotEmpty()) Note("Fits here: " + act.productOptions.joinToString())
                        if (act.kind == ActionKind.TREATMENT_LOCATION) TextButton({
                            vm.edit { Edits.finish(it, it.copy(treatment = it.treatment.copy(excludedLocations = it.treatment.excludedLocations + act.locationKey)), "Skip treatment location") }
                        }) { Text("Skip this location") }
                    }
                }
            }
            rec.limits.forEach { Note(it) }
        } else if (a != null && state.results.none { it.kind == RESULT_RECOMMENDATIONS }) Note("Recommendations need to be (re)calculated for the current inputs.")
        if (m.treatment.excludedLocations.isNotEmpty()) TextButton({ vm.edit { Edits.finish(it, it.copy(treatment = it.treatment.copy(excludedLocations = emptyList())), "Restore skipped locations") } }) { Text("Restore skipped locations") }
        SectionTitle("Catalog geometry")
        for (p in Catalog.products) {
            Text(p.name, style = MaterialTheme.typography.bodyMedium)
            Note("${fmt(p.geometry.faceWidthM, m.units)} × ${fmt(p.geometry.faceHeightM, m.units)} × ${fmt(p.geometry.depthM, m.units)} · ${p.geometry.confirmation} · ${p.material}. Performance data: not supplied.")
        }
        OutlinedButton(onExpertHelp, Modifier.padding(top = 8.dp)) { Text("Expert help") }
    }
}
