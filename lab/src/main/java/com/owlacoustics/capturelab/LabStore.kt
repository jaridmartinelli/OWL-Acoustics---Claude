package com.owlacoustics.capturelab

import android.content.Context
import com.owlacoustics.domain.capturelab.Truth
import com.owlacoustics.domain.geometry.Vec2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Methods under test. Numbers match the options discussed; method 3 (server) is paused. */
@Serializable
enum class Method(val title: String, val needsAr: Boolean, val summary: String) {
    AR_TRACE("1 · ARCore tracing", true, "Aim the crosshair at each floor corner; ARCore supplies real feet."),
    SPIN_EDGE("2 · Spin: floor-line", false, "Turn on the spot; the app finds the floor–wall line in each view. Model-free version (no learned layout model)."),
    AIM_TILT("4 · Aim + tilt", false, "Stand still, aim at each floor corner; distance comes from phone height and tilt."),
    AR_AUTO("5 · ARCore auto walls", true, "Pan across every wall; ARCore detects wall planes and the outline is built automatically."),
    SPIN_DEPTH("6 · Spin: AI depth", false, "Turn on the spot; an on-device depth model estimates wall distance, scaled by the floor."),
    TRACE("8 · Camera scan", false, "The app finds floor, walls and ceiling as you turn; a brush adds or takes away where it is wrong."),
}

@Serializable
data class P(val x: Double, val y: Double) { fun v() = Vec2(x, y) }

@Serializable
data class LabResult(
    val id: String,
    val method: Method,
    val atMs: Long,
    val durationMs: Long,
    val taps: Int,
    val corners: List<P>,
    val eyeHeightM: Double? = null,
    val note: String = "",
)

@Serializable
data class LabState(
    val eyeHeightM: Double? = null,
    val eyeHeightSource: String = "",
    val truth: Truth? = null,
    val truthText: String = "",
    val results: List<LabResult> = emptyList(),
)

/** App-private JSON store; written atomically. */
class LabStore(context: Context) {
    private val file = File(context.filesDir, "lab-state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<LabState> = _state

    private fun load(): LabState = runCatching { json.decodeFromString(LabState.serializer(), file.readText()) }.getOrDefault(LabState())

    @Synchronized
    fun update(f: (LabState) -> LabState) {
        val next = f(_state.value)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LabState.serializer(), next))
        tmp.renameTo(file)
        _state.value = next
    }

    fun addResult(r: LabResult) = update { it.copy(results = it.results + r) }
}
