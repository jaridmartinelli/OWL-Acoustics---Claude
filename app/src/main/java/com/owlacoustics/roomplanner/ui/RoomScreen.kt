package com.owlacoustics.roomplanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.persistence.inputHash
import com.owlacoustics.domain.model.RoomModel
import com.owlacoustics.domain.services.OfferAvailability
import com.owlacoustics.domain.services.ServiceCatalog
import com.owlacoustics.domain.workflow.Workflow
import com.owlacoustics.roomplanner.data.AppContainer
import com.owlacoustics.roomplanner.ui.analysis.AnalysisScreen
import com.owlacoustics.roomplanner.ui.analysis.RecommendScreen
import com.owlacoustics.roomplanner.ui.capture.CaptureScreen
import com.owlacoustics.roomplanner.ui.ceiling.CeilingScreen
import com.owlacoustics.roomplanner.ui.importplan.ImportScreen
import com.owlacoustics.roomplanner.ui.importplan.backdropFor
import com.owlacoustics.roomplanner.ui.importplan.renderSource
import com.owlacoustics.roomplanner.ui.plan.PlanEditor
import com.owlacoustics.roomplanner.ui.plan.Sel
import com.owlacoustics.roomplanner.ui.setup.SetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Step(val label: String) { CAPTURE("Capture"), IMPORT("Import"), PLAN("Plan"), CEILING("Ceiling & 3D"), SETUP("Purpose & setup"), ANALYSIS("Analysis"), ACTIONS("Actions") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(c: AppContainer, vm: RoomViewModel, initialStep: Step, onBack: () -> Unit) {
    val stateFlow = vm.state
    if (stateFlow == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Room unavailable") }, navigationIcon = { TextButton(onBack) { Text("Rooms") } }) }) { pad ->
            Text(vm.loadError ?: "Room could not be opened.", Modifier.padding(pad).padding(16.dp))
        }
        return
    }
    val state by stateFlow.collectAsState()
    val job by vm.job.collectAsState()
    var step by rememberSaveable { mutableStateOf(initialStep) }
    var requestSel by remember { mutableStateOf<Sel?>(null) }
    var expert by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it) } }
    val m = state.model

    // calibrated import source shown under the plan
    var backdropImg by remember { mutableStateOf<ImageBitmap?>(null) }
    val cal = m.importCalibration
    LaunchedEffect(cal?.mediaId, cal?.page) {
        backdropImg = null
        val rec = cal?.let { k -> c.media.all().firstOrNull { it.id == k.mediaId && !it.deleted } } ?: return@LaunchedEffect
        backdropImg = withContext(Dispatchers.IO) { renderSource(c.media, rec, cal.page).first?.asImageBitmap() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.semantics { contentDescription = "Room ${state.name}" }) {
                        Text(state.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(statusLine(m, state.saved), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                },
                navigationIcon = { TextButton(onBack, Modifier.testTag("back-to-rooms")) { Text("Rooms") } },
                actions = {
                    TextButton({ vm.undo() }, enabled = state.history.canUndo && state.saved, modifier = Modifier.testTag("undo")
                        .semantics { contentDescription = "Undo " + (state.history.undoLabel ?: "") }) { Text("Undo") }
                    TextButton({ vm.redo() }, enabled = state.history.canRedo && state.saved, modifier = Modifier.testTag("redo")
                        .semantics { contentDescription = "Redo " + (state.history.redoLabel ?: "") }) { Text("Redo") }
                    TextButton({ renaming = true }) { Text("Rename") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (renaming) RenameBar(state.name, { renaming = false }) { vm.rename(it); renaming = false }
            state.unsaved?.let { u ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("unsaved-banner")) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Not saved: ${u.reason} Your last saved version is safe.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ vm.retry() }) { Text("Retry") }
                        TextButton({ vm.discard() }) { Text("Discard") }
                    }
                }
            }
            job?.let { j ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(j.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton({ vm.cancelJob() }) { Text("Cancel") }
                    }
                    if (j.fraction != null) LinearProgressIndicator(progress = { j.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            PrimaryScrollableTabRow(selectedTabIndex = step.ordinal, edgePadding = 4.dp) {
                for (s in Step.values()) Tab(step == s, { step = s }, text = { Text(s.label) }, modifier = Modifier.testTag("tab-${s.name}"))
            }
            Column(Modifier.weight(1f)) {
                when (step) {
                    Step.CAPTURE -> CaptureScreen(m.id, c.media, c.evidence, c.remoteAnalysis) { step = Step.PLAN }
                    Step.IMPORT -> ImportScreen(vm, m, c.media) { step = Step.PLAN }
                    Step.PLAN -> PlanEditor(vm, m, backdropImg?.let { img -> cal?.let { backdropFor(img, it) } }, requestSel) { requestSel = null }
                    Step.CEILING -> CeilingScreen(vm, m, onCorrectInPlan = { sel -> requestSel = sel; step = Step.PLAN }, onExpertHelp = { expert = true })
                    Step.SETUP -> SetupScreen(vm, m)
                    Step.ANALYSIS -> AnalysisScreen(vm, state)
                    Step.ACTIONS -> RecommendScreen(vm, state) { expert = true }
                }
            }
        }
    }
    if (expert) ExpertHelpDialog(c, m, state.history.entries[state.history.cursor].seq) { expert = false }
}

fun statusLine(m: RoomModel, saved: Boolean): String {
    val s = Workflow.stages(m)
    val parts = ArrayList<String>()
    parts += if (saved) "Saved on this phone" else "Not saved"
    parts += when {
        m.finalized -> "finalized (still editable)"
        s.planReviewed -> "plan confirmed"
        else -> "draft"
    }
    if (s.ceilingSupported) parts += "ceiling modeled"
    if (s.setupReady) parts += "setup ready"
    return parts.joinToString(" · ")
}

@Composable
fun ExpertHelpDialog(c: AppContainer, m: RoomModel, seq: Long, onClose: () -> Unit) {
    val availability = ServiceCatalog.availability(null, c.billingConfigured)
    AlertDialog(onDismissRequest = onClose, title = { Text("Expert help") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (availability) {
                is OfferAvailability.Unavailable -> Text("${availability.reason} No packages, prices or payments are offered in this build, and nothing is sent.")
                is OfferAvailability.Ready -> Text(availability.pkg.description)
            }
            Text("When a service is configured, you will see exactly what is included and what would be shared before paying. A request is tied to the room revision you submit; later edits do not change it.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Text("What would be shared: room model revision $seq (${inputHash(m).take(10)}…), ${m.vertices.size} walls, ${m.openings.size} openings, ceiling: ${m.ceiling.profile::class.simpleName}. Raw video is never included unless you separately allow it.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Text("Editing this room is always free and never depends on this service.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = { TextButton(onClose) { Text("Close") } })
}

@Composable
fun RenameBar(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Room name") }, modifier = Modifier.weight(1f))
        TextButton({ onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        TextButton(onDismiss) { Text("Cancel") }
    }
}
