package com.setu.mesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.app.ui.components.RelativeMap
import com.setu.mesh.app.ui.components.SosCard
import kotlinx.coroutines.delay

/**
 * The responder view: what a rescue worker or a bystander-with-signal sees. Sorted by triage
 * severity -- that ordering is a safety decision (see [sortForResponder]), not a display
 * preference.
 *
 * Own device at centre of [RelativeMap], known-position reports plotted by real bearing and
 * distance. No online map tiles are used regardless of what this screen shows -- an offline-mesh
 * app depending on the internet for its own map would be absurd, and would fail on stage.
 */
@Composable
fun MeshScreen(viewModel: MeshViewModel = remember { MeshViewModel() }) {
    val snapshot by SetuService.snapshot.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(MESH_REFRESH_INTERVAL_MILLIS)
        }
    }

    val now = System.currentTimeMillis()
    val sorted = sortForResponder(viewModel.carried)
    val known = sorted.filter { hasKnownPosition(it) }
    val unknown = sorted.filter { !hasKnownPosition(it) }
    val selfFix = viewModel.selfFix
    val selfPosition = selfFix?.point

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (sorted.isEmpty()) {
            EmptyState(snapshot?.tier?.name, snapshot?.neighbourCount ?: 0)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (known.isNotEmpty()) {
                    item { SectionHeader("Position known") }
                    if (selfPosition != null) {
                        item {
                            RelativeMap(
                                self = selfPosition,
                                beacons = known,
                                onTapBeacon = {},
                                nowMillis = now,
                                selfFix = selfFix,
                            )
                        }
                    } else {
                        item {
                            Text(
                                "Waiting for your own GPS fix to plot relative positions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(known) { beacon ->
                        SosCard(
                            beacon = beacon,
                            nowMillis = now,
                            selfFix = selfFix,
                            signalDbm = viewModel.signalDbmByOrigin[beacon.origin.raw],
                        )
                    }
                }
                if (unknown.isNotEmpty()) {
                    item { SectionHeader("Position unknown") }
                    items(unknown) { beacon ->
                        SosCard(
                            beacon = beacon,
                            nowMillis = now,
                            selfFix = selfFix,
                            signalDbm = viewModel.signalDbmByOrigin[beacon.origin.raw],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EmptyState(tier: String?, neighbourCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No SOS received. SafeHop is listening.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (tier != null) "Tier $tier · $neighbourCount nearby" else "Mesh not started",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val MESH_REFRESH_INTERVAL_MILLIS = 2_000L
