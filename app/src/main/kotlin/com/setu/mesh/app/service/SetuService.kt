package com.setu.mesh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.setu.mesh.core.geo.distanceMetres
import com.setu.mesh.core.engine.ForwardingRecord
import com.setu.mesh.core.engine.MeshNode
import com.setu.mesh.core.engine.NodeSnapshot
import com.setu.mesh.core.model.GeoPoint
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
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    private var positionRefreshJob: Job? = null

    // ---- position-refresh bookkeeping (§6): budget is per outstanding-SOS episode, not per
    // lifetime of the service, so these reset on the null -> non-null transition of ownSos
    // rather than on every resend (a resend, auto or manual, keeps the same episode going).
    private var autoRefreshCount = 0
    private var lastAutoRefreshAtMillis = 0L
    private var hadOwnSosLastCheck = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        runningInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires explicit foreground service type at start time. LOCATION is
            // required alongside CONNECTED_DEVICE because AndroidNodeHost reads GPS for the
            // entire lifetime of this service, not only while a BLE link happens to be open --
            // see the matching manifest declaration.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
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
        // The UI may already have asked for attentive mode before this node existed.
        node.setAttentive(attentiveRequested)
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

        autoRefreshCount = 0
        lastAutoRefreshAtMillis = 0L
        hadOwnSosLastCheck = false
        positionRefreshJob = scope.launch {
            while (isActive) {
                delay(POSITION_REFRESH_CHECK_INTERVAL_MILLIS)
                maybeRefreshOwnSosPosition()
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
        positionRefreshJob?.cancel()
        positionRefreshJob = null
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

    /**
     * Corrects an outstanding own SOS's position without waiting for the user to touch
     * anything. RC1 is that the wire position is frozen at tap time; this is what unfreezes it
     * once a materially better fix shows up, within a budget so it cannot itself become a source
     * of mesh chatter.
     *
     * Resends when the live fix has moved beyond what both fixes' accuracy could plausibly
     * explain as noise, or when it has gotten meaningfully more precise -- subject to a cooldown
     * and a per-episode cap so a jittery GPS cannot retrigger this every 10 seconds forever.
     */
    private fun maybeRefreshOwnSosPosition() {
        val node = meshNode ?: return
        val host = androidNodeHost ?: return
        val ownSos = node.snapshot.value.ownSos

        if (ownSos == null) {
            hadOwnSosLastCheck = false
            autoRefreshCount = 0
            lastAutoRefreshAtMillis = 0L
            return
        }
        if (!hadOwnSosLastCheck) {
            // A fresh episode: either the very first SOS, or a new one after the last was marked
            // safe/delivered. The budget below belongs to this episode, not to the service's
            // whole lifetime.
            autoRefreshCount = 0
            lastAutoRefreshAtMillis = 0L
        }
        hadOwnSosLastCheck = true

        if (autoRefreshCount >= MAX_AUTO_REFRESHES_PER_SOS) return

        val nowMillis = System.currentTimeMillis()
        if (lastAutoRefreshAtMillis != 0L &&
            nowMillis - lastAutoRefreshAtMillis < MIN_AUTO_REFRESH_INTERVAL_MILLIS
        ) {
            return
        }

        val liveFix = host.lastFix() ?: return
        // No evidence this fix is any better than what is already on the wire -- nothing to act on.
        val nowAccuracy = liveFix.accuracyMetres ?: return

        // Unknown-on-the-wire (no fix at origination) behaves like "arbitrarily coarse" here: the
        // displacement test below is trivially satisfied by GeoPoint.UNKNOWN's placeholder
        // coordinates, and the improvement test always fires once nowAccuracy <= 30 m -- both are
        // exactly the outcome wanted when the original SOS carried no location at all.
        val txAccuracy = ownSos.senderAccuracyMetres ?: UNQUANTIFIED_TX_ACCURACY_METRES
        val displacementMetres = distanceMetres(ownSos.position, liveFix.point)

        val movedBeyondNoise = displacementMetres > hypot(txAccuracy, nowAccuracy.toDouble())
        val improvedSignificantly = nowAccuracy < txAccuracy / 2 && nowAccuracy <= IMPROVEMENT_ACCURACY_CEILING_METRES
        if (!movedBeyondNoise && !improvedSignificantly) return

        // Same resend path as a user-triggered triage edit: a fresh MessageId (so dedup cannot
        // suppress the correction) through originateSos, which already supersedes the previous
        // own message. See the rewritten comment on selfFix() below for why this is safe.
        node.originateSos(ownSos.flags, ownSos.souls, nowMillis)
        autoRefreshCount++
        lastAutoRefreshAtMillis = nowMillis
        Log.i(
            TAG_MESH,
            "Auto-refreshed own SOS position: displacement=${displacementMetres.roundToInt()}m " +
                "txAccuracy=${txAccuracy}m nowAccuracy=${nowAccuracy}m " +
                "(refresh $autoRefreshCount/$MAX_AUTO_REFRESHES_PER_SOS)",
        )
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

    /**
     * Same guard as `AndroidNodeHost.isDebuggable`: the debug-only always-relay override lives in
     * `:core` (`MeshNode.setAlwaysRelayOverride`), which cannot check `ApplicationInfo.FLAG_DEBUGGABLE`
     * itself without importing Android, so the check has to happen here, at the one call site
     * that can reach both a `Context` and the running node.
     */
    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

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

        // ---- position refresh (§6): see maybeRefreshOwnSosPosition ----

        /** How often the live fix is checked against what is on the wire. */
        private const val POSITION_REFRESH_CHECK_INTERVAL_MILLIS = 10_000L

        /** Floor between two auto-refreshes, regardless of how often better fixes show up. */
        private const val MIN_AUTO_REFRESH_INTERVAL_MILLIS = 120_000L

        /** Ceiling on auto-refreshes per outstanding-SOS episode, so a jittery GPS cannot turn
         *  this into a second source of mesh chatter alongside the user's own triage edits. */
        private const val MAX_AUTO_REFRESHES_PER_SOS = 3

        /**
         * Stand-in for "the wire carries no usable accuracy figure" (`senderAccuracyMetres ==
         * null`, i.e. positionAccuracyClass 0: no fix at origination, or one worse than 100 m).
         * Deliberately larger than any real class boundary so both refresh tests below resolve
         * the way they should when the original SOS went out with no location at all: the
         * displacement test is satisfied by `GeoPoint.UNKNOWN`'s placeholder coordinates already,
         * and this sentinel makes the improvement test fire too, as soon as any real fix accurate
         * to 30 m or better shows up.
         */
        private const val UNQUANTIFIED_TX_ACCURACY_METRES = 1_000.0

        /** "Improved significantly" additionally requires the new fix to be this good outright. */
        private const val IMPROVEMENT_ACCURACY_CEILING_METRES = 30f

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

        /** No-op when the mesh is not running. */
        fun setAttentive(active: Boolean) {
            attentiveRequested = active
            runningInstance?.meshNode?.setAttentive(active)
        }

        /**
         * Debug-only field-test aid: forces every relay decision to `RelayDecision.Relay` so a
         * field test is never at the mercy of `ForwardingPolicy`'s probabilistic roll -- see
         * `MeshNode.setAlwaysRelayOverride`. Shaped exactly like [setBatteryOverride]: a hard
         * no-op in a release build regardless of caller, and a no-op if the mesh is not running.
         * Unlike [setAttentive], not latched here -- there is no cold-start ordering problem to
         * solve, since a field tester only ever flips this after the mesh (and this screen) are
         * already up.
         */
        fun setAlwaysRelayOverride(active: Boolean) {
            val instance = runningInstance
            if (instance == null || !instance.isDebuggable()) {
                Log.w(TAG_MESH, "setAlwaysRelayOverride ignored: mesh not running, or release build")
                return
            }
            instance.meshNode?.setAlwaysRelayOverride(active)
        }

        /**
         * The last 32 relay decisions this node has made, newest first -- RC4's answer to "did
         * this node even decide to forward that beacon, and why not". Empty when the mesh is not
         * running. See `MeshNode.recentForwardingDecisions` and `ForwardingRecord`.
         */
        fun recentForwardingDecisions(): List<ForwardingRecord> =
            runningInstance?.meshNode?.recentForwardingDecisions() ?: emptyList()

        /**
         * Latched here, not merely forwarded. On a cold start `MainActivity.onStart()` runs
         * before the user has granted permissions and therefore before the mesh node exists, so
         * a plain forward is dropped on the floor -- and the node then sits un-attentive, at the
         * slow one-scan-per-epoch duty cycle, until the app happens to be backgrounded and
         * reopened. [startMesh] reads this when it builds the node.
         */
        @Volatile
        private var attentiveRequested: Boolean = false

        /**
         * Smoothed radio signal strength for [originRaw], or null when that origin has not been
         * heard first-hand recently -- which includes every report that arrived via a relay.
         * Null means "unknown", never "far": see `MeshNode.directSignalDbm`.
         */
        fun directSignalDbm(originRaw: Int): Int? =
            runningInstance?.meshNode?.directSignalDbm(originRaw)

        /**
         * How hard this phone is currently shouting, as the radio reported it back.
         *
         * The dBm figures are the **nominal** values Android documents for each
         * `ADVERTISE_TX_POWER_*` constant, not a measurement -- legacy advertising only reports
         * which constant took effect, and the actual radiated power depends on the controller
         * and the antenna. Labelled as nominal wherever it is shown, for that reason.
         */
        fun advertiseTxPowerDescription(): String? {
            val settings = runningInstance?.androidLink?.advertiseSettingsInEffect?.value ?: return null
            val (label, nominalDbm) = when (settings.txPowerLevel) {
                AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW -> "ULTRA_LOW" to -21
                AdvertiseSettings.ADVERTISE_TX_POWER_LOW -> "LOW" to -15
                AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM -> "MEDIUM" to -7
                AdvertiseSettings.ADVERTISE_TX_POWER_HIGH -> "HIGH" to 1
                else -> "UNKNOWN" to 0
            }
            val mode = when (settings.mode) {
                AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY -> "LOW_LATENCY"
                AdvertiseSettings.ADVERTISE_MODE_BALANCED -> "BALANCED"
                AdvertiseSettings.ADVERTISE_MODE_LOW_POWER -> "LOW_POWER"
                else -> "UNKNOWN"
            }
            return "TX $label (~$nominalDbm dBm nominal) · mode $mode"
        }

        /** Everything this node is currently carrying, for the responder view. Empty if not running. */
        fun carriedMessages(): List<com.setu.mesh.core.model.SosBeacon> =
            runningInstance?.meshNode?.carriedMessages() ?: emptyList()

        /**
         * This device's own last fix, accuracy and age included, for the responder map's footer
         * and the SOS screen's fix-quality line. Not on `NodeSnapshot` -- that type is `:core`
         * and has no reason to carry display-only accuracy, since `MeshNode` itself only ever
         * needs the point through `NodeHost.position()`.
         *
         * What this does NOT do any more is the whole story of what used to be forbidden here:
         * this comment previously said a new fix arriving must never trigger an automatic resend
         * of an outstanding SOS, on the theory that resending was unsafe. That theory was wrong
         * about *why* it would be unsafe. What is actually forbidden is reusing a `MessageId` --
         * the seen-set dedups on it, so a beacon reusing the same id is silently suppressed by
         * every peer that already relayed it, and the correction never reaches anyone -- and
         * leaving two live SOS from one person in a mesh built to conserve airtime. Neither
         * applies to [SetuService.maybeRefreshOwnSosPosition]: it mints a fresh id through the
         * same `MeshNode.originateSos` path a manual "tap to resend" uses, which already
         * supersedes the previous own message (`MeshNode.kt` around `originateSos`), so there is
         * still exactly one live SOS per person at any time. The budget and cooldown around that
         * call exist to bound airtime, not because re-origination itself is unsafe.
         */
        fun selfFix(): SelfFix? = runningInstance?.androidNodeHost?.lastFix()

        /**
         * This device's own last GPS fix, for the responder map to plot relative to. Reimplemented
         * on top of [selfFix] so there is one source of truth for "what is my position".
         */
        fun selfPosition(): com.setu.mesh.core.model.GeoPoint? = selfFix()?.point

        /**
         * The position and accuracy class actually broadcast in this node's current own SOS,
         * plus when the auto-refresh loop last corrected it -- read straight off
         * `NodeSnapshot.ownSos` rather than tracked in parallel, so it can never drift from what
         * is really on the wire. This is deliberately distinct from [selfFix]/[selfPosition]:
         * the gap between the two is exactly RC1 from the field test -- a phone's own screen can
         * honestly read "±10 m · 2s ago" while the beacon still on air carries a fix accepted
         * minutes earlier -- and that gap was invisible before this task. Null when the mesh is
         * not running or has no outstanding own SOS.
         */
        fun transmittedOwnPosition(): TransmittedPosition? {
            val ownSos = runningInstance?.meshNode?.snapshot?.value?.ownSos ?: return null
            val lastRefresh = runningInstance?.lastAutoRefreshAtMillis?.takeIf { it != 0L }
            return TransmittedPosition(
                position = ownSos.position,
                accuracyClass = ownSos.positionAccuracyClass,
                lastRefreshAtMillis = lastRefresh,
            )
        }
    }
}

/**
 * What [SetuService.transmittedOwnPosition] reports: the point and accuracy class actually on
 * the wire in this node's own outstanding SOS, and when the auto-refresh loop
 * (`SetuService.maybeRefreshOwnSosPosition`) last corrected it.
 */
data class TransmittedPosition(
    val position: GeoPoint,
    /** 0..3 per docs/PROTOCOL.md §2. See `SosBeacon.senderAccuracyMetres` for what it claims. */
    val accuracyClass: Int,
    /** Wall-clock millis of the last auto-refresh this episode, or null if none has fired yet. */
    val lastRefreshAtMillis: Long?,
)
