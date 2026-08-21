package com.setu.mesh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.setu.mesh.app.R
import com.setu.mesh.app.ble.BEACON_SIZE_BYTES
import com.setu.mesh.app.ble.BleAdvertiser
import com.setu.mesh.app.ble.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the BLE relay alive when the screen is off. Android will kill a background BLE
 * scanner within minutes without a foreground service, and the mesh dies with it.
 *
 * The service owns the radio. From B5 it will also own the single `MeshNode` and drive
 * [advertiser] from `MeshNode.beaconsToAdvertise()` with the mode and TX power chosen by the
 * power governor. For B2 the ownership is real but the beacons are test patterns.
 */
class SetuService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val advertiser by lazy { BleAdvertiser(this) }
    private val scanner by lazy { BleScanner(this) }

    /** Tracks the currently running test scan/throttle job so a repeat tap replaces it cleanly. */
    private var scanTestJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires explicit foreground service type at start time
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            ACTION_TEST_ONE -> advertiser.setBeacons(listOf(testBeacon(0)), TEST_MODE, TEST_TX_POWER)
            ACTION_TEST_MANY ->
                advertiser.setBeacons(List(TEST_BEACON_COUNT, ::testBeacon), TEST_MODE, TEST_TX_POWER)
            ACTION_TEST_STOP -> advertiser.stop()

            ACTION_TEST_SCAN -> {
                scanTestJob?.cancel()
                scanTestJob = scope.launch {
                    val collector = launch {
                        scanner.results.collect { hit ->
                            // Logged as "address rssi hex" so it can be diffed byte-for-byte
                            // against the advertising phone's "→ air <hex>" log from B2.
                            Log.i(
                                TAG_SCAN_TEST,
                                "${hit.address} ${hit.rssiDbm}dBm ${hit.payload.toHex()}",
                            )
                        }
                    }
                    Log.i(TAG_SCAN_TEST, "Single scan window: ${SCAN_TEST_WINDOW_MILLIS}ms")
                    scanner.scanFor(SCAN_TEST_WINDOW_MILLIS, ScanSettings.SCAN_MODE_LOW_LATENCY)
                    Log.i(TAG_SCAN_TEST, "Scan window closed")
                    collector.cancel()
                }
            }

            ACTION_TEST_SCAN_THROTTLE -> {
                scanTestJob?.cancel()
                scanTestJob = scope.launch {
                    val collector = launch {
                        scanner.results.collect { hit ->
                            Log.i(
                                TAG_SCAN_TEST,
                                "${hit.address} ${hit.rssiDbm}dBm ${hit.payload.toHex()}",
                            )
                        }
                    }
                    // The spec's throttle test: call scanFor ten times spread across twenty
                    // seconds. Each attempt is launched on its own two-second tick rather than
                    // awaited in sequence, so a call's own window duration never distorts the
                    // cadence of the *next* attempt — this is what "ten calls in twenty seconds"
                    // is actually testing. With the 6-second minimum gap, only ~3-4 of these
                    // should ever reach the radio; the rest must be dropped and logged loudly,
                    // never passed through — passing one through risks the real
                    // SCAN_FAILED_SCANNING_TOO_FREQUENTLY code.
                    repeat(THROTTLE_TEST_ATTEMPTS) { attempt ->
                        launch {
                            Log.i(TAG_SCAN_TEST, "Throttle test attempt ${attempt + 1}/$THROTTLE_TEST_ATTEMPTS")
                            scanner.scanFor(THROTTLE_TEST_WINDOW_MILLIS, ScanSettings.SCAN_MODE_LOW_LATENCY)
                        }
                        delay(THROTTLE_TEST_INTERVAL_MILLIS)
                    }
                    // Let the last-accepted attempt's window finish before tearing the collector down.
                    delay(THROTTLE_TEST_WINDOW_MILLIS)
                    collector.cancel()
                    Log.i(TAG_SCAN_TEST, "Throttle test complete")
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        advertiser.stop()
        scanner.stop()
        scanTestJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "setu_relay"
        const val NOTIFICATION_ID = 1

        // ---- B2 scaffolding: replaced in B5 by MeshNode.beaconsToAdvertise() ----

        const val ACTION_TEST_ONE = "com.setu.mesh.app.action.TEST_ONE"
        const val ACTION_TEST_MANY = "com.setu.mesh.app.action.TEST_MANY"
        const val ACTION_TEST_STOP = "com.setu.mesh.app.action.TEST_STOP"

        /** More than any handset has slots, so the carousel is always exercised. */
        const val TEST_BEACON_COUNT = 6

        // Loud and fast so a second phone finds it immediately. In B5 both come from the
        // RadioPlan the power governor produces, not from constants.
        private const val TEST_MODE = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        private const val TEST_TX_POWER = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

        /**
         * Byte 0 is `0xA0 + index` so the rotating carousel slot is identifiable at a glance in
         * nRF Connect; the remaining 23 bytes are a `01..17` ramp that is trivial to eyeball.
         */
        private fun testBeacon(index: Int): ByteArray =
            ByteArray(BEACON_SIZE_BYTES) { i -> if (i == 0) (0xA0 + index).toByte() else i.toByte() }

        // ---- B3 scaffolding: replaced in B5 by MeshNode-driven scan windows ----

        const val ACTION_TEST_SCAN = "com.setu.mesh.app.action.TEST_SCAN"
        const val ACTION_TEST_SCAN_THROTTLE = "com.setu.mesh.app.action.TEST_SCAN_THROTTLE"

        private const val TAG_SCAN_TEST = "SetuScanTest"

        /** Long enough to catch a slow advertiser's carousel rotation going by once or twice. */
        private const val SCAN_TEST_WINDOW_MILLIS = 10_000L

        // The spec's throttle test verbatim: ten scanFor attempts, spread across twenty seconds.
        private const val THROTTLE_TEST_ATTEMPTS = 10
        private const val THROTTLE_TEST_INTERVAL_MILLIS = 2_000L // 10 x 2s = 20s span
        private const val THROTTLE_TEST_WINDOW_MILLIS = 2_000L // short: keeps windows non-overlapping

        private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    }
}
