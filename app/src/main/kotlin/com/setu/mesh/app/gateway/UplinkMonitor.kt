package com.setu.mesh.app.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What this phone can actually reach the outside world with, tracked live rather than assumed.
 *
 * `GatewayRole.onUplinkAvailable` used to be driven by a hardcoded `true` the instant gateway
 * mode was enabled in settings -- which meant the app would happily promise an SMS/WhatsApp
 * alert from a phone that had neither signal nor internet. The whole point of the gateway role
 * is "the person who actually has a way out sends the alert"; a setting the user flips is not
 * evidence of that, only a live radio state is.
 *
 * Two capabilities, tracked independently, because they really are independent and the demo
 * depends on the difference:
 *  - [hasInternet] drives WhatsApp. Requires both NET_CAPABILITY_INTERNET *and*
 *    NET_CAPABILITY_VALIDATED -- in a disaster a Wi-Fi AP can be powered and associable with
 *    dead backhaul, and an unvalidated network must not be trusted as a way out.
 *  - [hasCellService] drives SMS, which does not need data at all: a SIM with bars but no data
 *    plan (or a data outage with voice/SMS still up) still gets a text out.
 *
 * The classic demo puts victim phones in airplane mode to prove there is no internet, which
 * means the *gateway* phone has to be the one phone left out of airplane mode. Tracking real
 * state here makes that a fact the app can check and show, rather than a hardcoded lie the demo
 * relied on staying true by accident.
 */
class UplinkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val _hasInternet = MutableStateFlow(false)
    val hasInternet: StateFlow<Boolean> = _hasInternet.asStateFlow()

    private val _hasCellService = MutableStateFlow(false)
    val hasCellService: StateFlow<Boolean> = _hasCellService.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var airplaneModeReceiver: BroadcastReceiver? = null
    private var started = false

    /**
     * Begin tracking. Cell service is checked immediately since there is no cheap callback for
     * "SIM state changed" worth the extra permission surface here -- it only actually changes on
     * an airplane-mode toggle or a SIM being physically swapped, and the airplane-mode receiver
     * below already catches the common case live.
     */
    fun start() {
        if (started) return
        started = true
        registerNetworkCallback()
        registerAirplaneModeReceiver()
        refreshCellService()
    }

    fun stop() {
        if (!started) return
        started = false
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        networkCallback = null
        airplaneModeReceiver?.let { appContext.unregisterReceiver(it) }
        airplaneModeReceiver = null
    }

    private fun registerNetworkCallback() {
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _hasInternet.value =
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            override fun onLost(network: Network) {
                _hasInternet.value = false
            }

            override fun onUnavailable() {
                _hasInternet.value = false
            }
        }
        networkCallback = callback
        manager.registerDefaultNetworkCallback(callback)
    }

    /**
     * ACTION_AIRPLANE_MODE_CHANGED is a normal (no-permission) broadcast, and airplane mode is
     * the one SIM-adjacent state the demo actually flips live -- a victim phone going into
     * airplane mode mid-demo must immediately stop counting as a possible gateway.
     */
    private fun registerAirplaneModeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshCellService()
            }
        }
        airplaneModeReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun refreshCellService() {
        val simReady = telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
        val airplaneModeOn = Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) != 0
        _hasCellService.value = simReady && !airplaneModeOn
    }
}
