package com.setu.mesh.sim

import com.setu.mesh.core.link.Link
import com.setu.mesh.core.link.LinkCapabilities
import com.setu.mesh.core.link.LinkEvent
import com.setu.mesh.core.link.PeerHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * [Link] implementation for the simulator.
 *
 * This does NOT drive the node via the flow/coroutine path. The [World] stepping loop calls
 * `MeshNode.planNow()` and `MeshNode.onBeaconHeard()` directly — we never call `MeshNode.run()`.
 *
 * `setAdvertisedBeacons` just stores the current set; `scanFor` is never called by the step
 * loop (the world delivers beacons itself). The flow exists only to satisfy the interface.
 */
class SimLink(
    override val capabilities: LinkCapabilities = LinkCapabilities(advertisingSlots = 1),
) : Link {

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 64)
    override val events: Flow<LinkEvent> = _events

    /** The beacons this node is currently broadcasting. Read and written by [World] each tick. */
    var currentBeacons: List<ByteArray> = emptyList()

    override suspend fun setAdvertisedBeacons(beacons: List<ByteArray>) {
        currentBeacons = beacons
    }

    override suspend fun scanFor(windowMillis: Long) {
        // The step loop delivers beacons directly via MeshNode.onBeaconHeard().
        // This method is not used in the simulator.
    }

    override suspend fun sendBundle(peer: PeerHandle, payload: ByteArray): Boolean {
        // Rich bundles are out of scope for the simulator.
        return false
    }

    override suspend fun shutdown() {
        currentBeacons = emptyList()
    }
}
