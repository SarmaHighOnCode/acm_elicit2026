package com.setu.mesh.app.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.setu.mesh.core.link.Link
import com.setu.mesh.core.link.LinkCapabilities
import com.setu.mesh.core.link.LinkEvent
import com.setu.mesh.core.link.PeerHandle
import com.setu.mesh.core.link.RadioProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * `Link` for a real phone. Composes [BleAdvertiser] and [BleScanner] behind the frozen
 * `core.link.Link` interface — this is the class that makes the seam real.
 *
 * This class is a **dumb pipe**, same as the two it wraps. It must never parse a beacon, decide
 * what to advertise, decide when to scan, or deduplicate — every one of those is a routing
 * decision, and routing lives in `:core`. If this class ever imports `com.setu.mesh.core.model`
 * or `com.setu.mesh.core.codec`, that is the layering breaking.
 *
 * Radio tuning arrives via [applyRadioProfile] rather than as a parameter on every call, so the
 * engine can change the power tier without this class ever learning what a "tier" is. The
 * profile is cached and applied to subsequent advertising and scanning, and re-applied
 * immediately on change -- a stable outbox may never trigger another `setAdvertisedBeacons`
 * content change, so waiting for one would silently strand the old tuning.
 */
class AndroidLink(
    context: Context,
    private val scope: CoroutineScope,
) : Link {

    private val appContext = context.applicationContext
    private val advertiser = BleAdvertiser(appContext)
    private val scanner = BleScanner(appContext)

    override val capabilities: LinkCapabilities
        get() = LinkCapabilities(
            advertisingSlots = advertiser.advertisingSlots,
            supportsExtendedAdvertising = advertiser.supportsExtendedAdvertising,
            maxBundleBytes = MAX_BUNDLE_BYTES,
            canAdvertise = advertiser.canAdvertise,
        )

    /**
     * Transport telemetry, deliberately not part of `Link`: it is "what is my radio doing",
     * not a routing input, and `:core` must stay unable to see it.
     */
    val advertiseSettingsInEffect: StateFlow<android.bluetooth.le.AdvertiseSettings?>
        get() = advertiser.settingsInEffect

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = EVENTS_BUFFER_CAPACITY)
    override val events: Flow<LinkEvent> = _events.asSharedFlow()

    /**
     * System broadcast, not an app-specific one, so [ContextCompat.RECEIVER_NOT_EXPORTED] is
     * correct on API 33+ — nothing but the system ever sends `ACTION_STATE_CHANGED`.
     */
    private val radioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF ->
                    _events.tryEmit(LinkEvent.RadioUnavailable("Bluetooth turned off", System.currentTimeMillis()))
            }
        }
    }

    private var receiverRegistered = false

    init {
        ContextCompat.registerReceiver(
            appContext,
            radioStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        // Re-emits every scanner hit as a BeaconHeard. This is the entire multi-hop path in the
        // common case: MeshNode.onBeaconHeard() is driven from here with no connection involved.
        scope.launch {
            scanner.results.collect { hit ->
                _events.emit(LinkEvent.BeaconHeard(hit.payload, PeerHandle(hit.address), hit.rssiDbm, hit.atMillis))
            }
        }

        scope.launch {
            advertiser.lastFailure.collect { failure ->
                if (failure != null) {
                    _events.emit(LinkEvent.RadioUnavailable(failure.reason, failure.atMillis))
                }
            }
        }

        scope.launch {
            scanner.lastFailure.collect { failure ->
                if (failure != null) {
                    _events.emit(LinkEvent.RadioUnavailable(failure.reason, failure.atMillis))
                }
            }
        }
    }

    /**
     * Current radio tuning, driven by [applyRadioProfile]. Starts balanced so a transport used
     * before the engine ever reports a profile still behaves sensibly.
     */
    @Volatile
    private var advertiseMode: Int = AdvertiseSettings.ADVERTISE_MODE_BALANCED

    @Volatile
    private var txPower: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM

    @Volatile
    private var scanMode: Int = ScanSettings.SCAN_MODE_BALANCED

    override suspend fun applyRadioProfile(profile: RadioProfile) {
        when (profile) {
            RadioProfile.HIGH_PERFORMANCE -> {
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                txPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
            }
            RadioProfile.BALANCED -> {
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED
                txPower = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                scanMode = ScanSettings.SCAN_MODE_BALANCED
            }
            RadioProfile.LOW_POWER -> {
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                txPower = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                scanMode = ScanSettings.SCAN_MODE_LOW_POWER
            }
            // Cheapest advertising interval, but the *highest* transmit power: a node here has
            // stopped scanning entirely and broadcasts rarely, so each broadcast should carry
            // as far as it possibly can. See docs/POWER.md §1.
            RadioProfile.LOW_POWER_LONG_RANGE -> {
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                txPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                scanMode = ScanSettings.SCAN_MODE_LOW_POWER
            }
        }
        // Re-apply immediately so the new tuning takes effect on the beacons already on air,
        // rather than waiting for the next content change (which for a stable outbox may never
        // come -- BleAdvertiser now skips no-op restarts).
        advertiser.reapplyWith(advertiseMode, txPower)
    }

    override suspend fun setAdvertisedBeacons(beacons: List<ByteArray>) {
        if (beacons.isEmpty()) {
            advertiser.stop()
        } else {
            advertiser.setBeacons(beacons, advertiseMode, txPower)
        }
    }

    override suspend fun scanFor(windowMillis: Long) {
        _events.emit(LinkEvent.ScanWindow(open = true, atMillis = System.currentTimeMillis()))
        scanner.scanFor(windowMillis, scanMode)
        _events.emit(LinkEvent.ScanWindow(open = false, atMillis = System.currentTimeMillis()))
    }

    /**
     * Always false in v1. Rich bundles need a GATT connection, which is out of scope here —
     * `MeshNode` treats this identically to an unreachable peer, which is the correct fallback
     * until GATT transport for [com.setu.mesh.core.model.SosBundle] is implemented.
     */
    override suspend fun sendBundle(peer: PeerHandle, payload: ByteArray): Boolean = false

    override suspend fun shutdown() {
        if (receiverRegistered) {
            appContext.unregisterReceiver(radioStateReceiver)
            receiverRegistered = false
        }
        advertiser.stop()
        scanner.stop()
    }

    companion object {
        private const val MAX_BUNDLE_BYTES = 244
        private const val EVENTS_BUFFER_CAPACITY = 64

        private const val DEFAULT_ADVERTISE_MODE = AdvertiseSettings.ADVERTISE_MODE_BALANCED
        private const val DEFAULT_TX_POWER = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
        private const val DEFAULT_SCAN_MODE = ScanSettings.SCAN_MODE_BALANCED
    }
}
