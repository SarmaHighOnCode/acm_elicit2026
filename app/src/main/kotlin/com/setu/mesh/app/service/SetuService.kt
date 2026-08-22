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
import com.setu.mesh.app.data.NodeIdentity
import com.setu.mesh.core.engine.MeshNode
import com.setu.mesh.core.engine.NodeSnapshot
import com.setu.mesh.core.model.MessageId
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SituationFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    // ---------------------------------------------------------------- B5: the real mesh node

    private var androidNodeHost: AndroidNodeHost? = null
    private var androidLink: AndroidLink? = null
    private var meshNode: MeshNode? = null
    private var meshJob: Job? = null
    private var notificationJob: Job? = null
    private var snapshotJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        runningInstance = this
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
     * that differs here is which `Link` it is handed. Node identity is persisted via
     * [NodeIdentity] so it survives a service restart.
     */
    private fun startMesh() {
        if (meshNode != null) {
            Log.i(TAG_MESH, "Mesh already running")
            return
        }
        val nodeId = NodeIdentity.get(this)
        val host = AndroidNodeHost(this)
        val link = AndroidLink(this, scope)
        val node = MeshNode(nodeId, link, host)

        androidNodeHost = host
        androidLink = link
        meshNode = node
        _snapshot.value = null

        meshJob = scope.launch { node.run() }

        // Snapshot propagation and notification refresh are deliberately two separate
        // coroutines. Doing both in one collect with a trailing delay throttles the *snapshot*
        // too, because node.snapshot is a conflated StateFlow: the collector sleeps, intermediate
        // values are dropped, and the UI only learns about state changes every few seconds. That
        // is unacceptable on the SOS screen, where the whole point is that tapping SOS visibly
        // does something immediately.
        snapshotJob = scope.launch {
            node.snapshot.collect { snap ->
                _snapshot.value = snap
                Log.i(
                    TAG_MESH,
                    "node=${nodeId.short()} tier=${snap.tier} battery=${snap.batteryPercent}% " +
                        "carrying=${snap.carrying} neighbours=${snap.neighbourCount} " +
                        "advertising=${snap.advertising} scanning=${snap.scanning} " +
                        "ownSosDelivered=${snap.ownSosDelivered}",
                )
            }
        }

        // Notifications, by contrast, genuinely are worth rate-limiting: churning the system
        // notification on every protocol tick is itself a battery cost, which would be an
        // embarrassing thing to get wrong in this particular app.
        notificationJob = scope.launch {
            while (isActive) {
                _snapshot.value?.let { updateNotification(it) }
                delay(NOTIFICATION_UPDATE_MIN_INTERVAL_MILLIS)
            }
        }

        Log.i(TAG_MESH, "Mesh started for node ${nodeId.short()}")
    }

    private fun stopMesh() {
        meshJob?.cancel()
        meshJob = null
        notificationJob?.cancel()
        notificationJob = null
        snapshotJob?.cancel()
        snapshotJob = null
        val link = androidLink
        androidLink = null
        androidNodeHost?.shutdown()
        androidNodeHost = null
        meshNode = null
        _snapshot.value = null
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
        if (runningInstance === this) runningInstance = null
        super.onDestroy()
    }

    private fun updateNotification(snapshot: NodeSnapshot) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
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

    /**
     * With no [snapshot] yet (service just started, mesh not brought up) this is the generic
     * "relay active" text from strings.xml. Once the mesh is running it shows
     * "SafeHop · RELAY — carrying 3 · 5 nearby", matching `docs/tasks/B5-node-host-and-service.md`.
     */
    private fun buildNotification(snapshot: NodeSnapshot? = null): Notification {
        val text = if (snapshot != null) {
            "${snapshot.tier} — carrying ${snapshot.carrying} · ${snapshot.neighbourCount} nearby"
        } else {
            getString(R.string.notification_text)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
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

        // ---- B5: the real mesh node ----

        const val ACTION_START_MESH = "com.setu.mesh.app.action.START_MESH"
        const val ACTION_STOP_MESH = "com.setu.mesh.app.action.STOP_MESH"
        const val ACTION_ORIGINATE_TEST_SOS = "com.setu.mesh.app.action.ORIGINATE_TEST_SOS"

        private const val TAG_MESH = "SetuMesh"

        /** Notification churn is itself a battery cost; this is deliberately not per-tick. */
        private const val NOTIFICATION_UPDATE_MIN_INTERVAL_MILLIS = 3_000L

        private val _snapshot = MutableStateFlow<NodeSnapshot?>(null)

        /**
         * Live snapshot of the running node, or null when the mesh is not started. This is the
         * singleton-holder half of the B5 spec's "expose via a binder or a singleton holder" —
         * B6/B7's `ViewModel`s collect this directly rather than binding to the service.
         */
        val snapshot: StateFlow<NodeSnapshot?> = _snapshot.asStateFlow()

        /** The one running instance, so the companion API below can reach its live `MeshNode`. */
        @Volatile
        private var runningInstance: SetuService? = null

        /** Null when the mesh is not running -- callers must check before assuming an id exists. */
        fun originateSos(flags: SituationFlags, souls: Int): MessageId? =
            runningInstance?.meshNode?.originateSos(flags, souls)

        fun markSafe() {
            runningInstance?.meshNode?.markSafe()
        }

        /** Debug-only; see `AndroidNodeHost.setBatteryOverride`. No-op if the mesh isn't running. */
        fun setBatteryOverride(percent: Int?) {
            runningInstance?.androidNodeHost?.setBatteryOverride(percent)
        }

        /** Everything this node is currently carrying, for the responder view. Empty if not running. */
        fun carriedMessages(): List<com.setu.mesh.core.model.SosBeacon> =
            runningInstance?.meshNode?.carriedMessages() ?: emptyList()

        /**
         * This device's own last GPS fix, for the responder map to plot relative to. Not on
         * `NodeSnapshot` -- that type is `:core` and has no reason to carry raw position, since
         * `MeshNode` itself only ever needs it indirectly through `NodeHost`.
         */
        fun selfPosition(): com.setu.mesh.core.model.GeoPoint? =
            runningInstance?.androidNodeHost?.position()
    }
}
