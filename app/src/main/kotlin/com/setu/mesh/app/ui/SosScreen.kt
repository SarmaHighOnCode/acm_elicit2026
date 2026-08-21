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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.ui.components.StatusLadder
import com.setu.mesh.app.ui.components.TierBadge
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.power.PowerTier
import java.util.Locale

/**
 * The screen a stranded person uses. Design constraint: they are panicking, possibly
 * one-handed, possibly in the dark or in water, phone at 4%. Every normal UI assumption is
 * wrong here, so the SOS button dominates the bottom half of the screen and sends immediately
 * with no form to fill in first -- triage refines afterwards, never blocks sending.
 */
@Composable
fun SosScreen(viewModel: SosViewModel = remember { SosViewModel() }) {
    val snapshot by viewModel.snapshot.collectAsState()

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
                if (snapshot != null) {
                    TierBadge(tier = snapshot!!.tier, lastGasp = snapshot!!.lastGasp)
                    Spacer(Modifier.height(20.dp))
                }

                if (viewModel.hasSentOnce) {
                    StatusLadder(
                        carrying = snapshot?.carrying ?: 0,
                        maxHops = snapshot?.ownSosMaxHops ?: 0,
                        delivered = snapshot?.ownSosDelivered ?: false,
                    )
                    Spacer(Modifier.height(24.dp))
                    TriageControls(viewModel)
                    Spacer(Modifier.height(24.dp))
                    EnergySummary(snapshot)
                } else {
                    Text(
                        text = if (snapshot == null) "Starting…" else "Tap SOS below to send your location and situation.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SosButton(
                sent = viewModel.hasSentOnce,
                onSend = { viewModel.sendSos() },
                onMarkSafe = { viewModel.markSafe() },
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
private fun TriageControls(viewModel: SosViewModel) {
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
        Severity.entries.forEach { severity ->
            SeverityChip(
                severity = severity,
                selected = viewModel.severity == severity,
                onClick = { viewModel.changeSeverity(severity) },
                modifier = Modifier.weight(1f),
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
        SoulsCounter(
            souls = viewModel.souls,
            onDecrement = { viewModel.changeSouls(viewModel.souls - 1) },
            onIncrement = { viewModel.changeSouls(viewModel.souls + 1) },
        )
    }

    Spacer(Modifier.height(16.dp))

    Column {
        ToggleRow("Trapped", viewModel.trapped) { viewModel.toggleTrapped() }
        ToggleRow("Medical need", viewModel.medicalNeed) { viewModel.toggleMedicalNeed() }
        ToggleRow("Water rising", viewModel.waterRising) { viewModel.toggleWaterRising() }
    }
}

@Composable
private fun SeverityChip(severity: Severity, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = severity.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        modifier = modifier.semantics { contentDescription = "Severity: ${severity.name}" },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Composable
private fun SoulsCounter(souls: Int, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CounterButton(symbol = "−", onClick = onDecrement, description = "Decrease people count")
        Text(
            text = souls.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        CounterButton(symbol = "+", onClick = onIncrement, description = "Increase people count")
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
private fun EnergySummary(snapshot: com.setu.mesh.core.engine.NodeSnapshot?) {
    val energy = snapshot?.energyMilliampHours ?: return
    if (energy <= 0.0) return
    Text(
        text = "SETU has used %.2f mAh and carried %d messages so far.".format(energy, snapshot.beaconsRelayed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
