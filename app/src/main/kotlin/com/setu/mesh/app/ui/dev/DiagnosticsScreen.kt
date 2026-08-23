package com.setu.mesh.app.ui.dev

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.setu.mesh.app.data.GatewaySettings
import com.setu.mesh.app.service.SetuService

/**
 * The raw-radio test controls from B2/B3/B4. Kept, not deleted, because gate G3 (a beacon
 * actually crossing two phones) has not yet been verified on physical hardware -- these remain
 * the fastest way to debug `AndroidLink`/`BleAdvertiser`/`BleScanner` directly if that
 * verification turns up a problem, without the protocol layer in the way.
 *
 * Moved out of `MainActivity.kt` (B9): no longer a top-level tab, reachable only through the
 * hidden developer entry.
 */
@Composable
fun DiagnosticsScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .then(Modifier),
        ) {
            Text(
                text = "Raw radio diagnostics",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Bypasses MeshNode entirely -- for debugging AndroidLink/BleAdvertiser/BleScanner directly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            MeshControls()
            Spacer(Modifier.height(24.dp))
            GatewaySmsControls()
            Spacer(Modifier.height(24.dp))
            AdvertiserTestControls()
            Spacer(Modifier.height(24.dp))
            ScannerTestControls()
        }
    }
}

/**
 * Gate G3 controls: brings up a real `MeshNode` over a real `AndroidLink` and originates a
 * test SOS. Two phones both tapping "Start mesh" then one tapping "Originate test SOS" is the
 * whole bring-up test -- watch `adb logcat -s SetuMesh` on both to see the beacon cross.
 */
@Composable
private fun MeshControls() {
    val context = LocalContext.current

    fun send(action: String) {
        val intent = Intent(context, SetuService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    Text(
        text = "Mesh (B4/G3)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { send(SetuService.ACTION_START_MESH) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
        ),
    ) {
        Text("Start mesh", color = MaterialTheme.colorScheme.onSecondary)
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { send(SetuService.ACTION_ORIGINATE_TEST_SOS) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text("Originate test SOS", color = MaterialTheme.colorScheme.onPrimaryContainer)
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = { send(SetuService.ACTION_STOP_MESH) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Stop mesh", color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Gateway SMS fallback. Read by [SetuService.startMesh] at mesh start, so toggling this after
 * the mesh is already running has no effect until "Stop mesh" / "Start mesh" is pressed again --
 * that's a deliberate simplicity trade for a same-day feature, not a real settings screen.
 *
 * This device becomes the one node that texts an authority number the moment it is the first to
 * accept delivery of any SOS. Everywhere else in the mesh, "delivered" only ever meant a
 * neighbouring phone's screen; this is the one path that reaches outside it.
 */
@Composable
private fun GatewaySmsControls() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(GatewaySettings.isEnabled(context)) }
    var number by remember { mutableStateOf(GatewaySettings.getPhoneNumber(context)) }
    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasSmsPermission = granted }

    Text(
        text = "Gateway SMS fallback",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "When this node is the first to accept an SOS, text an authority number. " +
            "Requires SEND_SMS and a (re)start of the mesh to take effect.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Enable gateway mode", color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                GatewaySettings.setEnabled(context, it)
                if (it && !hasSmsPermission) {
                    permissionLauncher.launch(Manifest.permission.SEND_SMS)
                }
            },
        )
    }

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = number,
        onValueChange = {
            number = it
            GatewaySettings.setPhoneNumber(context, it)
        },
        label = { Text("Authority phone number") },
        modifier = Modifier.fillMaxWidth(),
    )

    if (!hasSmsPermission) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "SEND_SMS not granted -- alerts will be silently skipped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * B2 verification scaffolding. Drives the service's advertiser with known test patterns so the
 * bytes on air can be checked against nRF Connect on a second phone.
 */
@Composable
private fun AdvertiserTestControls() {
    val context = LocalContext.current

    fun send(action: String) {
        val intent = Intent(context, SetuService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    Text(
        text = "Advertiser test (B2)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { send(SetuService.ACTION_TEST_ONE) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text("Advertise 1 beacon", color = MaterialTheme.colorScheme.onPrimaryContainer)
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { send(SetuService.ACTION_TEST_MANY) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(
            text = "Advertise ${SetuService.TEST_BEACON_COUNT} beacons (carousel)",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = { send(SetuService.ACTION_TEST_STOP) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Stop advertising", color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * B3 verification scaffolding. Drives the service's scanner so hits can be checked against the
 * B2 advertiser test controls on a second phone, and so the 6-second rate limiter can be proven
 * out via logcat.
 */
@Composable
private fun ScannerTestControls() {
    val context = LocalContext.current

    fun send(action: String) {
        val intent = Intent(context, SetuService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    Text(
        text = "Scanner test (B3)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { send(SetuService.ACTION_TEST_SCAN) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text("Scan for 10s (check logcat)", color = MaterialTheme.colorScheme.onPrimaryContainer)
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = { send(SetuService.ACTION_TEST_SCAN_THROTTLE) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Throttle test (10x in 20s)", color = MaterialTheme.colorScheme.onSurface)
    }
}
