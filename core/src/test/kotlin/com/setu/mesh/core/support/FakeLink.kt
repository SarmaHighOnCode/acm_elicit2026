package com.setu.mesh.core.support

import com.setu.mesh.core.link.Link
import com.setu.mesh.core.link.LinkCapabilities
import com.setu.mesh.core.link.LinkEvent
import com.setu.mesh.core.link.PeerHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeLink(
    override val capabilities: LinkCapabilities = LinkCapabilities()
) : Link {
    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 64)
    override val events: Flow<LinkEvent> = _events

    val advertisedBeacons = mutableListOf<ByteArray>()
    var scannedWindows = mutableListOf<Long>()
    var bundlesSent = mutableListOf<Pair<PeerHandle, ByteArray>>()
    var isShutdown = false

    fun emitEvent(event: LinkEvent) {
        _events.tryEmit(event)
    }

    override suspend fun setAdvertisedBeacons(beacons: List<ByteArray>) {
        advertisedBeacons.clear()
        advertisedBeacons.addAll(beacons)
    }

    override suspend fun scanFor(windowMillis: Long) {
        scannedWindows.add(windowMillis)
        _events.tryEmit(LinkEvent.ScanWindow(open = true, atMillis = 0L))
        _events.tryEmit(LinkEvent.ScanWindow(open = false, atMillis = windowMillis))
    }

    override suspend fun sendBundle(peer: PeerHandle, payload: ByteArray): Boolean {
        bundlesSent.add(peer to payload)
        return true
    }

    override suspend fun shutdown() {
        isShutdown = true
    }
}
