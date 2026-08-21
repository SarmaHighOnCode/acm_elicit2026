package com.setu.mesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.app.ui.components.StatusLadder
import com.setu.mesh.app.ui.components.TierBadge
import com.setu.mesh.core.engine.NodeSnapshot
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SituationFlags
import java.util.Locale

/**
 * The screen a stranded person uses. Design constraint: they are panicking, possibly
 * one-handed, possibly in the dark or in water, phone at 4%. Every normal UI assumption is
 * wrong here, so the SOS button dominates the bottom third of the screen and sends immediately
 * with no form to fill in first -- triage refines afterwards, never blocks sending.
 *
 * State lives here rather than in a ViewModel, for two specific reasons:
 *
 *  - **"Is an SOS outstanding?" is derived from the protocol, never mirrored.** It is exactly
 *    `snapshot.ownSos != null`. An earlier version tracked it as a separate UI boolean, which
 *    could desync from reality -- on rotation, or after a service restart, the screen would show
 *    "not sent" while the mesh was still broadcasting the SOS. In an emergency app that is the
 *    worst possible class of bug, so the mirror is gone.
 *  - **Triage inputs use `rememberSaveable`**, so a rotation does not silently reset someone's
 *    "trapped / water rising" answers back to defaults.
 */
@Composable
fun SosScreen() {
    val snapshot by SetuService.snapshot.collectAsState()

    var severity by rememberSaveable { mutableStateOf(Severity.HIGH) }
    var souls by rememberSaveable { mutableIntStateOf(1) }
    var trapped by rememberSaveable { mutableStateOf(false) }
    var medicalNeed by rememberSaveable { mutableStateOf(false) }
    var waterRising by rememberSaveable { mutableStateOf(false) }

    // Single source of truth: the node either holds an outstanding own SOS or it does not.
    val sosActive = snapshot?.ownSos != null

    fun flags() = SituationFlags(
        severity = severity,
        trapped = trapped,
        medicalNeed = medicalNeed,
        waterRising = waterRising,
    )

    // Editing triage before the first send is free; after it, each edit re-sends so the mesh
    // carries the corrected situation rather than the stale one.
    fun resendIfActive() {
        if (sosActive) SetuService.originateSos(flags(), souls)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                snapshot?.let { TierBadge(tier = it.tier, lastGasp = it.lastGasp) }
                if (snapshot != null) Spacer(Modifier.height(20.dp))

                if (sosActive) {
                    StatusLadder(
                        carrying = snapshot?.carrying ?: 0,
                        maxHops = snapshot?.ownSosMaxHops ?: 0,
                        delivered = snapshot?.ownSosDelivered ?: false,
                    )
                    Spacer(Modifier.height(24.dp))
                } else {
                    Text(
                        text = if (snapshot == null) "Starting…" else "Tap SOS below to send your location and situation.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }

                TriageControls(
                    severity = severity,
                    souls = souls,
                    trapped = trapped,
                    medicalNeed = medicalNeed,
                    waterRising = waterRising,
                    onSeverity = { severity = it; resendIfActive() },
                    onSouls = { souls = it.coerceIn(1, 255); resendIfActive() },
                    onTrapped = { trapped = !trapped; resendIfActive() },
                    onMedical = { medicalNeed = !medicalNeed; resendIfActive() },
                    onWater = { waterRising = !waterRising; resendIfActive() },
                )

                if (sosActive) {
                    Spacer(Modifier.height(24.dp))
                    EnergySummary(snapshot)
                }
            }

            SosButton(
                sent = sosActive,
                onSend = { SetuService.originateSos(flags(), souls) },
                onMarkSafe = { SetuService.markSafe() },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.32f),
            )
        }
    }
}

@Composable
private fun SosButton(
    sent: Boolean,
    onSend: () -> Unit,
    onMarkSafe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (sent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
    val label = if (sent) "SOS SENT — TAP TO RESEND" else "SOS"

    Box(
        modifier = modifier
            .padding(16.dp)
            .background(color, RoundedCornerShape(24.dp))
            .clickable { onSend() }
            .semantics { contentDescription = "Send emergency SOS" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondary,
            )
            if (sent) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "I am safe now",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier
                        .clickable { onMarkSafe() }
                        .semantics { contentDescription = "Mark yourself safe, cancelling the SOS" },
                )
            }
        }
    }
}

@Composable
private fun TriageControls(
    severity: Severity,
    souls: Int,
    trapped: Boolean,
    medicalNeed: Boolean,
    waterRising: Boolean,
    onSeverity: (Severity) -> Unit,
    onSouls: (Int) -> Unit,
    onTrapped: () -> Unit,
    onMedical: () -> Unit,
    onWater: () -> Unit,
) {
    Text(
        text = "Situation",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Severity.entries.forEach { value ->
            FilterChip(
                selected = severity == value,
                onClick = { onSeverity(value) },
                label = {
                    Text(
                        text = value.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Severity: ${value.name}" },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "People here",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            CounterButton("−", { onSouls(souls - 1) }, "Decrease people count")
            Text(
                text = souls.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            CounterButton("+", { onSouls(souls + 1) }, "Increase people count")
        }
    }

    Spacer(Modifier.height(16.dp))

    Column {
        ToggleRow("Trapped", trapped, onTrapped)
        ToggleRow("Medical need", medicalNeed, onMedical)
        ToggleRow("Water rising", waterRising, onWater)
    }
}

@Composable
private fun CounterButton(symbol: String, onClick: () -> Unit, description: String) {
    Box(
        modifier = Modifier
            .height(56.dp)
            .width(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = "$label, ${if (checked) "on" else "off"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .width(28.dp)
                .background(
                    if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Only rendered once real numbers exist. A placeholder here would look like a real measurement,
 * which is exactly what `docs/POWER.md` warns against doing anywhere in this project.
 */
@Composable
private fun EnergySummary(snapshot: NodeSnapshot?) {
    val energy = snapshot?.energyMilliampHours ?: return
    if (energy <= 0.0) return
    Text(
        text = "SETU has used %.2f mAh and carried %d messages so far.".format(energy, snapshot.beaconsRelayed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
