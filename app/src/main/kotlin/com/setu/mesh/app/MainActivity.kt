package com.setu.mesh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.ui.MeshScreen
import com.setu.mesh.app.ui.SosScreen
import com.setu.mesh.app.ui.dev.DeveloperScreen
import com.setu.mesh.app.ui.theme.SetuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SetuTheme {
                SetuApp()
            }
        }
    }
}

@Composable
private fun SetuApp() {
    var serviceRunning by rememberSaveable { mutableStateOf(false) }

    if (!serviceRunning) {
        PermissionGate(onAllGranted = { serviceRunning = true })
    } else {
        RunningScreen()
    }
}

/**
 * The two audiences that get equal top-level billing (B9): the person sending an SOS, and the
 * person trying to help one. Mesh Lab (the judge demo) and Diagnostics (raw-radio bring-up) are
 * not here -- they moved behind a hidden long-press on the SOS screen's tier badge, see
 * [RunningScreen].
 */
private enum class Destination(val label: String) {
    SOS("SOS"),
    HELP_OTHERS("Help others"),
}

@Composable
private fun RunningScreen() {
    var destination by rememberSaveable { mutableStateOf(Destination.SOS) }
    var showDeveloper by rememberSaveable { mutableStateOf(false) }

    // Full-screen replacement, not a third nav destination -- reaching it at all requires the
    // hidden gesture, so it must never show up as a visible tab a panicking user could tap into
    // by accident.
    if (showDeveloper) {
        DeveloperScreen(onBack = { showDeveloper = false })
        return
    }

    Scaffold(
        // Bottom placement is deliberate: one-handed thumb reach for someone in a panic.
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = destination == dest,
                        onClick = { destination = dest },
                        icon = { NavIcon(dest, selected = destination == dest) },
                        label = { Text(dest.label) },
                        modifier = Modifier.semantics { contentDescription = dest.label },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (destination) {
                Destination.SOS -> SosScreen(onDeveloperEntry = { showDeveloper = true })
                Destination.HELP_OTHERS -> MeshScreen()
            }
        }
    }
}

/**
 * Hand-drawn nav glyphs: there is no `material-icons-extended` in the version catalog, so these
 * are two `Canvas` shapes rather than an icon-pack lookup. A single dot for the emergency side,
 * two linked dots for the responder side -- deliberately simple enough to read at nav-bar size.
 */
@Composable
private fun NavIcon(destination: Destination, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(24.dp)) {
        when (destination) {
            Destination.SOS -> drawCircle(color = color, radius = size.minDimension / 2.4f)
            Destination.HELP_OTHERS -> {
                val r = size.minDimension / 4.5f
                drawCircle(color = color, radius = r, center = Offset(size.width * 0.34f, size.height * 0.38f))
                drawCircle(color = color, radius = r, center = Offset(size.width * 0.66f, size.height * 0.62f))
            }
        }
    }
}
