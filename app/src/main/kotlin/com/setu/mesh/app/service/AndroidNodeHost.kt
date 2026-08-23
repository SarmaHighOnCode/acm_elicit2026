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
import android.os.SystemClock
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

    // RC1: a fix this old is not a position any more. Without this, position() kept returning
    // whatever currentFix last held indefinitely -- including a fix accepted minutes before a
    // GPS outage -- and that stale point went straight onto the wire as the SOS location.
    override fun position(): GeoPoint? = liveFix()?.point

    /**
     * The full fix behind [position]: accuracy, age and provider, for the responder map's
     * footer and the SOS screen's "did this carry a real location" line. `GeoPoint` lives in
     * `:core` and must not grow a field for a display concern that `:core` never needs, so this
     * lives at the app layer instead.
     */
    fun lastFix(): SelfFix? = liveFix()

    /**
     * [currentFix] itself, or null once it has aged past [MAX_FIX_AGE_MILLIS] -- the honest
     * answer for "what is our position right now" is nothing, not a stale point. Age is measured
     * against [SystemClock.elapsedRealtime], never wall-clock time; see the class doc on
     * [SelfFix.elapsedRealtimeMillis] for why.
     */
    private fun liveFix(): SelfFix? {
        val fix = currentFix ?: return null
        val ageMillis = SystemClock.elapsedRealtime() - fix.elapsedRealtimeMillis
        return if (ageMillis > MAX_FIX_AGE_MILLIS) null else fix
    }

    /**
     * Accuracy class of whatever [liveFix] currently holds, per docs/PROTOCOL.md §2. Falls back
     * to unknown (0) both when there is no fix and when the platform reported no accuracy figure
     * for the one we have -- an unmeasured fix is not evidence of a good one.
     */
    override fun positionAccuracyClass(): Int = accuracyClassFor(liveFix()?.accuracyMetres)

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

    /**
     * Adopts [location] if [isBetterFix] says it beats whatever [currentFix] already holds.
     * Logged either way -- accepted or rejected -- so field testing can see the arbitration
     * actually happen instead of only ever observing its outcome.
     */
    private fun onLocation(location: Location) {
        val nowElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        val candidateAccuracy = if (location.hasAccuracy()) location.accuracy else null
        val candidateElapsedRealtimeMillis = location.elapsedRealtimeMillis()
        val candidateAgeMillis = nowElapsedRealtimeMillis - candidateElapsedRealtimeMillis

        if (!isBetterFix(location, currentFix, nowElapsedRealtimeMillis)) {
            Log.d(
                TAG,
                "Rejected fix: provider=${location.provider} accuracy=${candidateAccuracy}m " +
                    "age=${candidateAgeMillis}ms held=$currentFix",
            )
            return
        }

        currentFix = SelfFix(
            point = GeoPoint.of(location.latitude, location.longitude),
            accuracyMetres = candidateAccuracy,
            atMillis = location.time,
            elapsedRealtimeMillis = candidateElapsedRealtimeMillis,
            provider = location.provider ?: "unknown",
        )
        Log.d(
            TAG,
            "Accepted fix: provider=${location.provider} accuracy=${candidateAccuracy}m " +
                "age=${candidateAgeMillis}ms",
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

            // elapsedRealtime, not existing.time: a seed's wall-clock timestamp is exactly as
            // vulnerable to clock skew as any other fix's, and this gate is the first line of
            // defence against adopting an hours-old, wrong-part-of-the-city seed at startup.
            val ageMillis = SystemClock.elapsedRealtime() - existing.elapsedRealtimeMillis()
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

        /**
         * Past this age, `currentFix` is not a position any more -- see [liveFix]. This is the
         * fix for RC1: without an expiry, a fix accepted minutes before a GPS outage sat in
         * `currentFix` forever and kept being broadcast as the SOS location, honestly labelled
         * "±10 m" on the sender's own screen while actually describing somewhere the person no
         * longer was.
         */
        private const val MAX_FIX_AGE_MILLIS = 5 * 60_000L

        /**
         * Absolute floor, independent of everything else in [isBetterFix]. RC3: with no such
         * floor, a 70-second GPS dropout outdoors let a ±2000 m cell fix win outright under the
         * "significantly newer" rule below and become `currentFix` -- and then get broadcast as
         * the SOS position. Nothing this coarse is worth adopting regardless of age or what we
         * currently hold.
         */
        private const val MAX_USABLE_ACCURACY_METRES = 200f

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
         *
         * All age math here runs on [nowElapsedRealtimeMillis] and [SelfFix.elapsedRealtimeMillis]
         * -- never [Location.getTime] or [SelfFix.atMillis] -- because [Location.getTime] is a
         * wall-clock timestamp that a provider can report skewed relative to
         * `System.currentTimeMillis()`, which corrupts every comparison built on it.
         * `elapsedRealtimeNanos` is monotonic and immune to that.
         */
        private fun isBetterFix(candidate: Location, current: SelfFix?, nowElapsedRealtimeMillis: Long): Boolean {
            val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else null

            // RC3's missing absolute floor. Unconditional: no age or current-fix comparison below
            // can ever be a reason to accept a fix this coarse.
            if (candidateAccuracy != null && candidateAccuracy > MAX_USABLE_ACCURACY_METRES) return false

            if (current == null) return true

            // An accuracy-less candidate carries no evidence it is any good, and must never
            // displace a fix we know the quality of, however old that fix's accuracy figure is.
            if (candidateAccuracy == null && current.accuracyMetres != null) return false

            val candidateElapsedRealtimeMillis = candidate.elapsedRealtimeMillis()
            val ageDeltaMillis = candidateElapsedRealtimeMillis - current.elapsedRealtimeMillis
            // "Is what we hold still fresh right now" -- not "was the candidate only a little
            // newer than it". The old test compared the candidate/current age *delta* against
            // STALE_FIX_MILLIS, which is a claim about relative ordering, not about whether the
            // held fix has actually gone stale by now; this is the freshness test its own comment
            // always claimed to be.
            val currentFreshnessMillis = nowElapsedRealtimeMillis - current.elapsedRealtimeMillis

            return when {
                ageDeltaMillis < -SIGNIFICANT_TIME_DELTA_MILLIS -> false
                // A significantly newer fix usually wins: the user may simply have moved, and
                // there is no way to tell a stale-but-precise fix from a correct one. But not
                // when it is drastically coarser and what we hold is still fresh right now.
                // Indoors, GPS goes quiet while NETWORK fixes keep arriving every second, and
                // without this guard a +-150 m cell fix repeatedly stomps a +-8 m GPS fix that is
                // only half a minute old -- which is exactly what makes the displayed accuracy
                // flap between +-100 m and +-200 m on a phone sitting still on a desk.
                ageDeltaMillis > SIGNIFICANT_TIME_DELTA_MILLIS ->
                    !(isDrasticallyWorse(candidateAccuracy, current.accuracyMetres) &&
                        currentFreshnessMillis < STALE_FIX_MILLIS)
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

        /** [Location.getElapsedRealtimeNanos] in the same unit every other age comparison uses. */
        private fun Location.elapsedRealtimeMillis(): Long = elapsedRealtimeNanos / 1_000_000L

        /** Maps a raw accuracy figure to a wire `positionAccuracyClass` value per docs/PROTOCOL.md §2. */
        private fun accuracyClassFor(accuracyMetres: Float?): Int = when {
            accuracyMetres == null -> 0
            accuracyMetres <= 10f -> 1
            accuracyMetres <= 30f -> 2
            accuracyMetres <= 100f -> 3
            else -> 0
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
    /** Display-only wall-clock timestamp ("3 s ago"). Never compare two of these for freshness --
     *  see [elapsedRealtimeMillis]. */
    val atMillis: Long,
    /**
     * [Location.getElapsedRealtimeNanos] in milliseconds: the monotonic clock every age
     * comparison in [AndroidNodeHost] is built on. `atMillis`/`Location.getTime()` is wall-clock
     * and a provider can report it skewed relative to `System.currentTimeMillis()`, which
     * corrupts age math built on it -- this field exists so nothing here has to trust that clock.
     */
    val elapsedRealtimeMillis: Long,
    val provider: String,
)
