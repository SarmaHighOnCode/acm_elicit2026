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
import com.setu.mesh.app.ble.AndroidLink
import com.setu.mesh.app.ble.BEACON_SIZE_BYTES
import com.setu.mesh.app.ble.BleAdvertiser
import com.setu.mesh.app.ble.BleScanner
import com.setu.mesh.core.engine.MeshNode
import com.setu.mesh.core.engine.NodeHost
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SituationFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Keeps the BLE relay alive when the screen is off. Android will kill a background BLE
 * scanner within minutes without a foreground service, and the mesh dies with it.
 *
 * The service owns the radio. [advertiser]/[scanner] remain for the B2/B3 test actions below,
 * which are the fastest way to confirm raw bytes are actually on air with nRF Connect. [meshNode]
 * is the real path: an `AndroidLink` wrapping the same two radios, driven by `MeshNode.run()`
 * exactly as `:sim` drives it, which is what makes gate G3 (a real beacon crossing two phones)
 * the same code path as the 200-node simulator.
 */
class SetuService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val advertiser by lazy { BleAdvertiser(this) }
    private val scanner by lazy { BleScanner(this) }

    /** Tracks the currently running test scan/throttle job so a repeat tap replaces it cleanly. */
    private var scanTestJob: Job? = null

    // ---------------------------------------------------------------- B4/G3: the real mesh node

    /**
     * Stand-in for the real `AndroidNodeHost` that B5 will build (reading `BatteryManager` and
     * GPS). Fixed at full battery, not charging, no fix yet -- exactly what
     * `docs/tasks/B4-android-link.md` calls for so `MeshNode.run()` can be exercised for real
     * before B5 lands. `hasTrustedClock() = true` assumes the phone's own clock is NTP-synced,
     * which is the common case and is what lets rendezvous phase-lock without waiting on drift
     * correction during this test.
     */
    private object StubNodeHost : NodeHost {
        override fun nowMillis(): Long = System.currentTimeMillis()
        override fun batteryPercent(): Int = 100
        override fun isCharging(): Boolean = false
        override fun position(): GeoPoint? = null
        override fun hasTrustedClock(): Boolean = true
    }

    /**
     * Fresh per service instance -- fine for a two-phone bring-up test, but two phones must
     * not collide, so it is randomly seeded rather than fixed. B5 persists this in
     * `SharedPreferences` via `NodeIdentity` so it survives restarts.
     */
    private val nodeId: NodeId by lazy { NodeId.fromSeed(UUID.randomUUID().toString()) }

    private var androidLink: AndroidLink? = null
    private var meshNode: MeshNode? = null
    private var meshJob: Job? = null

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

            ACTION_START_MESH -> startMesh()
            ACTION_STOP_MESH -> stopMesh()
            ACTION_ORIGINATE_TEST_SOS -> originateTestSos()
        }

        return START_STICKY
    }

    /**
     * Gate G3: brings up a real `MeshNode` over a real `AndroidLink` and starts its run loop.
     * This is the same `MeshNode.run()` that `:sim` drives against `SimLink` -- the only thing
     * that differs here is which `Link` it is handed.
     */
    private fun startMesh() {
        if (meshNode != null) {
            Log.i(TAG_MESH, "Mesh already running for node ${nodeId.short()}")
            return
        }
        val link = AndroidLink(this, scope)
        val node = MeshNode(nodeId, link, StubNodeHost)
        androidLink = link
        meshNode = node

        meshJob = scope.launch { node.run() }

        // Periodic snapshot logging is the only observability this bring-up test needs --
        // the real UI (B6/B7) reads `MeshNode.snapshot` directly instead of logcat.
        scope.launch {
            node.snapshot.collect { snap ->
                Log.i(
                    TAG_MESH,
                    "node=${nodeId.short()} tier=${snap.tier} battery=${snap.batteryPercent}% " +
                        "carrying=${snap.carrying} neighbours=${snap.neighbourCount} " +
                        "advertising=${snap.advertising} scanning=${snap.scanning} " +
                        "ownSosDelivered=${snap.ownSosDelivered}",
                )
            }
        }

        Log.i(TAG_MESH, "Mesh started for node ${nodeId.short()}")
    }

    private fun stopMesh() {
        meshJob?.cancel()
        meshJob = null
        val link = androidLink
        androidLink = null
        meshNode = null
        if (link != null) {
            scope.launch { link.shutdown() }
        }
        Log.i(TAG_MESH, "Mesh stopped")
    }

    /** Originates a test SOS on the running mesh node, for the G3 two-phone bring-up test. */
    private fun originateTestSos() {
        val node = meshNode
        if (node == null) {
            Log.w(TAG_MESH, "Cannot originate SOS: mesh is not running, call ACTION_START_MESH first")
            return
        }
        val messageId = node.originateSos(SituationFlags(severity = Severity.HIGH), souls = 1)
        Log.i(TAG_MESH, "Originated test SOS $messageId")
    }

    override fun onDestroy() {
        stopMesh()
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

        // ---- B4/G3: the real mesh node ----

        const val ACTION_START_MESH = "com.setu.mesh.app.action.START_MESH"
        const val ACTION_STOP_MESH = "com.setu.mesh.app.action.STOP_MESH"
        const val ACTION_ORIGINATE_TEST_SOS = "com.setu.mesh.app.action.ORIGINATE_TEST_SOS"

        private const val TAG_MESH = "SetuMesh"
    }
}
