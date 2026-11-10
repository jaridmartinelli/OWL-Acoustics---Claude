package com.owlacoustics.capturelab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.owlacoustics.capturelab.ar.ArAutoScreen
import com.owlacoustics.capturelab.ar.ArTraceScreen
import com.owlacoustics.capturelab.trace.TraceScreen
import com.owlacoustics.domain.capturelab.Score
import com.owlacoustics.domain.capturelab.Scorecard
import com.owlacoustics.domain.capturelab.Truth
import java.text.DateFormat
import java.util.Date

enum class Screen { HOME, HEIGHT, AR_TRACE, SPIN, AIM, AR_AUTO, TRACE, RESULTS }

class LabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LabStore(applicationContext)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val ctx = LocalContext.current
                    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
                    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
                    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                    if (screen != Screen.HOME) BackHandler { screen = Screen.HOME }
                    val home = { screen = Screen.HOME }
                    if (!granted && screen != Screen.HOME && screen != Screen.RESULTS) {
                        Column(Modifier.padding(16.dp)) {
                            Text("The camera is needed to test capture methods.")
                            Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                            TextButton(home) { Text("Back") }
                        }
                    } else when (screen) {
                        Screen.HOME -> Home(store) { screen = it }
                        Screen.HEIGHT -> DoorHeightScreen(store, home)
                        Screen.AR_TRACE -> ArTraceScreen(store, home)
                        Screen.SPIN -> SpinScreen(store, home)
                        Screen.AIM -> AimTiltScreen(store, home)
                        Screen.AR_AUTO -> ArAutoScreen(store, home)
                        Screen.TRACE -> TraceScreen(store, home)
                        Screen.RESULTS -> Results(store, home)
                    }
                }
            }
        }
    }
}

fun scoreOf(r: LabResult, truth: Truth?): Score? =
    if (truth == null || r.corners.size < 3) null else Scorecard.score(r.corners.map { it.v() }, truth)

@Composable
private fun Home(store: LabStore, go: (Screen) -> Unit) {
    val st by store.state.collectAsState()
    val ctx = LocalContext.current
    val ar = remember { ArCoreApk.getInstance().checkAvailability(ctx) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OWL Capture Lab", style = MaterialTheme.typography.headlineSmall)
        Text("Test build. Try each camera method in the same room, then compare them against your tape measure.", style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("1. Tape-measure the room (truth)", style = MaterialTheme.typography.titleSmall)
                TruthEditor(store)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("2. Phone height: " + (st.eyeHeightM?.let { ft(it) } ?: "not set"), style = MaterialTheme.typography.titleSmall)
                    Text("Needed by methods 2, 4, 6 (not 8). Set it with method 7 (door) or type it. ${st.eyeHeightSource}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton({ go(Screen.HEIGHT) }) { Text("7 · Set") }
            }
        }
        Text("3. Scan with each method", style = MaterialTheme.typography.titleSmall)
        val rows = listOf(Method.TRACE to Screen.TRACE, Method.AR_TRACE to Screen.AR_TRACE, Method.AR_AUTO to Screen.AR_AUTO, Method.AIM_TILT to Screen.AIM, Method.SPIN_EDGE to Screen.SPIN)
        for ((m, s) in rows) {
            val label = if (m == Method.SPIN_EDGE) "2 + 6 · Spin (floor-line + AI depth)" else m.title
            val runs = st.results.filter { it.method == m || (m == Method.SPIN_EDGE && it.method == Method.SPIN_DEPTH) }.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text((if (m.needsAr && ar != ArCoreApk.Availability.SUPPORTED_INSTALLED) "AR: $ar · " else "") + "$runs run(s)", style = MaterialTheme.typography.labelSmall)
                }
                Button({ go(s) }) { Text("Scan") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button({ go(Screen.RESULTS) }, Modifier.fillMaxWidth()) { Text("4. Compare results (${st.results.size})") }
        Text("Method 3 (server) is paused.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TruthEditor(store: LabStore) {
    val st by store.state.collectAsState()
    var rect by remember { mutableStateOf(st.truth?.rectangle ?: true) }
    var l by remember { mutableStateOf(st.truth?.takeIf { it.rectangle }?.lengthsM?.getOrNull(0)) }
    var w by remember { mutableStateOf(st.truth?.takeIf { it.rectangle }?.lengthsM?.getOrNull(1)) }
    var listText by remember { mutableStateOf(st.truthText) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(rect, { rect = true }, { Text("Rectangle") })
        FilterChip(!rect, { rect = false }, { Text("Other shape") })
    }
    if (rect) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) { FeetField("Length", st.truth?.takeIf { it.rectangle }?.lengthsM?.getOrNull(0)?.let { ft(it) } ?: "") { m, _ -> l = m } }
        Column(Modifier.weight(1f)) { FeetField("Width", st.truth?.takeIf { it.rectangle }?.lengthsM?.getOrNull(1)?.let { ft(it) } ?: "") { m, _ -> w = m } }
    } else {
        androidx.compose.material3.OutlinedTextField(listText, { listText = it }, label = { Text("Wall lengths in order, separated by commas") }, modifier = Modifier.fillMaxWidth())
    }
    TextButton({
        val truth = if (rect) { val a = l; val b = w; if (a != null && b != null) Scorecard.rectangle(a, b) else null }
        else listText.split(',').mapNotNull { (com.owlacoustics.domain.units.LengthParser.parse(it, com.owlacoustics.domain.units.LengthUnit.FOOT) as? com.owlacoustics.domain.units.ParseResult.Ok)?.value?.meters }
            .takeIf { it.size >= 3 }?.let { Truth(it, false) }
        if (truth != null) store.update { it.copy(truth = truth, truthText = listText) }
    }) { Text("Save truth" + (st.truth?.let { " (saved: " + it.lengthsM.joinToString(" · ") { m -> ft(m) } + ")" } ?: "")) }
}

@Composable
private fun Results(store: LabStore, back: () -> Unit) {
    val st by store.state.collectAsState()
    val truth = st.truth
    val ranked = st.results.sortedWith(compareBy(nullsLast()) { scoreOf(it, truth)?.meanAbsErrorM })
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Results", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(back) { Text("Back") }
        }
        Text(if (truth == null) "Enter the tape-measured room on the home screen to score results." else "Truth: " + truth.lengthsM.joinToString(" · ") { ft(it) } + " · best first",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ranked, key = { it.id }) { r ->
                val sc = scoreOf(r, truth)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(r.method.title, style = MaterialTheme.typography.titleSmall)
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.atMs)) + " · %.0f s · %d taps".format(r.durationMs / 1000.0, r.taps) +
                            (r.eyeHeightM?.let { " · phone at ${ft(it)}" } ?: "") + (if (r.note.isNotEmpty()) " · ${r.note}" else ""), style = MaterialTheme.typography.labelSmall)
                        if (r.corners.size >= 3) {
                            OutlineDrawing(r.corners.map { it.v() }, Modifier.fillMaxWidth().height(180.dp))
                            Text("Walls: " + Scorecard.wallLengths(r.corners.map { it.v() }).joinToString(" · ") { ft(it) }, style = MaterialTheme.typography.bodySmall)
                        } else Text("No outline produced.", style = MaterialTheme.typography.bodySmall)
                        if (sc != null) {
                            if (sc.wallCountFound != sc.wallCountTrue) Text("Found ${sc.wallCountFound} walls, room has ${sc.wallCountTrue}.", color = MaterialTheme.colorScheme.error)
                            sc.meanAbsErrorM?.let { Text("Average wall error %.1f\" · worst %.1f\"".format(it / 0.0254, (sc.maxAbsErrorM ?: 0.0) / 0.0254), style = MaterialTheme.typography.bodyLarge) }
                            if (sc.perWallErrorM.isNotEmpty()) Text("Per wall: " + sc.perWallErrorM.joinToString(" · ") { inches(it) }, style = MaterialTheme.typography.bodySmall)
                            Text(listOfNotNull(sc.meanAngleErrorDeg?.let { "corners off square by %.1f° avg".format(it) },
                                sc.areaErrorPct?.let { "area %+.1f%%".format(it) }, "perimeter %+.1f%%".format(sc.perimeterErrorPct)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ store.update { s -> s.copy(results = s.results.filter { it.id != r.id }) } }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
