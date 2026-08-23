package com.setu.mesh.app.gateway

import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.SosBeacon
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One place to describe an SOS in text. SMS, the WhatsApp pre-fill, and the QR "last known
 * state" card (SosScreen) all need the same underlying facts -- severity, souls, flags,
 * position -- and would drift out of sync as separate copies of the same string-building logic
 * the moment one of them was tweaked and the others weren't.
 *
 * Lives in `:app`, not `:core`: a Google Maps URL and human-readable labels are presentation,
 * and `core` has no reason to know either exists.
 */
object SosSummary {

    /**
     * SMS body. Not length-capped here -- [android.telephony.SmsManager.divideMessage] and
     * `sendMultipartTextMessage` (see GatewayDispatcher) handle a body over 160 characters
     * correctly, so this favours completeness over squeezing into one segment.
     */
    fun smsBody(beacon: SosBeacon): String = buildString {
        append("SafeHop SOS: ")
        append(beacon.flags.severity)
        append(", ")
        append(beacon.souls)
        append(if (beacon.souls == 1) " person" else " people")
        val flags = flagLabels(beacon)
        if (flags.isNotEmpty()) {
            append(", ")
            append(flags.joinToString(", "))
        }
        append(", ")
        append(beacon.hops)
        append(" hop(s) from ")
        append(beacon.origin.short())
        append(locationSuffix(beacon))
    }

    /**
     * Text for the wa.me `text=` pre-fill. WhatsApp carries no per-segment cost the way SMS
     * does, so this includes the maps link too -- the one thing worth leaving out of the SMS
     * body when every character there might tip it into a second segment.
     */
    fun whatsAppText(beacon: SosBeacon): String = buildString {
        append("SafeHop SOS ").append(beacon.messageId.short()).append('\n')
        append(beacon.flags.severity).append(" · ").append(beacon.souls)
        append(if (beacon.souls == 1) " person" else " people")
        val flags = flagLabels(beacon)
        if (flags.isNotEmpty()) {
            append(" · ").append(flags.joinToString(", "))
        }
        append('\n')
        append(beacon.hops).append(" hop(s) from ").append(beacon.origin.short())
        append('\n')
        if (beacon.position != GeoPoint.UNKNOWN) {
            append(mapsUrl(beacon.position))
        } else {
            append("Location: unknown")
        }
    }

    /**
     * Plain "lat,lon" Google Maps link. Callers must check the position is not
     * [GeoPoint.UNKNOWN] first -- that sentinel formats as a plausible-looking but meaningless
     * coordinate (-214.7483648) if fed through here directly.
     */
    fun mapsUrl(position: GeoPoint): String = "https://maps.google.com/?q=${position.latitude},${position.longitude}"

    /**
     * Payload for the SOS screen's QR "last known state" card (SosScreen). Plain text, maps URL
     * first so any phone's stock camera app offers a tappable link without needing a dedicated
     * QR reader, then a human-readable summary underneath for a reader that only shows raw text.
     *
     * [nowMillis] is the moment this card was rendered, not the beacon's own `epochMinute` --
     * this is a "here is what I can tell you right now" card, not a replay of the original send.
     *
     * When there is no GPS fix, [SosBeacon.position] is [GeoPoint.UNKNOWN] -- `Int.MIN_VALUE`
     * per axis, i.e. -214.7483648 if naively formatted as degrees. The maps URL line is omitted
     * entirely in that case rather than ever putting that sentinel in front of a rescuer as a
     * real coordinate.
     */
    fun qrPayload(beacon: SosBeacon, nowMillis: Long): String = buildString {
        if (beacon.position != GeoPoint.UNKNOWN) {
            append(mapsUrl(beacon.position))
        } else {
            append("Location: unknown")
        }
        append('\n')
        append("SafeHop SOS ").append(beacon.messageId.short()).append('\n')
        append("Severity: ").append(beacon.flags.severity).append('\n')
        append("People: ").append(beacon.souls).append('\n')
        val flags = flagLabels(beacon)
        append("Flags: ").append(if (flags.isEmpty()) "none" else flags.joinToString(", ")).append('\n')
        append("From: ").append(beacon.origin.short()).append('\n')
        append("Time: ").append(isoUtc(nowMillis))
    }

    private fun isoUtc(millis: Long): String =
        Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun locationSuffix(beacon: SosBeacon): String =
        if (beacon.position != GeoPoint.UNKNOWN) " @ ${beacon.position.latitude},${beacon.position.longitude}" else ""

    /** Same label set and order as SosCard's situationLine, so SMS/WhatsApp/QR read the same as the on-screen triage. */
    private fun flagLabels(beacon: SosBeacon): List<String> = buildList {
        if (beacon.flags.trapped) add("trapped")
        if (beacon.flags.medicalNeed) add("medical")
        if (beacon.flags.waterRising) add("water rising")
        if (beacon.flags.vulnerableOccupant) add("vulnerable occupant")
        if (beacon.flags.mobilityImpaired) add("mobility impaired")
    }
}
