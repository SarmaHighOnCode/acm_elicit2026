package com.setu.mesh.app.data

import android.content.Context

/**
 * Whether this device acts as a gateway (SMS uplink to an authority number when it is the first
 * node to accept delivery of an SOS), and which number to text. Off by default -- only a node
 * a responder deliberately designates as "the one with signal" should be sending SMS on behalf
 * of the mesh.
 */
object GatewaySettings {

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getPhoneNumber(context: Context): String =
        prefs(context).getString(KEY_PHONE_NUMBER, "") ?: ""

    fun setPhoneNumber(context: Context, number: String) {
        prefs(context).edit().putString(KEY_PHONE_NUMBER, number).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "setu_gateway"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PHONE_NUMBER = "phone_number"
}
