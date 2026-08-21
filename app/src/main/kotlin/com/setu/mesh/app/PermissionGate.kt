package com.setu.mesh.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.setu.mesh.app.service.SetuService

/**
 * Permission gate composable. Requests each required permission in order, surfaces
 * hardware capability checks (adapter present, BLE advertiser available), and only
 * then offers the button to start the foreground service.
 *
 * Every API-level-specific request is guarded with a Build.VERSION.SDK_INT check so
 * we never request a permission that doesn't exist on the running device.
 */
@Composable
fun PermissionGate(onAllGranted: () -> Unit) {
    val context = LocalContext.current

    // -- Hardware checks (non-permission) --
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    val hasAdapter = adapter != null
    val advertiserAvailable = adapter?.bluetoothLeAdvertiser != null

    // -- Permission state --
    var permissionsGranted by remember { mutableStateOf(false) }
    var bluetoothEnabled by remember { mutableStateOf(adapter?.isEnabled == true) }

    // Recalculate permission state
    fun recheckPermissions(): Boolean {
        val needed = buildRequiredPermissions()
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        permissionsGranted = recheckPermissions()
        bluetoothEnabled = adapter?.isEnabled == true
    }

    // -- Permission launcher --
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = recheckPermissions()
        bluetoothEnabled = adapter?.isEnabled == true
    }

    // -- Bluetooth enable launcher --
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        bluetoothEnabled = adapter?.isEnabled == true
        permissionsGranted = recheckPermissions()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "SETU",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Emergency Mesh Relay",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            // -- Hardware status --
            StatusRow(
                label = "Bluetooth adapter",
                ok = hasAdapter,
                detail = if (hasAdapter) "Present" else "Not found — BLE unavailable",
            )

            StatusRow(
                label = "BLE advertiser",
                ok = advertiserAvailable,
                detail = if (!hasAdapter) "—"
                else if (advertiserAvailable) "Available"
                else "Null — this device cannot advertise beacons",
            )

            StatusRow(
                label = "Bluetooth enabled",
                ok = bluetoothEnabled,
                detail = if (!hasAdapter) "—"
                else if (bluetoothEnabled) "On"
                else "Off — tap below to enable",
            )

            StatusRow(
                label = "Permissions",
                ok = permissionsGranted,
                detail = if (permissionsGranted) "All granted" else "Some missing — tap below",
            )

            Spacer(Modifier.height(24.dp))

            // -- Action buttons --
            if (!permissionsGranted) {
                Button(
                    onClick = {
                        val needed = buildRequiredPermissions()
                        permissionLauncher.launch(needed.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Text("Grant permissions", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
            }

            if (hasAdapter && !bluetoothEnabled) {
                Button(
                    onClick = {
                        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        enableBluetoothLauncher.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Text("Enable Bluetooth", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
            }

            if (permissionsGranted && bluetoothEnabled && hasAdapter) {
                Button(
                    onClick = {
                        val serviceIntent = Intent(context, SetuService::class.java)
                        ContextCompat.startForegroundService(context, serviceIntent)
                        onAllGranted()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Text("Start SETU relay", color = MaterialTheme.colorScheme.onSecondary)
                }
            }

            if (!hasAdapter) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "This device has no Bluetooth adapter. SETU requires BLE hardware.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!advertiserAvailable && bluetoothEnabled) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Warning: BluetoothLeAdvertiser is null. This device can receive " +
                        "beacons but cannot broadcast them. It will not be able to relay SOS messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (ok) "✓" else "✗",
            color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Builds the list of permissions to request, filtered by API level.
 * Requesting a permission that doesn't exist on the running API level throws.
 */
private fun buildRequiredPermissions(): List<String> = buildList {
    // Android 13+ notification permission
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Android 12+ granular Bluetooth permissions
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    // GPS for SOS beacon position
    add(Manifest.permission.ACCESS_FINE_LOCATION)
}
