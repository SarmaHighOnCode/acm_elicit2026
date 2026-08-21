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

    @Volatile
    private var lastFix: GeoPoint? = null

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

    private val locationListener = LocationListener { location: Location ->
        lastFix = GeoPoint.of(location.latitude, location.longitude)
    }

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

    override fun position(): GeoPoint? = lastFix

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

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is gated by PermissionGate; the
    // SecurityException catch below covers revocation while the service is already running.
    private fun requestLocationUpdates() {
        val manager = locationManager ?: return
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                Log.w(TAG, "No location provider enabled; position() will return null until one is")
                return
            }
        }

        // Seed with whatever fix already exists so position() need not wait for a fresh one.
        try {
            val existing = manager.getLastKnownLocation(provider)
                ?: manager.getLastKnownLocation(
                    if (provider == LocationManager.GPS_PROVIDER) LocationManager.NETWORK_PROVIDER
                    else LocationManager.GPS_PROVIDER,
                )
            existing?.let { lastFix = GeoPoint.of(it.latitude, it.longitude) }

            manager.requestLocationUpdates(
                provider,
                LOCATION_UPDATE_MIN_TIME_MILLIS,
                LOCATION_UPDATE_MIN_DISTANCE_METRES,
                locationListener,
            )
            locationUpdatesRequested = true
        } catch (e: SecurityException) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted; position() will return null", e)
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

        // GPS is not the power problem in this app, but there is no reason to burn it either.
        private const val LOCATION_UPDATE_MIN_TIME_MILLIS = 60_000L
        private const val LOCATION_UPDATE_MIN_DISTANCE_METRES = 50.0f
    }
}
