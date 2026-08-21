package com.setu.mesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** One beacon heard on air. [atMillis] is wall clock — the rendezvous scheduler needs absolute time. */
data class ScanHit(
    val payload: ByteArray,
    val address: String,
    val rssiDbm: Int,
    val atMillis: Long,
)

/** The most recent reason the radio refused to scan (or a mapped `onScanFailed` code), for the UI. */
data class ScanFailure(
    val code: Int,
    val reason: String,
    val atMillis: Long,
)

/**
 * Listens for 24-byte SETU beacons in bounded windows: start, wait [ScanHit]s for a fixed
 * duration, stop. Never runs continuously — scanning is roughly 100x the cost of advertising,
 * which is the whole reason SETU scans in short scheduled windows instead of leaving the radio
 * open (`docs/POWER.md` §1-2).
 *
 * This class is deliberately stupid, same as [BleAdvertiser]: it moves opaque bytes off the
 * radio and nothing else. It does not deduplicate, does not decide when to scan, and does not
 * touch TTL — that is routing and it lives in `:core` (`SeenSet`). Two identical payloads from
 * two different addresses are both passed up unchanged; that duplication feeds density damping
 * upstream and collapsing it here would destroy that signal.
 *
 * The rate limiter below is the reason this class exists as its own thing rather than three
 * lines wrapping `BluetoothLeScanner`. Android silently throttles an app to 5 `startScan` calls
 * per 30 seconds. Blow through it and there is no exception, no failure callback, and no
 * results — the code looks correct and simply never finds anything. A 6-second minimum gap
 * between starts keeps every caller comfortably under that budget, and the 60-second rendezvous
 * epoch this feeds is itself sized around the same limit — see `docs/PROTOCOL.md` §1-2.
 */
class BleScanner(context: Context) {

    private val appContext = context.applicationContext

    // Resolved on every access rather than cached, exactly like BleAdvertiser: the adapter can
    // be off when the service starts and come up later, and bluetoothLeScanner is null the
    // whole time it is off.
    private val scanner: BluetoothLeScanner?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    private val _results = MutableSharedFlow<ScanHit>(extraBufferCapacity = RESULTS_BUFFER_CAPACITY)

    /**
     * Every beacon heard, unfiltered and undeduplicated. Backed by a buffered `SharedFlow` fed
     * with `tryEmit` — `ScanCallback` fires on a Binder thread and must never suspend or block,
     * so there is no back-pressure here by design; a collector that falls behind loses hits, and
     * that is logged loudly rather than silently blocking the radio thread.
     */
    val results: Flow<ScanHit> = _results.asSharedFlow()

    private val _lastFailure = MutableStateFlow<ScanFailure?>(null)

    /** A silent scanner is indistinguishable from an empty room, so failures are published. */
    val lastFailure: StateFlow<ScanFailure?> = _lastFailure.asStateFlow()

    private val rateLimiterLock = Any()

    /** Wall clock of the last `startScan` call actually issued to the radio, or null before the first. */
    private var lastScanStartMillis: Long? = null

    private var activeCallback: ScanCallback? = null

    private val droppedWrongLength = AtomicInteger(0)

    /**
     * Starts a scan, suspends for [windowMillis] collecting into [results], then stops.
     *
     * If less than [MIN_SCAN_START_GAP_MILLIS] have passed since the previous `startScan`, the
     * request is dropped — logged loudly and returned immediately, not suspended — so a caller
     * looping too fast is never itself stalled waiting on the radio. That check-and-reserve is
     * synchronized so two overlapping callers can never both slip through the same gap, and is
     * rolled back by [releaseReservation] if the scan never reaches the radio.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_SCAN is gated by PermissionGate; the
    // SecurityException catches below cover revocation while the service is already running.
    suspend fun scanFor(windowMillis: Long, scanMode: Int) {
        val now = System.currentTimeMillis()

        val previousStart = synchronized(rateLimiterLock) {
            val previous = lastScanStartMillis
            val gap = previous?.let { now - it }
            if (previous != null && gap != null && gap < MIN_SCAN_START_GAP_MILLIS) {
                val waitMillis = MIN_SCAN_START_GAP_MILLIS - gap
                Log.w(
                    TAG,
                    "Scan request DROPPED: only ${gap}ms since last startScan " +
                        "(need ${MIN_SCAN_START_GAP_MILLIS}ms). Caller must wait ${waitMillis}ms " +
                        "more. Dropping now instead of risking SCAN_FAILED_SCANNING_TOO_FREQUENTLY.",
                )
                return
            }
            lastScanStartMillis = now
            previous
        }

        val radio = scanner
        if (radio == null) {
            // Nothing reached the radio, so the slot was never really spent. Without this the
            // first scan after enabling Bluetooth is dropped for six seconds for no reason.
            releaseReservation(now, previousStart)
            Log.e(TAG, "No BLE scanner available — adapter off, or device cannot scan")
            record(NO_CODE, "No BLE scanner available — adapter off, or device cannot scan")
            return
        }

        val filter = ScanFilter.Builder()
            // Empty data + empty mask matches ANY payload carrying our UUID. A non-empty mask
            // filters on payload content and would silently drop real beacons.
            .setServiceData(SETU_SERVICE_UUID, byteArrayOf(), byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            // 0 = report immediately. Non-zero batches reports, which breaks the bounded-window
            // model this class exists to provide.
            .setReportDelay(0)
            .build()

        val callback = HitCallback()

        val gapLog = previousStart?.let { "${now - it}ms" } ?: "first scan"
        Log.i(TAG, "startScan @ $now, gap since previous=$gapLog, mode=$scanMode, window=${windowMillis}ms")

        try {
            radio.startScan(listOf(filter), settings, callback)
            activeCallback = callback
        } catch (e: SecurityException) {
            releaseReservation(now, previousStart)
            Log.e(TAG, "BLUETOOTH_SCAN denied while starting scanner", e)
            record(NO_CODE, "BLUETOOTH_SCAN permission denied")
            return
        }

        try {
            delay(windowMillis)
        } finally {
            // Always in finally: cancellation must still stop the scan, or a leaked scan burns
            // battery indefinitely — the entire point of the bounded-window model.
            stopInternal(radio, callback)
        }
    }

    /**
     * Hands the rate-limit slot back when the scan never actually reached the radio, so a
     * no-op call does not cost the next real caller six seconds.
     *
     * The reservation is taken up front rather than after `startScan` on purpose: reserving late
     * would let two concurrent callers both pass the gap check before either started. Rolling
     * back keeps that atomicity while still not charging for a scan that never happened. The
     * identity check means a caller who reserved after us is never clobbered — in that case the
     * slot genuinely is spoken for and ours is simply forgotten.
     */
    private fun releaseReservation(reservedAt: Long, previous: Long?) {
        synchronized(rateLimiterLock) {
            if (lastScanStartMillis == reservedAt) {
                lastScanStartMillis = previous
            }
        }
    }

    /** Stops whatever scan is currently active, if any. Safe to call when nothing is running. */
    fun stop() {
        val callback = activeCallback ?: return
        stopInternal(scanner, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal(radio: BluetoothLeScanner?, callback: ScanCallback) {
        try {
            radio?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "BLUETOOTH_SCAN denied while stopping scanner", e)
        }
        if (activeCallback === callback) {
            activeCallback = null
        }
    }

    private fun record(code: Int, reason: String) {
        _lastFailure.value = ScanFailure(code, reason, System.currentTimeMillis())
    }

    private inner class HitCallback : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getServiceData(SETU_SERVICE_UUID) ?: return

            if (payload.size != BEACON_SIZE_BYTES) {
                val total = droppedWrongLength.incrementAndGet()
                Log.w(
                    TAG,
                    "Dropping ${payload.size}-byte payload from ${result.device.address}, " +
                        "expected $BEACON_SIZE_BYTES. Not padding, not truncating. " +
                        "Total dropped so far: $total",
                )
                return
            }

            val hit = ScanHit(
                payload = payload,
                address = result.device.address,
                rssiDbm = result.rssi,
                atMillis = System.currentTimeMillis(),
            )
            if (!_results.tryEmit(hit)) {
                Log.w(TAG, "results flow full — dropping a hit; collector is falling behind")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> {
                    Log.e(TAG, "ALREADY_STARTED: previous scan was not stopped before this one began")
                    record(errorCode, "Scan already running — previous scan was not stopped")
                }

                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> {
                    Log.e(TAG, "APPLICATION_REGISTRATION_FAILED: usually a missing BLUETOOTH_SCAN permission")
                    record(errorCode, "App registration with the BLE stack failed — check permissions")
                }

                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> {
                    Log.e(TAG, "INTERNAL_ERROR: Bluetooth stack wedged; toggling Bluetooth usually clears it")
                    record(errorCode, "Bluetooth stack error — try toggling Bluetooth")
                }

                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> {
                    Log.e(TAG, "FEATURE_UNSUPPORTED: this device cannot scan for the requested settings")
                    record(errorCode, "This device cannot scan with the requested settings")
                }

                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> {
                    Log.e(TAG, "OUT_OF_HARDWARE_RESOURCES: too many concurrent filters/scans on this radio")
                    record(errorCode, "Radio out of scan resources — too many filters or concurrent scans")
                }

                ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> {
                    // If this ever fires, the 6-second rate limiter above has a bug — it exists
                    // specifically to make this code unreachable. Treat it as a bug report.
                    Log.e(
                        TAG,
                        "!!!! SCAN_FAILED_SCANNING_TOO_FREQUENTLY !!!! The rate limiter failed to " +
                            "prevent this — Android's 5-per-30s throttle was hit anyway. This is a " +
                            "bug in BleScanner, not a transient radio error.",
                    )
                    record(errorCode, "Hit Android's scan throttle — rate limiter bug")
                }

                else -> {
                    Log.e(TAG, "Scan failed with unmapped code $errorCode")
                    record(errorCode, "Scan failed (code $errorCode)")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SetuScanner"

        /** No `ScanCallback` code applies — the failure happened before the radio saw it. */
        private const val NO_CODE = -1

        /**
         * Android silently throttles to 5 `startScan` calls per rolling 30s window. 6s keeps
         * every caller under that with margin, without needing to track a rolling call count.
         * Do not shorten this — see the class doc and `docs/PROTOCOL.md` §1-2.
         */
        const val MIN_SCAN_START_GAP_MILLIS = 6_000L

        private const val RESULTS_BUFFER_CAPACITY = 64
    }
}
