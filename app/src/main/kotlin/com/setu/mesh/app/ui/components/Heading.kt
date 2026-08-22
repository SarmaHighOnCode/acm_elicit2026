package com.setu.mesh.app.ui.components

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.setu.mesh.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * True-north heading in degrees, or null when this device cannot provide one.
 *
 * Sensor preference order: fused `TYPE_ROTATION_VECTOR` (gyro-stabilised, the best available),
 * falling back to `TYPE_GEOMAGNETIC_ROTATION_VECTOR` (still fused, more jitter without a gyro),
 * and finally raw `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` via `getRotationMatrix` on
 * hardware that exposes neither rotation-vector sensor. A phone with no magnetometer at all --
 * not rare on low-end hardware -- returns null; [RelativeMap] falls back to north-up rather than
 * crash or display a heading that was never real.
 *
 * [self] feeds the magnetic-to-true-north correction only; it is read live from a
 * [rememberUpdatedState] rather than as a `DisposableEffect` key, so a new GPS fix (which can
 * arrive every second per B11 Part 2) updates the declination without tearing down and
 * re-registering the sensors -- that would reset the circular smoothing below and make the
 * compass visibly jump on every fix.
 */
@Composable
fun rememberTrueHeadingDegrees(self: GeoPoint?): Float? {
    val context = LocalContext.current
    val latestSelf = rememberUpdatedState(self)
    var headingDegrees by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val hasRawFallback = accelerometer != null && magnetometer != null

        if (sensorManager == null || (rotationSensor == null && !hasRawFallback)) {
            // No usable sensor combination on this device -- north-up is the honest fallback,
            // not a crash and not a frozen fake reading.
            headingDegrees = null
            return@DisposableEffect onDispose {}
        }

        // Circularly-smoothed heading: an EMA over raw degrees jumps the long way round at the
        // 359 -> 0 wrap (averaging 359 and 1 the naive way gives 180, not 0), which makes the
        // compass visibly spin backwards every time the phone crosses north. Smoothing the unit
        // vector's components instead and re-deriving the angle with atan2 has no seam.
        var smoothedX = 1f
        var smoothedY = 0f
        var smoothedInitialized = false
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val uprightMatrix = FloatArray(9)
        val rotationVector = FloatArray(4)
        val orientation = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var haveGravity = false
        var haveGeomagnetic = false

        fun publishMagneticAzimuthDegrees(azimuthDegrees: Float) {
            // The rotation vector's reference is magnetic north, but every bearing this app
            // computes from lat/lon (relativeOffsetMetres, bearingDegrees) is relative to true
            // north. Declination reaches double-digit degrees in parts of the world, so this is
            // a real error to skip, not a rounding detail -- but it needs a self fix to compute,
            // so it is only applied when one exists.
            val declination = latestSelf.value?.let {
                GeomagneticField(
                    it.latitude.toFloat(),
                    it.longitude.toFloat(),
                    0f,
                    System.currentTimeMillis(),
                ).declination
            } ?: 0f
            val trueDegrees = (azimuthDegrees + declination + 360f) % 360f

            val radians = Math.toRadians(trueDegrees.toDouble())
            val x = cos(radians).toFloat()
            val y = sin(radians).toFloat()
            if (!smoothedInitialized) {
                smoothedX = x
                smoothedY = y
                smoothedInitialized = true
            } else {
                smoothedX += HEADING_EMA_ALPHA * (x - smoothedX)
                smoothedY += HEADING_EMA_ALPHA * (y - smoothedY)
            }
            val smoothedDegrees = Math.toDegrees(atan2(smoothedY, smoothedX).toDouble()).toFloat()
            headingDegrees = (smoothedDegrees + 360f) % 360f
        }

        fun remapAndPublish() {
            @Suppress("DEPRECATION") // Context.getDisplay() needs API 30; this app's minSdk is
            // 26, and defaultDisplay has no minSdk-26-safe replacement.
            val rotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            val (axisX, axisY) = when (rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)

            // Tilt compensation, and the reason the compass pointed the wrong way.
            //
            // getOrientation()'s azimuth is the compass direction of the *top edge of the
            // screen* projected onto the horizontal plane. That is what you want with the phone
            // lying flat. Held up at a reading angle it is already skewed, and past vertical it
            // inverts outright -- the top edge starts pointing back over the reader's shoulder,
            // so the map reads a full 180 degrees out. What someone holding a phone up means by
            // "the way I am facing" is where the *back* of the phone points, which is the
            // device's -Z axis.
            //
            // The switch happens at 45 degrees of tilt, where the two references agree exactly:
            // the top edge and the back of the phone project onto the same horizontal bearing
            // there, so crossing the threshold is seamless instead of a jump. remappedMatrix[8]
            // is the world-vertical component of the device's Z axis -- near +-1 lying flat,
            // near 0 stood upright.
            val orientedMatrix = if (abs(remappedMatrix[8]) < FLAT_ENOUGH_Z_COMPONENT) {
                SensorManager.remapCoordinateSystem(
                    remappedMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    uprightMatrix,
                )
                uprightMatrix
            } else {
                remappedMatrix
            }

            SensorManager.getOrientation(orientedMatrix, orientation)
            val azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            publishMagneticAzimuthDegrees((azimuthDegrees + 360f) % 360f)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        // Some vendors -- Samsung among them, which is what this is being
                        // tested on -- report a 5-element rotation vector: the quaternion plus
                        // a trailing heading-accuracy estimate. getRotationMatrixFromVector
                        // does not accept anything longer than 4, so hand it the quaternion
                        // rather than the raw array. A 3-element vector is passed through
                        // untouched: the call derives w itself in that case, and padding it to
                        // 4 with a zero w would silently produce a wrong matrix.
                        val vector = if (event.values.size > 4) {
                            System.arraycopy(event.values, 0, rotationVector, 0, 4)
                            rotationVector
                        } else {
                            event.values
                        }
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, vector)
                        remapAndPublish()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, gravity, 0, minOf(event.values.size, 3))
                        haveGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomagnetic, 0, minOf(event.values.size, 3))
                        haveGeomagnetic = true
                    }
                }
                if (rotationSensor == null && haveGravity && haveGeomagnetic) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                        remapAndPublish()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose { sensorManager.unregisterListener(listener) }
    }

    return headingDegrees
}

// Low enough to damp hand jitter, high enough that the map still feels live when the phone
// actually turns.
private const val HEADING_EMA_ALPHA = 0.15f

/**
 * cos(45 degrees). Above this the device is nearer flat than upright, so the top edge of the
 * screen is the better reference for "which way am I facing"; below it, the back of the phone is.
 */
private const val FLAT_ENOUGH_Z_COMPONENT = 0.7071f
