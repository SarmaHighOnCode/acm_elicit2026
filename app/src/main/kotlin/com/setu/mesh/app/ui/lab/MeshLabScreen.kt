package com.setu.mesh.app.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * In-app simulator: runs `:sim`'s `World` on the phone in your hand and renders it live. This
 * exists to demonstrate mesh-scale behaviour without needing a room full of phones -- but
 * everything on this screen is **simulated**, and the banner below says so persistently and is
 * never dismissable. Presenting a simulated hop as a live one is the single fastest way to lose
 * a technical judge's trust, and this screen is built to make that mistake structurally hard.
 */
@Composable
fun MeshLabScreen(viewModel: MeshLabViewModel = remember { MeshLabViewModel() }) {
    val snapshot by viewModel.snapshot.collectAsState()
    var drainSlider by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose { viewModel.shutdown() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            SimulatedBanner()

            MetricsStrip(snapshot)

            NodeGraphCanvas(
                nodes = snapshot.nodes,
                onTapNode = { id -> viewModel.kill(id) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
            )

            Controls(
                viewModel = viewModel,
                currentScenario = snapshot.scenario,
                drainValue = drainSlider,
                onDrainChange = { drainSlider = it },
                onApplyDrain = {
                    viewModel.drainAll(drainSlider.toDouble())
                    drainSlider = 0f
                },
            )
        }
    }
}

@Composable
private fun SimulatedBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SimulatedBannerColor)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "SIMULATED — not live radio data",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun MetricsStrip(snapshot: LabSnapshot) {
    val metrics = snapshot.metrics
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MetricValue("Delivery", metrics?.let { "%.0f%%".format(it.deliveryRatio * 100) } ?: "—")
        MetricValue("Alive", metrics?.let { "${it.totalNodes - it.deadAtEnd}/${it.totalNodes}" } ?: "—")
        MetricValue("Mean mAh", metrics?.let { "%.2f".format(it.energyMahMean) } ?: "—")
    }
}

@Composable
private fun MetricValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Controls(
    viewModel: MeshLabViewModel,
    currentScenario: String,
    drainValue: Float,
    onDrainChange: (Float) -> Unit,
    onApplyDrain: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            viewModel.scenarioNames.forEach { name ->
                FilterChip(
                    selected = currentScenario == name,
                    onClick = { viewModel.reset(name) },
                    label = { Text(name) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drain all", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = drainValue,
                onValueChange = onDrainChange,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("${(drainValue * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = onApplyDrain,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text("Apply drain", color = MaterialTheme.colorScheme.onSecondaryContainer)
        }

        Spacer(Modifier.height(12.dp))

        Row {
            Button(
                onClick = { viewModel.togglePause() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(if (viewModel.paused) "Resume" else "Pause", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { viewModel.reset(currentScenario) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Reset", color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Text(
            text = "Tap a node to kill it. Watch the mesh reroute.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val SimulatedBannerColor = Color(0xFFFFC107)
