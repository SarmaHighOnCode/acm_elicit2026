package com.setu.mesh.app.ui.dev

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.core.engine.ForwardingRecord
import com.setu.mesh.core.routing.RelayDecision
import com.setu.mesh.core.routing.SuppressReason
import kotlinx.coroutines.delay

/**
 * RC4's field-test panel: a three-phone test found that phone b never got a relay phone a heard
 * perfectly, and there was no way on real hardware to tell whether a had even decided to forward
 * it. [MeshNode.onBeaconHeard][com.setu.mesh.core.engine.MeshNode.onBeaconHeard] used to compute
 * that decision and throw it away; this renders the ring buffer it now keeps, one line per
 * beacon, so the question "what happened to that beacon" always has an answer here.
 *
 * Exported with exactly this signature so the hidden developer screen can wire it in without
 * knowing anything about how it gets its data -- see [PositionDiagnostics] and
 * [com.setu.mesh.app.ui.dev.DiagnosticsScreen] for the existing pattern this slots alongside.
 */
@Composable
fun RelayDiagnostics(modifier: Modifier = Modifier) {
    var decisions by remember { mutableStateOf<List<ForwardingRecord>>(emptyList()) }
    // Not read back from the running node -- there is no getter for the override's current state
    // any more than there is for AndroidNodeHost's battery override, so this mirrors that: local
    // UI state that resets to off whenever this screen is entered fresh, which is the safe
    // default for a field-test aid nobody should forget is on.
    var alwaysRelay by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            decisions = SetuService.recentForwardingDecisions()
            delay(RELAY_DIAGNOSTICS_POLL_INTERVAL_MILLIS)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Relay decisions",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))

        if (alwaysRelay) {
            Button(
                onClick = {
                    alwaysRelay = false
                    SetuService.setAlwaysRelayOverride(false)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            ) {
                Text("Always relay: ON -- tap to let the dice roll again", color = MaterialTheme.colorScheme.onSecondary)
            }
        } else {
            OutlinedButton(
                onClick = {
                    alwaysRelay = true
                    SetuService.setAlwaysRelayOverride(true)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Force every relay decision (debug only)", color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (decisions.isEmpty()) {
            Text(
                text = "No beacons heard yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            decisions.forEach { record ->
                Text(
                    text = formatForwardingLine(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorFor(record.decision),
                )
            }
        }
    }
}

/**
 * One line per [ForwardingRecord], e.g.
 * `SOS M-00001A2B from N-0000C3 · hops 1 · -71 dBm · SUPPRESS(PROBABILISTIC) p=0.09 · try 1/3`.
 * `try N/3` only appears for a [SuppressReason.PROBABILISTIC] suppression -- it is the only
 * reason `onBeaconHeard` ever reconsiders, so it is the only one a retry count means anything for.
 */
private fun formatForwardingLine(record: ForwardingRecord): String {
    val rssi = record.rssiDbm?.let { "$it dBm" } ?: "rssi unknown"
    val decisionText = when (val decision = record.decision) {
        is RelayDecision.Relay -> "RELAY p=${"%.2f".format(decision.probability)}"
        is RelayDecision.Suppress -> {
            val base = "SUPPRESS(${decision.reason})"
            if (decision.reason == SuppressReason.PROBABILISTIC) {
                "$base p=${"%.2f".format(decision.probability)} · try ${record.attempt}/$MAX_RECONSIDER_ATTEMPTS"
            } else {
                base
            }
        }
    }
    return "${record.type} ${record.messageId.short()} from ${record.origin.short()} " +
        "· hops ${record.hops} · $rssi · $decisionText"
}

private fun colorFor(decision: RelayDecision): Color = when (decision) {
    is RelayDecision.Relay -> RelayGreen
    is RelayDecision.Suppress -> when (decision.reason) {
        SuppressReason.MALFORMED -> MalformedRed
        else -> SuppressAmber
    }
}

private val RelayGreen = Color(0xFF66BB6A)
private val SuppressAmber = Color(0xFFFFA726)
private val MalformedRed = Color(0xFFEF5350)

private const val RELAY_DIAGNOSTICS_POLL_INTERVAL_MILLIS = 1_000L

/** Mirrors `MeshNode`'s private `MAX_RECONSIDER_ATTEMPTS` -- there is no public handle to the
 *  constant itself, same situation as the attentive-mode tests in `MeshNodeTest`. */
private const val MAX_RECONSIDER_ATTEMPTS = 3
