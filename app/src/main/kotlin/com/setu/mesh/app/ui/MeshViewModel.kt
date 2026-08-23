package com.setu.mesh.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.setu.mesh.app.service.SelfFix
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.core.geo.BearingConfidenceBand
import com.setu.mesh.core.geo.bearingConfidenceBand
import com.setu.mesh.core.geo.bearingDegrees
import com.setu.mesh.core.geo.bearingUncertaintyDegrees
import com.setu.mesh.core.geo.compassPoint
import com.setu.mesh.core.geo.distanceMetres
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.SosBeacon
import kotlin.math.hypot
import kotlin.math.roundToInt

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
 * How much a responder should trust the bearing/distance shown for one beacon.
 *
 * Replaces the old self-only, boolean `isSelfFixDegraded`: that flag knew only the receiver's
 * own fix quality and nothing about the sender's, and nothing about how far apart the two
 * devices actually are (RC2). Two ±10 m fixes 15 m apart produce a bearing whose 1-sigma
 * uncertainty is ~43 degrees (see [bearingUncertaintyDegrees]) -- past 90 degrees at 2-sigma,
 * which reads to a user as "flipped" even though nothing is broken. That is physics, not a
 * defect; the actual bug was drawing a confident dot over it regardless.
 *
 * Three bands rather than a boolean because "the number is fine" and "there is no number" are
 * not the only two honest states: a report a few metres away can have a perfectly good distance
 * figure while its direction is genuine noise.
 */
sealed class PositionConfidence {
    /** sigma_bearing <= 20 degrees: cardinal direction and distance are both worth showing. */
    data class Confident(val distanceMetres: Double, val sigmaMetres: Double, val compassPoint: String) :
        PositionConfidence()

    /** 20-50 degrees: distance is still worth showing; a direction claim is not. */
    data class Approximate(val distanceMetres: Double, val sigmaMetres: Double) : PositionConfidence()

    /**
     * More than 50 degrees, or the sender's accuracy class is unknown, or the self fix is
     * null/stale. [distanceMetres] and [sigmaMetres] are still populated whenever a distance
     * figure could be computed at all (self fix present and beacon position known) -- only the
     * direction claim is withheld, never the distance itself.
     */
    data class Unusable(val distanceMetres: Double?, val sigmaMetres: Double?) : PositionConfidence()
}

/**
 * Computes [PositionConfidence] for one beacon from the receiver's own fix and the sender's
 * reported accuracy class ([SosBeacon.senderAccuracyMetres], carried on the wire since it was
 * added to two previously-reserved bits of byte 0). Pure function of its three arguments -- no
 * hidden clock reads beyond [nowMillis] -- so it is at least reviewable end to end in this one
 * place even though `:app` has no test source set to verify it in directly; the angle maths it
 * calls into ([bearingUncertaintyDegrees], [bearingConfidenceBand], [bearingDegrees],
 * [compassPoint]) lives in `:core`'s `Geo.kt` and is covered by `GeoTest`.
 */
fun positionConfidence(selfFix: SelfFix?, beacon: SosBeacon, nowMillis: Long): PositionConfidence {
    if (selfFix == null || beacon.position == GeoPoint.UNKNOWN) {
        return PositionConfidence.Unusable(distanceMetres = null, sigmaMetres = null)
    }

    val distance = distanceMetres(selfFix.point, beacon.position)
    val selfAccuracy = selfFix.accuracyMetres?.toDouble()
    val senderAccuracy = beacon.senderAccuracyMetres
    // Android's Location.getAccuracy() is a 68% (~1-sigma) radius, so two independent fixes
    // combine in quadrature -- see bearingUncertaintyDegrees' doc for the geometry.
    val sigmaMetres = if (selfAccuracy != null && senderAccuracy != null) hypot(selfAccuracy, senderAccuracy) else null

    // A fix can be precise and still far too old -- the age gate that used to be
    // isSelfFixDegraded's other input folds in here instead of living beside this function as a
    // second source of truth for "can we trust this fix".
    val ageMillis = nowMillis - selfFix.atMillis
    val stale = ageMillis > DEGRADED_AGE_THRESHOLD_MILLIS

    if (sigmaMetres == null || stale) {
        return PositionConfidence.Unusable(distance, sigmaMetres)
    }

    val sigmaDegrees = bearingUncertaintyDegrees(sigmaMetres, distance)
    return when (bearingConfidenceBand(sigmaDegrees)) {
        BearingConfidenceBand.CONFIDENT -> PositionConfidence.Confident(
            distanceMetres = distance,
            sigmaMetres = sigmaMetres,
            compassPoint = compassPoint(bearingDegrees(selfFix.point, beacon.position)),
        )
        BearingConfidenceBand.APPROXIMATE -> PositionConfidence.Approximate(distance, sigmaMetres)
        BearingConfidenceBand.UNUSABLE -> PositionConfidence.Unusable(distance, sigmaMetres)
    }
}

/**
 * The combined 1-sigma figure behind any [PositionConfidence], when one exists -- one place that
 * knows which variant carries it, shared by [com.setu.mesh.app.ui.components.RelativeMap]'s
 * uncertainty disc and its auto-range calculation.
 */
val PositionConfidence.sigmaMetresOrNull: Double?
    get() = when (this) {
        is PositionConfidence.Confident -> sigmaMetres
        is PositionConfidence.Approximate -> sigmaMetres
        is PositionConfidence.Unusable -> sigmaMetres
    }

/**
 * The exact three-band card text from this task's spec table, shared by
 * [com.setu.mesh.app.ui.components.SosCard] and [com.setu.mesh.app.ui.components.RelativeMap]'s
 * selected-beacon panel so the wording has one home. Null only for [PositionConfidence.Unusable]
 * with no distance at all -- no self fix, or the beacon's own position unknown -- where there is
 * honestly nothing to show.
 */
fun formatPositionConfidenceLine(confidence: PositionConfidence): String? = when (confidence) {
    is PositionConfidence.Confident ->
        "${confidence.compassPoint} · ${confidence.distanceMetres.roundToInt()} m ±${confidence.sigmaMetres.roundToInt()} m"
    is PositionConfidence.Approximate ->
        "~${confidence.distanceMetres.roundToInt()} m ±${confidence.sigmaMetres.roundToInt()} m · direction approximate"
    is PositionConfidence.Unusable -> {
        val distance = confidence.distanceMetres
        if (distance == null) {
            null
        } else {
            val sigmaSuffix = confidence.sigmaMetres?.let { " ±${it.roundToInt()} m" } ?: ""
            "within ~${distance.roundToInt()} m$sigmaSuffix"
        }
    }
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

/** Past this age, a self fix is not fresh enough to support a bearing claim -- see [positionConfidence]. */
private const val DEGRADED_AGE_THRESHOLD_MILLIS = 60_000L
