package com.setu.mesh.app.ui.dev

import android.hardware.SensorManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.service.SelfFix
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.app.ui.components.declinationDegrees
import com.setu.mesh.app.ui.components.rememberHeadingReading
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Every real number behind the responder map's honesty claims, in one place -- the panel a field
 * tester reads before pressing SOS, rather than something someone has to `adb logcat` for during
 * a live test. Real values only, per this task's "no invented numbers" rule: an unmeasured figure
 * is shown as unmeasured, never a placeholder.
 *
 * Exported with exactly this signature so the hidden developer screen can wire it in without
 * knowing anything about how it gets its data -- see [com.setu.mesh.app.ui.dev.DiagnosticsScreen]
 * for the existing pattern this slots alongside.
 */
@Composable
fun PositionDiagnostics(modifier: Modifier = Modifier) {
    var selfFix by remember { mutableStateOf<SelfFix?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            selfFix = SetuService.selfFix()
            nowMillis = System.currentTimeMillis()
            delay(DIAGNOSTICS_POLL_INTERVAL_MILLIS)
        }
    }

    val headingReading = rememberHeadingReading(selfFix?.point)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Position diagnostics",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))

        DiagnosticLine("Raw fix", formatRawFixLine(selfFix))
        DiagnosticLine("Accuracy", selfFix?.accuracyMetres?.let { "±${it.roundToInt()} m" } ?: "unknown")
        DiagnosticLine("Provider", selfFix?.provider ?: "none")
        DiagnosticLine("Fix age", formatFixAgeLine(selfFix, nowMillis))
        DiagnosticLine("Declination", formatDeclinationLine(selfFix, nowMillis))
        DiagnosticLine("Compass accuracy", formatAccuracyStatusLine(headingReading.accuracyStatus))
        DiagnosticLine(
            "Estimated heading accuracy",
            headingReading.estimatedAccuracyDegrees?.let { "±${it.roundToInt()}°" } ?: "not reported by this sensor",
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Six decimal places is ~11 cm at the equator -- enough to see GPS jitter between polls without
 * implying precision the fix itself does not carry.
 */
private fun formatRawFixLine(selfFix: SelfFix?): String =
    selfFix?.let { "%.6f, %.6f".format(it.point.latitude, it.point.longitude) } ?: "no fix"

private fun formatFixAgeLine(selfFix: SelfFix?, nowMillis: Long): String =
    selfFix?.let { "${((nowMillis - it.atMillis) / 1000).coerceAtLeast(0)} s ago" } ?: "no fix"

/**
 * The exact declination [com.setu.mesh.app.ui.components.rememberHeadingReading] applies right
 * now, computed the identical way via [declinationDegrees] -- so a field tester sees the real
 * number instead of trusting that the true-north correction happened silently.
 */
private fun formatDeclinationLine(selfFix: SelfFix?, nowMillis: Long): String {
    val point = selfFix?.point ?: return "not applied -- no fix yet"
    val declination = declinationDegrees(point, nowMillis)
    return "%.1f° applied".format(declination)
}

private fun formatAccuracyStatusLine(accuracyStatus: Int): String = when (accuracyStatus) {
    SensorManager.SENSOR_STATUS_NO_CONTACT -> "no compass sensor"
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "unreliable -- needs calibration"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low -- needs calibration"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
    else -> "unknown ($accuracyStatus)"
}

private const val DIAGNOSTICS_POLL_INTERVAL_MILLIS = 1_000L
