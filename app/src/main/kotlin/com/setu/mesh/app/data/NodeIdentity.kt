package com.setu.mesh.app.data

import android.content.Context
import com.setu.mesh.core.model.NodeId
import java.util.UUID

/**
 * A stable 24-bit node id, persisted so it survives restarts.
 *
 * Deliberately **not** derived from `ANDROID_ID` or any hardware identifier — that would be a
 * privacy problem for no protocol benefit. A locally generated random UUID, persisted once, is
 * exactly as good a seed for [NodeId.fromSeed] and carries no identifying information off the
 * device.
 */
object NodeIdentity {

    fun get(context: Context): NodeId {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_UUID, null)
        val seed = existing ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_UUID, it).apply()
        }
        return NodeId.fromSeed(seed)
    }

    private const val PREFS_NAME = "setu_identity"
    private const val KEY_INSTALL_UUID = "install_uuid"
}
