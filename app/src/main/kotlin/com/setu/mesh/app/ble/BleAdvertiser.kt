package com.setu.mesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The most recent reason the radio refused to advertise, for the UI to surface. */
data class AdvertiseFailure(
    val code: Int,
    val reason: String,
    val atMillis: Long,
)

/**
 * Puts 24-byte beacons on air as connectionless BLE legacy advertisements.
 *
 * This class is deliberately stupid. It moves opaque bytes to the radio and nothing else: it
 * does not parse a beacon, deduplicate, decide what deserves airtime, or touch TTL. All of that
 * is routing and it lives in `:core` — `MeshNode.beaconsToAdvertise()` hands us an already
 * ordered list and our only job is to broadcast it.
 *
 * The carousel here is the mechanical half of that: when the caller hands us more beacons than
 * the radio has advertising slots (the common case — most budget handsets expose exactly one),
 * we rotate the window forward every [rotationIntervalMillis] so everything eventually gets air.
 */
class BleAdvertiser(
    context: Context,
    private val rotationIntervalMillis: Long = DEFAULT_ROTATION_INTERVAL_MILLIS,
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    // Resolved on every access rather than cached: the adapter can be off when the service
    // starts and come up later, and bluetoothLeAdvertiser is null for as long as it is off.
    private val adapter: BluetoothAdapter?
        get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val advertiser: BluetoothLeAdvertiser?
        get() = adapter?.bluetoothLeAdvertiser

    /**
     * Real slot count. Many devices claim multi-advertisement support and expose exactly one in
     * practice, which is what [ADVERTISE_FAILED_TOO_MANY_ADVERTISERS] recovery below is for.
     */
    val advertisingSlots: Int
        get() = if (adapter?.isMultipleAdvertisementSupported == true) MULTI_ADVERTISEMENT_SLOTS else 1

    /** False when this device physically cannot advertise. Some handsets simply cannot. */
    val canAdvertise: Boolean
        get() = !featureUnsupported && advertiser != null

    val supportsExtendedAdvertising: Boolean
        get() = adapter?.isLeExtendedAdvertisingSupported == true

    private val _lastFailure = MutableStateFlow<AdvertiseFailure?>(null)

    /**
     * A silent advertiser is indistinguishable from an empty room, so failures are published
     * rather than only logged. B4 maps this onto `LinkEvent.RadioUnavailable`.
     */
    val lastFailure: StateFlow<AdvertiseFailure?> = _lastFailure.asStateFlow()

    // ---- state, all touched on the main looper only ----

    private val activeCallbacks = mutableListOf<AdvertiseCallback>()
    private var currentBeacons: List<ByteArray> = emptyList()
    private var currentMode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
    private var currentTxPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
    private var carouselOffset = 0

    /** Dropped to 1 after the radio tells us it has fewer slots than it advertised. */
    private var slotLimit = Int.MAX_VALUE
    private var retriedAlreadyStarted = false

    @Volatile
    private var featureUnsupported = false

    private val rotate = Runnable {
        val slots = effectiveSlots()
        if (currentBeacons.size > slots) {
            carouselOffset = (carouselOffset + slots) % currentBeacons.size
        }
        applyWindow()
    }

    /**
     * Replace the advertised set. An empty list stops advertising. Each entry must be exactly
     * [BEACON_SIZE_BYTES]; anything else is logged and skipped, never truncated — a truncated
     * beacon is a corrupt beacon and would fail CRC at every receiver.
     */
    fun setBeacons(beacons: List<ByteArray>, mode: Int, txPower: Int) {
        val valid = beacons.filter { beacon ->
            val ok = beacon.size == BEACON_SIZE_BYTES
            if (!ok) {
                Log.e(
                    TAG,
                    "Skipping beacon of ${beacon.size} bytes, expected $BEACON_SIZE_BYTES. " +
                        "Not truncating — hex=${beacon.toHex()}",
                )
            }
            ok
        }

        handler.post {
            currentBeacons = valid
            currentMode = mode
            currentTxPower = txPower
            carouselOffset = 0
            slotLimit = Int.MAX_VALUE
            retriedAlreadyStarted = false
            applyWindow()
        }
    }

    fun stop() {
        handler.post {
            handler.removeCallbacks(rotate)
            currentBeacons = emptyList()
            stopAll()
            Log.i(TAG, "Advertising stopped")
        }
    }

    // ---------------------------------------------------------------- internals

    private fun effectiveSlots(): Int = minOf(advertisingSlots, slotLimit).coerceAtLeast(1)

    @SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE is gated by PermissionGate; the
    // SecurityException catch below covers revocation while the service is already running.
    private fun applyWindow() {
        handler.removeCallbacks(rotate)
        // Always stop before starting. Leaked callbacks accumulate and the radio eventually
        // refuses to start anything at all, with no error that points at the real cause.
        stopAll()

        if (currentBeacons.isEmpty()) return

        val radio = advertiser
        if (radio == null || featureUnsupported) {
            record(NO_CODE, "No BLE advertiser available — adapter off, or device cannot advertise")
            return
        }

        val slots = effectiveSlots().coerceAtMost(currentBeacons.size)
        val window = List(slots) { currentBeacons[(carouselOffset + it) % currentBeacons.size] }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(currentMode)
            .setTxPowerLevel(currentTxPower)
            .setConnectable(false) // beacons are connectionless; that is the whole power thesis
            .setTimeout(0)
            .build()

        window.forEach { beacon ->
            val data = AdvertiseData.Builder()
                // Both of these are mandatory. Leaving either on blows the 31-byte AD budget and
                // produces ADVERTISE_FAILED_DATA_TOO_LARGE — the most common way this fails.
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(SETU_SERVICE_UUID, beacon)
                .build()

            val callback = BeaconCallback(beacon)
            try {
                radio.startAdvertising(settings, data, callback)
                activeCallbacks += callback
                // Logged so it can be diffed byte-for-byte against nRF Connect on a second phone.
                Log.i(TAG, "→ air ${beacon.toHex()}")
            } catch (e: SecurityException) {
                Log.e(TAG, "BLUETOOTH_ADVERTISE denied while starting advertiser", e)
                record(NO_CODE, "BLUETOOTH_ADVERTISE permission denied")
            }
        }

        if (currentBeacons.size > slots) {
            Log.d(
                TAG,
                "Carousel: ${currentBeacons.size} beacons over $slots slot(s), " +
                    "offset=$carouselOffset, rotating in ${rotationIntervalMillis}ms",
            )
            handler.postDelayed(rotate, rotationIntervalMillis)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAll() {
        if (activeCallbacks.isEmpty()) return
        val radio = advertiser
        activeCallbacks.forEach { callback ->
            try {
                radio?.stopAdvertising(callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "BLUETOOTH_ADVERTISE denied while stopping advertiser", e)
            }
        }
        activeCallbacks.clear()
    }

    private fun record(code: Int, reason: String) {
        _lastFailure.value = AdvertiseFailure(code, reason, System.currentTimeMillis())
    }

    private inner class BeaconCallback(private val beacon: ByteArray) : AdvertiseCallback() {

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(
                TAG,
                "Advertising ${beacon.toHex()} " +
                    "(mode=${settingsInEffect.mode}, txPower=${settingsInEffect.txPowerLevel})",
            )
        }

        override fun onStartFailure(errorCode: Int) {
            when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> {
                    val onAir = FLAGS_AD_BYTES + SERVICE_DATA_HEADER_BYTES + beacon.size
                    Log.e(
                        TAG,
                        "DATA_TOO_LARGE: beacon=${beacon.size}B, on-air total=${onAir}B of " +
                            "$LEGACY_AD_BUDGET_BYTES. Check setIncludeDeviceName(false) and " +
                            "setIncludeTxPowerLevel(false), and that the UUID is 16-bit.",
                    )
                    record(errorCode, "Beacon too large for a legacy advertisement (${onAir}B)")
                }

                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                    Log.e(TAG, "TOO_MANY_ADVERTISERS: radio has fewer slots than reported; falling back to 1")
                    record(errorCode, "Radio out of advertising slots — reduced to one")
                    handler.post {
                        if (slotLimit != 1) {
                            slotLimit = 1
                            applyWindow()
                        }
                    }
                }

                ADVERTISE_FAILED_ALREADY_STARTED -> {
                    Log.e(TAG, "ALREADY_STARTED: previous advertiser was not stopped; restarting once")
                    record(errorCode, "Advertiser already running — restarting")
                    handler.post {
                        if (!retriedAlreadyStarted) {
                            retriedAlreadyStarted = true
                            applyWindow()
                        }
                    }
                }

                ADVERTISE_FAILED_INTERNAL_ERROR -> {
                    Log.e(TAG, "INTERNAL_ERROR: Bluetooth stack wedged; toggling Bluetooth usually clears it")
                    record(errorCode, "Bluetooth stack error — try toggling Bluetooth")
                }

                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                    Log.e(TAG, "FEATURE_UNSUPPORTED: this device can never advertise; it can only receive")
                    featureUnsupported = true
                    record(errorCode, "This device cannot advertise — it can receive but never relay")
                    handler.post { stopAll() }
                }

                else -> {
                    Log.e(TAG, "Advertising failed with unmapped code $errorCode")
                    record(errorCode, "Advertising failed (code $errorCode)")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SetuAdvertiser"

        /** No AdvertiseCallback code applies — the failure happened before the radio saw it. */
        private const val NO_CODE = -1

        private const val MULTI_ADVERTISEMENT_SLOTS = 4
        const val DEFAULT_ROTATION_INTERVAL_MILLIS = 1_000L

        // Used only to make the DATA_TOO_LARGE log actionable. See docs/PROTOCOL.md §1.
        private const val LEGACY_AD_BUDGET_BYTES = 31
        private const val FLAGS_AD_BYTES = 3
        private const val SERVICE_DATA_HEADER_BYTES = 4

        private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    }
}
