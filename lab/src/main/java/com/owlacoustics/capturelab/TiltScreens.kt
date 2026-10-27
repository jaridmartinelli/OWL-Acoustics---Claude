package com.owlacoustics.capturelab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.owlacoustics.domain.capturelab.TiltMath
import com.owlacoustics.domain.geometry.Vec2
import java.util.UUID
import kotlin.math.abs

@Composable
fun AimReadout(aim: Aim) {
    val rollWarn = abs(Math.toDegrees(aim.roll)) > 8
    Text("Tilt %.1f° %s · heading %.0f°".format(abs(Math.toDegrees(aim.depression)), if (aim.depression >= 0) "down" else "up", Math.toDegrees(aim.yaw)) +
        if (rollWarn) " · hold the phone upright" else "",
        color = if (rollWarn) Color(0xFFB3261E) else Color.Unspecified, style = MaterialTheme.typography.bodyMedium)
}

/** Method 7: phone (eye) height from a door of known height, used to scale methods 2, 4 and 6. */
@Composable
fun DoorHeightScreen(store: LabStore, onDone: () -> Unit) {
    val o = rememberOrientation()
    val aim by o.aim.collectAsState()
    var door by remember { mutableStateOf(80 * 0.0254) }
    var bottom by remember { mutableStateOf<Double?>(null) }
    var top by remember { mutableStateOf<Double?>(null) }
    var manual by remember { mutableStateOf<Double?>(null) }
    val st by store.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraFeed(Modifier.fillMaxSize(), onFrame = null)
            Crosshair(Modifier.fillMaxSize())
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Phone height · now ${st.eyeHeightM?.let { ft(it) } ?: "not set"} ${st.eyeHeightSource}", style = MaterialTheme.typography.titleSmall)
            AimReadout(aim)
            FeetField("Door height (check yours; standard is 6' 8\")", "6' 8\"") { m, _ -> if (m != null) door = m }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ bottom = aim.depression }) { Text(if (bottom == null) "Aim: door bottom" else "Bottom ✓") }
                OutlinedButton({ top = -aim.depression }) { Text(if (top == null) "Aim: door top" else "Top ✓") }
            }
            val h = if (bottom != null && top != null) TiltMath.eyeHeightFromDoor(door, bottom!!, top!!) else null
            Text(when {
                bottom == null || top == null -> "Stand a few steps from a door. Hold the phone where you'll hold it while scanning."
                h == null -> "Couldn't work it out — re-aim at the bottom and top."
                else -> "Phone height ≈ ${ft(h)}"
            })
            FeetField("Or type phone height (floor to camera)", "") { m, _ -> manual = m }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ store.update { it.copy(eyeHeightM = h, eyeHeightSource = "(door method 7)") }; onDone() }, enabled = h != null) { Text("Use door result") }
                Button({ store.update { it.copy(eyeHeightM = manual, eyeHeightSource = "(typed)") }; onDone() }, enabled = manual != null) { Text("Use typed") }
                TextButton(onDone) { Text("Back") }
            }
        }
    }
}

/** Method 4: stand still and aim at each floor corner. */
@Composable
fun AimTiltScreen(store: LabStore, onDone: () -> Unit) {
    val o = rememberOrientation()
    val aim by o.aim.collectAsState()
    val st by store.state.collectAsState()
    val h = st.eyeHeightM
    var corners by remember { mutableStateOf<List<Vec2>>(emptyList()) }
    var taps by remember { mutableIntStateOf(0) }
    val started = remember { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CameraFeed(Modifier.fillMaxSize(), onFrame = null)
            Crosshair(Modifier.fillMaxSize())
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Method.AIM_TILT.title, style = MaterialTheme.typography.titleSmall)
            if (h == null) { Text("Set your phone height first (home screen)."); TextButton(onDone) { Text("Back") }; return@Column }
            AimReadout(aim)
            val d = TiltMath.floorDistance(h, aim.depression)
            Text(if (d != null) "Floor at crosshair is ${ft(d)} away" else "Aim lower, at the floor", style = MaterialTheme.typography.bodyLarge)
            Text("Stay on one spot. Aim at each floor corner in order around the room. ${corners.size} corner(s).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ TiltMath.floorPoint(h, aim.depression, aim.yaw)?.let { corners = corners + it; taps++ } }, enabled = d != null) { Text("Mark corner") }
                OutlinedButton({ corners = corners.dropLast(1); taps++ }, enabled = corners.isNotEmpty()) { Text("Undo") }
                Button({
                    store.addResult(LabResult(UUID.randomUUID().toString(), Method.AIM_TILT, System.currentTimeMillis(), System.currentTimeMillis() - started, taps + 1,
                        corners.map { P(it.x, it.y) }, h))
                    onDone()
                }, enabled = corners.size >= 3) { Text("Finish") }
                TextButton(onDone) { Text("Cancel") }
            }
        }
    }
}
