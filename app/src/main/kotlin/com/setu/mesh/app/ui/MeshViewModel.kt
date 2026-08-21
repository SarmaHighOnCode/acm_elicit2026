package com.setu.mesh.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SosBeacon

/**
 * Polls [SetuService.carriedMessages] rather than collecting a flow: `MeshNode` has no
 * push-based outbox stream (only the aggregate `NodeSnapshot`), so the responder view refreshes
 * on a timer. [MeshScreen] drives [refresh] from a `LaunchedEffect` loop.
 */
class MeshViewModel {

    var carried by mutableStateOf<List<SosBeacon>>(emptyList())
        private set

    fun refresh() {
        // Only SOS beacons are "reports" to a responder -- RECEIPT/SAFE/etc. are protocol
        // plumbing this screen has no business surfacing.
        carried = SetuService.carriedMessages().filter { it.type == MessageType.SOS }
    }
}

/**
 * Sort order is a safety decision, not a display preference: CRITICAL first, then newest within
 * a severity, with own messages pinned to the top.
 */
fun sortForResponder(beacons: List<SosBeacon>, selfOriginRaw: Int): List<SosBeacon> =
    beacons.sortedWith(
        compareByDescending<SosBeacon> { it.origin.raw == selfOriginRaw }
            .thenByDescending { it.flags.severity.wire }
            .thenByDescending { it.epochMinute },
    )

fun hasKnownPosition(beacon: SosBeacon): Boolean = beacon.position != GeoPoint.UNKNOWN
