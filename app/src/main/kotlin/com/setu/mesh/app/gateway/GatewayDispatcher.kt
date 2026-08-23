package com.setu.mesh.app.gateway

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.setu.mesh.core.model.SosBeacon

/**
 * "An SOS was accepted for delivery -> alert every channel currently available." The one place
 * in the app that owns that decision, split out of `SetuService` so the service is left with
 * just the wiring (construct this, feed it live uplink state, done). `core` must never learn
 * WhatsApp exists: `MeshNode.onGatewayAlert` only ever hands over a beacon, and everything
 * downstream of that -- which channels exist, how a phone number becomes a wa.me link -- is a
 * transport/presentation concern that belongs here, not in the protocol engine.
 *
 * WhatsApp has no API for silently sending a message. The only supported path is an
 * `ACTION_VIEW` intent to `https://wa.me/<number>?text=<encoded>`, which opens WhatsApp with the
 * message pre-filled and needs a human tap to actually send -- and Android 10+ restricts
 * background activity launches, so a foreground service could not reliably start that activity
 * itself even if it wanted to. This is framed as a feature, not a workaround: a responder
 * confirming with one tap before an alert reaches emergency services is a deliberate
 * human-in-the-loop check, not a limitation being routed around.
 */
class GatewayDispatcher(context: Context) {

    private val appContext = context.applicationContext

    /** Alerts over every channel [hasCellService]/[hasInternet] currently allow. */
    fun dispatch(beacon: SosBeacon, phoneNumber: String, hasCellService: Boolean, hasInternet: Boolean) {
        var firedAny = false

        if (hasCellService) {
            if (sendSms(phoneNumber, beacon)) firedAny = true
        } else {
            Log.i(TAG, "SMS skipped for ${beacon.messageId.short()}: no cell service")
        }

        if (hasInternet) {
            if (postWhatsAppAction(phoneNumber, beacon)) firedAny = true
        } else {
            Log.i(TAG, "WhatsApp skipped for ${beacon.messageId.short()}: no internet")
        }

        if (!firedAny) {
            // The failure mode most likely to bite on stage: gateway mode enabled, a message
            // accepted for delivery, and nothing actually left the phone. Loud on purpose --
            // this is the one line that should make the demo operator glance at the right phone.
            Log.e(
                TAG,
                "GATEWAY ALERT FIRED NO CHANNEL for ${beacon.messageId.short()} -- " +
                    "hasCellService=$hasCellService hasInternet=$hasInternet " +
                    "smsPermission=${hasSmsPermission()}",
            )
        }
    }

    /**
     * Deliberately not queued or retried: `sendMultipartTextMessage` hands off to the carrier
     * immediately and Android reports failure via result `PendingIntent`s, which this
     * fire-and-forget path does not wire up. Acceptable for a hackathon gateway node that is,
     * by definition, the one phone with signal in the room.
     */
    private fun sendSms(number: String, beacon: SosBeacon): Boolean {
        if (!hasSmsPermission()) {
            Log.w(TAG, "SMS suppressed for ${beacon.messageId.short()}: SEND_SMS not granted")
            return false
        }
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val body = SosSummary.smsBody(beacon)
            // divideMessage + sendMultipartTextMessage, not sendTextMessage: once coordinates
            // and flags are folded into the body it routinely crosses 160 characters, and
            // sendTextMessage mangles anything past that instead of sending the rest as a
            // second segment.
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            Log.i(TAG, "Gateway SMS sent (${parts.size} segment(s)) to $number for ${beacon.messageId.short()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gateway SMS failed for ${beacon.messageId.short()}", e)
            false
        }
    }

    /**
     * Posts the human-in-the-loop WhatsApp prompt. Returns true once the notification is up --
     * that counts as this channel "firing" here, since actually sending the message is a
     * deliberate tap away by design, not something this process can complete on its own.
     */
    private fun postWhatsAppAction(number: String, beacon: SosBeacon): Boolean {
        return try {
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
            val intent = whatsAppIntent(number, beacon)

            // Belt-and-suspenders against ActivityNotFoundException: an https:// ACTION_VIEW is
            // exempt from Android 11+ package-visibility restrictions, so this only ever comes
            // back null on a device with no browser and neither WhatsApp app installed at all --
            // treat that the same as "channel unavailable" rather than posting a dead button.
            if (intent.resolveActivity(appContext.packageManager) == null) {
                Log.w(TAG, "No app can open the WhatsApp alert intent for ${beacon.messageId.short()}")
                return false
            }

            val pendingIntent = PendingIntent.getActivity(
                appContext,
                beacon.messageId.raw,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val peopleWord = if (beacon.souls == 1) "person" else "people"
            val notification = Notification.Builder(appContext, GATEWAY_ALERT_CHANNEL_ID)
                .setContentTitle("SOS accepted -- send WhatsApp alert")
                .setContentText("${beacon.flags.severity} · ${beacon.souls} $peopleWord")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_send, "Send WhatsApp alert", pendingIntent)
                .build()

            val notificationId = GATEWAY_ALERT_NOTIFICATION_ID_BASE + (beacon.messageId.raw and 0xFFFF)
            manager.notify(notificationId, notification)
            Log.i(TAG, "Gateway WhatsApp prompt posted for ${beacon.messageId.short()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gateway WhatsApp prompt failed for ${beacon.messageId.short()}", e)
            false
        }
    }

    /**
     * Targets the installed WhatsApp package directly when present (consumer or Business) so the
     * tap opens WhatsApp itself instead of a disambiguation sheet; falls back to no package
     * restriction (a browser opens wa.me, which redirects into WhatsApp anyway if it's actually
     * installed under a variant this app doesn't check) when neither is found. Android 11+
     * package visibility means `getPackageInfo` only sees `com.whatsapp`/`com.whatsapp.w4b` here
     * because both are declared in the manifest's `<queries>` block.
     */
    private fun whatsAppIntent(number: String, beacon: SosBeacon): Intent {
        val text = Uri.encode(SosSummary.whatsAppText(beacon))
        val uri = Uri.parse("https://wa.me/$number?text=$text")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val targetPackage = listOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE)
            .firstOrNull { isPackageInstalled(it) }
        if (targetPackage != null) {
            intent.setPackage(targetPackage)
        }
        return intent
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "SetuGateway"

        const val GATEWAY_ALERT_CHANNEL_ID = "setu_gateway_alert"

        /** Offset from SetuService's own notification ids so this stack never collides with them. */
        private const val GATEWAY_ALERT_NOTIFICATION_ID_BASE = 2_000

        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    }
}
