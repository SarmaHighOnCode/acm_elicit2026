package com.setu.mesh.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.setu.mesh.app.service.SelfFix
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.SosBeacon

/**
 * Polls [SetuService.carriedMessages] rather than collecting a flow: `MeshNode` has no
 * push-based outbox stream (only the aggregate `NodeSnapshot`), so the responder view refreshes
 * on a timer. [MeshScreen] drives [refresh] from a `LaunchedEffect` loop.
 */
class MeshViewModel {

    /**
     * Other people's outstanding SOS only -- this node's own SOS is excluded here, once, rather
     * than sorted-but-pinned as it was before B11. "Help others" is a section about other
     * people; this node's own status already has a dedicated home on the SOS screen
     * (`StatusLadder`, `ownSosMaxHops`, `ownSosDelivered`).
     */
    var carried by mutableStateOf<List<SosBeacon>>(emptyList())
        private set

    /**
     * Refreshed on the same tick as [carried] rather than read during composition. Reading it
     * inline in the composable worked only by accident: it is a plain field, so nothing would
     * recompose when the first GPS fix landed -- the map would stay on "waiting for fix" until
     * some unrelated state change happened to trigger a redraw.
     */
    var selfFix by mutableStateOf<SelfFix?>(null)
        private set

    /**
     * Smoothed direct-signal strength per origin. A null value means that origin has not been
     * heard first-hand recently -- every relayed report included -- and must render as unknown.
     */
    var signalDbmByOrigin by mutableStateOf<Map<Int, Int?>>(emptyMap())
        private set

    fun refresh() {
        // A NodeId raw is always in 0..0xFFFFFF, so -1 is a safe sentinel for "id not known
        // yet" (mesh not started, or the first snapshot hasn't landed). Guard it explicitly:
        // when the id is unknown, filter nothing -- a null/unknown id must never silently empty
        // this screen by accidentally matching every beacon.
        val selfOriginRaw = SetuService.snapshot.value?.id?.raw ?: UNKNOWN_ORIGIN_RAW

        // Only SOS beacons are "reports" to a responder -- RECEIPT/SAFE/etc. are protocol
        // plumbing this screen has no business surfacing.
        carried = collapseByOrigin(
            SetuService.carriedMessages()
                .filter { it.type == MessageType.SOS }
                .filter { selfOriginRaw == UNKNOWN_ORIGIN_RAW || it.origin.raw != selfOriginRaw },
        )
        selfFix = SetuService.selfFix()
        // Read once per refresh, alongside `carried`, for the same reason selfFix is: these are
        // plain reads off the service, and sampling them during composition means nothing
        // recomposes when they change.
        signalDbmByOrigin = carried.associate { it.origin.raw to SetuService.directSignalDbm(it.origin.raw) }
    }

    companion object {
        private const val UNKNOWN_ORIGIN_RAW = -1
    }
}

/**
 * Sort order is a safety decision, not a display preference: CRITICAL first, then newest within
 * a severity. Own-origin beacons never reach this function -- they are filtered out in
 * [MeshViewModel.refresh] -- so there is no pinning concern here any more.
 */
fun sortForResponder(beacons: List<SosBeacon>): List<SosBeacon> =
    beacons.sortedWith(
        compareByDescending<SosBeacon> { it.flags.severity.wire }
            .thenByDescending { it.epochMinute },
    )

fun hasKnownPosition(beacon: SosBeacon): Boolean = beacon.position != GeoPoint.UNKNOWN

/**
 * One entry per person, not one per packet.
 *
 * A victim who edits their triage after sending resends, and a resend necessarily carries a new
 * [com.setu.mesh.core.model.MessageId] -- dedup is keyed on it, so reusing the id would stop the
 * correction propagating at all. The originator drops its own superseded copy, but peers that
 * already heard it keep theirs until it ages out; there is no recall mechanism in 24 bytes. So a
 * responder can legitimately be holding several beacons describing one situation, and showing
 * them as separate people is both wrong and dangerous -- it inflates the apparent casualty count.
 *
 * Newest wins. Ties break on **severity, highest first**, because `epochMinute` is
 * minute-resolution by design (`docs/PROTOCOL.md` §2 -- second precision is worthless in a
 * disaster and costs a byte we do not have) and two resends inside one minute are genuinely
 * unordered. Faced with an unresolvable tie, over-triage is the safe direction to err in. Fewest
 * hops breaks any remaining tie, preferring the least-relayed copy.
 */
fun collapseByOrigin(beacons: List<SosBeacon>): List<SosBeacon> =
    beacons
        .groupBy { it.origin.raw }
        .values
        .map { fromOneOrigin ->
            fromOneOrigin.maxWith(
                compareBy<SosBeacon> { it.epochMinute }
                    .thenBy { it.flags.severity.wire }
                    .thenByDescending { it.hops },
            )
        }

/**
 * True when the self fix is too old or too imprecise to support a meaningful bearing. Two
 * phones 5 m apart, each with a ±10 m fix, cannot produce a real direction to each other --
 * that is physics, not a bug, and both [com.setu.mesh.app.ui.components.RelativeMap] and
 * [com.setu.mesh.app.ui.components.SosCard] use this to say so instead of showing a confident
 * -looking number the fix cannot actually support.
 */
fun isSelfFixDegraded(selfFix: SelfFix?, nowMillis: Long): Boolean {
    if (selfFix == null) return true
    val ageMillis = nowMillis - selfFix.atMillis
    // Unknown accuracy is treated as degraded: a fix that carries no quality information is
    // not evidence of a good one.
    val accuracy = selfFix.accuracyMetres ?: return true
    return accuracy > DEGRADED_ACCURACY_THRESHOLD_METRES ||
        ageMillis > DEGRADED_AGE_THRESHOLD_MILLIS
}

/**
 * Coarse proximity from radio signal strength, for the near case GPS cannot resolve.
 *
 * Two phones five metres apart carry fixes whose combined error is larger than the distance
 * between them, so the map can honestly say ~22 m for a person standing next to you. RSSI has the
 * opposite failure mode: useless for direction, but genuinely informative up close.
 *
 * Deliberately **bands, never metres**. Converting RSSI to a distance requires a path-loss model
 * that multipath, body absorption and device orientation each break by a factor of two or more;
 * a number like "6.3 m" derived that way is a fabrication with a decimal point on it. A band is
 * what the measurement actually supports.
 *
 * Returns null when [signalDbm] is null -- unknown, which is not the same as far.
 */
fun proximityLabel(signalDbm: Int?): String? = when {
    signalDbm == null -> null
    signalDbm >= -60 -> "very close"
    signalDbm >= -75 -> "nearby"
    signalDbm >= -88 -> "in range"
    else -> "far"
}

private const val DEGRADED_ACCURACY_THRESHOLD_METRES = 30f
private const val DEGRADED_AGE_THRESHOLD_MILLIS = 60_000L
