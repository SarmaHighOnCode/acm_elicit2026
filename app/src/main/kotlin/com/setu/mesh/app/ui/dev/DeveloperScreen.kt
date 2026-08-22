package com.setu.mesh.app.ui.dev

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.ui.lab.MeshLabScreen

private val DEV_TAB_TITLES = listOf("Mesh Lab", "Diagnostics")

/**
 * Hidden host for the two screens that fell out of the top-level nav in B9: the 100-node judge
 * demo and the raw-radio bring-up controls. Reached only by long-pressing the `TierBadge` on the
 * SOS screen -- deliberately with no on-screen hint that this exists, so it never distracts the
 * person the SOS screen is actually for.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = "Developer",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            PrimaryTabRow(selectedTabIndex = tab) {
                DEV_TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }

            when (tab) {
                0 -> MeshLabScreen()
                1 -> DiagnosticsScreen()
            }
        }
    }
}
