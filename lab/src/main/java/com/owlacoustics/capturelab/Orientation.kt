package com.owlacoustics.capturelab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.asin
import kotlin.math.atan2

/** Camera aim from the rotation sensor. Depression is positive below the horizon; yaw is relative (no compass). */
data class Aim(val yaw: Double, val depression: Double, val roll: Double, val valid: Boolean)

class OrientationTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private val _aim = MutableStateFlow(Aim(0.0, 0.0, 0.0, false))
    val aim: StateFlow<Aim> = _aim
    val available get() = sensor != null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // back camera looks along device −Z; R maps device → world (x east, y north, z up)
        val fx = -r[2].toDouble(); val fy = -r[5].toDouble(); val fz = -r[8].toDouble()
        val depression = -asin(fz.coerceIn(-1.0, 1.0))
        val yaw = atan2(fy, fx)
        val roll = asin(r[6].toDouble().coerceIn(-1.0, 1.0)) // device x-axis tilt out of horizontal
        _aim.value = Aim(yaw, depression, roll, true)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

@Composable
fun rememberOrientation(): OrientationTracker {
    val ctx = LocalContext.current
    val t = remember { OrientationTracker(ctx) }
    DisposableEffect(t) { t.start(); onDispose { t.stop() } }
    return t
}
