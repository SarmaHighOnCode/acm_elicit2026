package com.setu.mesh.app.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.setu.mesh.core.engine.NodeHost
import com.setu.mesh.core.model.GeoPoint

/**
 * `NodeHost` for a real phone: battery, GPS, clock trust. Battery is a first-class protocol
 * input here, not telemetry — nearly every routing and scheduling decision in `:core` is a
 * function of [batteryPercent], so getting this wrong quietly breaks the protocol rather than
 * throwing.
 */
class AndroidNodeHost(context: Context) : NodeHost {

    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Cached rather than read fresh each call: batteryPercent() and position() are read on
    // every protocol tick, and BatteryManager/LocationManager reads are not free.
    @Volatile
    private var cachedBatteryPercent: Int = readBatteryPercentNow()

    @Volatile
    private var cachedCharging: Boolean = readChargingNow()

    /** The best fix seen so far -- see [isBetterFix]. Null until a first fix lands or seeds in. */
    @Volatile
    private var currentFix: SelfFix? = null

    /** Debug-only override so the demo can force EMBER tier without draining a real battery. */
    @Volatile
    private var batteryOverride: Int? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                cachedBatteryPercent = (level * 100) / scale
            }
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            cachedCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    // One listener instance registered against both providers (Android supports this fine --
    // Location.getProvider() disambiguates the source), so shutdown() has exactly one
    // registration to tear down rather than two independent ones that could drift apart.
    private val locationListener = LocationListener { location: Location -> onLocation(location) }

    private var receiversRegistered = false
    private var locationUpdatesRequested = false

    init {
        ContextCompat.registerReceiver(
            appContext,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiversRegistered = true
        requestLocationUpdates()
    }

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun batteryPercent(): Int = batteryOverride ?: cachedBatteryPercent

    override fun isCharging(): Boolean = cachedCharging

    override fun position(): GeoPoint? = currentFix?.point

    /**
     * The full fix behind [position]: accuracy, age and provider, for the responder map's
     * footer and the SOS screen's "did this carry a real location" line. `GeoPoint` lives in
     * `:core` and must not grow a field for a display concern that `:core` never needs, so this
     * lives at the app layer instead.
     */
    fun lastFix(): SelfFix? = currentFix

    /**
     * Approximated via `Settings.Global.AUTO_TIME`: true when the device is set to sync its
     * clock from the network/GPS rather than a value the user set by hand. When false, the
     * rendezvous scheduler falls back to consensus drift correction from beacon timestamps
     * instead of trusting the local clock outright.
     */
    override fun hasTrustedClock(): Boolean =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.AUTO_TIME, 1) == 1

    /**
     * Debug-only. Forces [batteryPercent] to return a fixed value; null restores the real
     * reading. Guarded by the app's debuggable flag rather than `BuildConfig.DEBUG`, since
     * enabling `buildConfig` would require a change to `app/build.gradle.kts`.
     */
    fun setBatteryOverride(percent: Int?) {
        if (!isDebuggable()) {
            Log.w(TAG, "setBatteryOverride ignored: release build")
            return
        }
        batteryOverride = percent
    }

    fun shutdown() {
        if (receiversRegistered) {
            appContext.unregisterReceiver(batteryReceiver)
            receiversRegistered = false
        }
        if (locationUpdatesRequested) {
            try {
                locationManager?.removeUpdates(locationListener)
            } catch (e: SecurityException) {
                Log.w(TAG, "ACCESS_FINE_LOCATION revoked while stopping updates", e)
            }
            locationUpdatesRequested = false
        }
    }

    private fun isDebuggable(): Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Adopts [location] if [isBetterFix] says it beats whatever [currentFix] already holds. */
    private fun onLocation(location: Location) {
        if (!isBetterFix(location, currentFix)) return
        currentFix = SelfFix(
            point = GeoPoint.of(location.latitude, location.longitude),
            accuracyMetres = if (location.hasAccuracy()) location.accuracy else null,
            atMillis = location.time,
            provider = location.provider ?: "unknown",
        )
    }

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is gated by PermissionGate; the
    // SecurityException catch below covers revocation while the service is already running.
    private fun requestLocationUpdates() {
        val manager = locationManager ?: return

        // Seed with whatever fix already exists so position() need not wait for a fresh one --
        // but only a fix fresh enough to still mean something. A seed with no age check can be
        // hours old and from a different part of the city, which alone produces "wrong distance
        // and direction" with no other bug present.
        seedFromLastKnown(manager)

        var requestedAny = false
        try {
            // Both providers, each with its own subscription: indoors GPS is often "enabled"
            // but silent for minutes, and NETWORK is the only thing that will actually produce
            // a fix there. Subscribing to only one (the old behaviour) meant an indoor node sat
            // forever on a stale seed whenever it happened to prefer the silent one.
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME_MILLIS,
                    LOCATION_UPDATE_MIN_DISTANCE_METRES,
                    locationListener,
                )
                requestedAny = true
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME_MILLIS,
                    LOCATION_UPDATE_MIN_DISTANCE_METRES,
                    locationListener,
                )
                requestedAny = true
            }
            if (!requestedAny) {
                Log.w(TAG, "No location provider enabled; position() will return null until one is")
            }
            locationUpdatesRequested = requestedAny
        } catch (e: SecurityException) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; position() will return null", e)
        }
    }

    @SuppressLint("MissingPermission") // see requestLocationUpdates above
    private fun seedFromLastKnown(manager: LocationManager) {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val existing = try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } ?: continue

            val ageMillis = nowMillis() - existing.time
            if (ageMillis > MAX_SEED_FIX_AGE_MILLIS) {
                // A silently discarded seed is confusing during bring-up ("why is the map
                // empty/wrong") -- log it loudly rather than just dropping it.
                Log.i(TAG, "Dropping $provider seed fix, ${ageMillis / 1000}s old (max ${MAX_SEED_FIX_AGE_MILLIS / 1000}s)")
                continue
            }
            onLocation(existing)
        }
    }

    private fun readBatteryPercentNow(): Int {
        val manager = batteryManager ?: return com.setu.mesh.core.model.BATTERY_UNKNOWN
        val value = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (value in 0..100) value else com.setu.mesh.core.model.BATTERY_UNKNOWN
    }

    private fun readChargingNow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return batteryManager?.isCharging == true
    }

    companion object {
        private const val TAG = "SetuNodeHost"

        // GPS is not this app's battery story -- the radio is (see docs/POWER.md) -- and during
        // an emergency the position *is* the payload. A 60s/50m throttle (the old values) meant
        // two phones sitting on a table shared whatever fix each happened to have at startup;
        // there is no equivalent reason to throttle GPS the way the radio schedule is throttled.
        private const val LOCATION_UPDATE_MIN_TIME_MILLIS = 1_000L
        private const val LOCATION_UPDATE_MIN_DISTANCE_METRES = 0.0f

        /** Older than this, a `getLastKnownLocation` seed is worse than no seed at all. */
        private const val MAX_SEED_FIX_AGE_MILLIS = 5 * 60_000L

        /** Below this age gap, two fixes are "about the same age" rather than one outdating the other. */
        /** Past this, whatever we hold is too old to defend against a coarser but fresh fix. */
        private const val STALE_FIX_MILLIS = 60_000L

        /** How much coarser a candidate must be before "newer" stops being reason enough. */
        private const val DRASTIC_ACCURACY_RATIO = 4f

        private const val SIGNIFICANT_TIME_DELTA_MILLIS = 30_000L

        /**
         * The standard "is this fix better than what we have" rule: a fix that is clearly newer
         * wins outright regardless of accuracy (staleness matters most during an emergency); of
         * two fixes of comparable age, the more accurate one wins; and a same-provider fix that
         * is not meaningfully worse than the current one also wins, which prevents NETWORK and
         * GPS flip-flopping against each other on every tick once both are live.
         */
        private fun isBetterFix(candidate: Location, current: SelfFix?): Boolean {
            if (current == null) return true
            val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else null
            val ageDeltaMillis = candidate.time - current.atMillis
            return when {
                ageDeltaMillis < -SIGNIFICANT_TIME_DELTA_MILLIS -> false
                // A significantly newer fix usually wins: the user may simply have moved, and
                // there is no way to tell a stale-but-precise fix from a correct one. But not
                // when it is drastically coarser and what we hold is still fresh. Indoors, GPS
                // goes quiet while NETWORK fixes keep arriving every second, and without this
                // guard a +-150 m cell fix repeatedly stomps a +-8 m GPS fix that is only half
                // a minute old -- which is exactly what makes the displayed accuracy flap
                // between +-100 m and +-200 m on a phone sitting still on a desk.
                ageDeltaMillis > SIGNIFICANT_TIME_DELTA_MILLIS ->
                    !(isDrasticallyWorse(candidateAccuracy, current.accuracyMetres) &&
                        ageDeltaMillis < STALE_FIX_MILLIS)
                candidateAccuracy == null -> false
                current.accuracyMetres == null -> true
                candidateAccuracy < current.accuracyMetres -> true
                candidate.provider == current.provider && candidateAccuracy <= current.accuracyMetres -> true
                else -> false
            }
        }

        /** Unknown accuracy counts as worst: it carries no evidence that it is any good. */
        private fun isDrasticallyWorse(candidateMetres: Float?, currentMetres: Float?): Boolean {
            if (candidateMetres == null) return true
            if (currentMetres == null) return false
            return candidateMetres > currentMetres * DRASTIC_ACCURACY_RATIO
        }
    }
}

/**
 * App-layer companion to [GeoPoint]: the point plus the quality info the responder map and the
 * SOS screen need to tell an honest story ("±8 m, 3 s ago, GPS" vs. pretending every fix is
 * survey-grade). Deliberately not part of `:core` -- `MeshNode` only ever needs [point] through
 * `NodeHost.position()`, and `GeoPoint` is wire-encoded, which [accuracyMetres] is not.
 */
data class SelfFix(
    val point: GeoPoint,
    /**
     * Null when the platform reported no accuracy at all. Deliberately nullable rather than 0f:
     * `Location.getAccuracy()` returns 0.0f when `hasAccuracy()` is false, and rendering that
     * would claim a perfect fix for a reading that carries no quality information whatsoever.
     */
    val accuracyMetres: Float?,
    val atMillis: Long,
    val provider: String,
)
