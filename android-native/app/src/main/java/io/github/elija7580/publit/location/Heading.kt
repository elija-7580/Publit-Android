package io.github.elija7580.publit.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Which way the phone points, in degrees from north (the compass heading on iOS); null without a compass.
 * Rounded to 5° so the screen does not redraw on every tremor.
 */
@Composable
fun rememberHeading(enabled: Boolean = true): State<Double?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<Double?>(null) }
    DisposableEffect(enabled) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || sm == null || sensor == null) return@DisposableEffect onDispose { }
        val rot = FloatArray(9); val o = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, o)
                val deg = (Math.toDegrees(o[0].toDouble()) + 360) % 360
                val r = Math.round(deg / 5) * 5.0 % 360
                if (state.value != r) state.value = r
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener); state.value = null }
    }
    return state
}
