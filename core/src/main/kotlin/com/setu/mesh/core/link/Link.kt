package com.setu.mesh.core.link

import kotlinx.coroutines.flow.Flow

/**
 * THE SEAM.
 *
 * Everything above this interface is pure protocol and is unit-testable on a laptop.
 * Everything below it is radio plumbing. `AndroidLink` drives real BLE; `SimLink` drives a
 * virtual world of a few hundred nodes. Neither one knows what a beacon *means*.
 *
 * Two rules keep this honest and both are load-bearing:
 *  1. This interface traffics in **opaque byte arrays**, never in protocol types. A Link that
 *     could parse a beacon would eventually be tempted to make a routing decision.
 *  2. Nothing in this package imports anything Android. The `:core` module uses the
 *     kotlin-jvm plugin precisely so that the compiler enforces that, not a code reviewer.
 *
 * Freeze this file early. Changing it mid-build blocks both people at once.
 */
interface Link {

    /** What the radio underneath can actually do. Read once at startup. */
    val capabilities: LinkCapabilities

    /** Inbound radio traffic. Cold until collected; a Link may assume a single collector. */
    val events: Flow<LinkEvent>

    /**
     * Replace the set of beacons currently being broadcast.
     *
     * Each element must be exactly [com.setu.mesh.core.model.SosBeacon.SIZE] bytes. If the radio
     * has fewer advertising slots than [beacons] has entries, the implementation round-robins
     * them (the "beacon carousel") rather than dropping any — many Android devices report
     * `isMultipleAdvertisementSupported() == false` and expose a single slot.
     *
     * Passing an empty list stops advertising.
     */
    suspend fun setAdvertisedBeacons(beacons: List<ByteArray>)

    /**
     * Listen for [windowMillis], then stop. Deliberately a bounded window rather than
     * start/stop calls, because Android throttles an app to **5 `startScan` calls per 30
     * seconds** and a naive duty cycle silently stops discovering anything. The rendezvous
     * scheduler issues at most one scan per epoch, which stays inside that budget.
     */
    suspend fun scanFor(windowMillis: Long)

    /**
     * Open a connection, push one bundle, close. Returns false if the peer was unreachable.
     * Only called by nodes rich enough in battery to afford a connection.
     */
    suspend fun sendBundle(peer: PeerHandle, payload: ByteArray): Boolean

    /** Release radios. Called on last-gasp shutdown. */
    suspend fun shutdown()
}

/** Opaque peer address. On Android this wraps a BLE MAC; in the simulator, a node index. */
@JvmInline
value class PeerHandle(val address: String)

/**
 * What the underlying radio supports. The power governor adapts to these rather than assuming
 * a best-case device, because the phones that matter in a disaster are the cheap ones.
 */
data class LinkCapabilities(
    /** Simultaneous advertising sets. Frequently 1 on budget hardware. */
    val advertisingSlots: Int = 1,
    /** BLE 5 extended advertising, i.e. whether a rich bundle can skip the GATT connection. */
    val supportsExtendedAdvertising: Boolean = false,
    /** Largest payload [sendBundle] will accept. */
    val maxBundleBytes: Int = 244,
    /** False for a radio that can only listen (a receive-only gateway, or a denied permission). */
    val canAdvertise: Boolean = true,
)

/** Inbound events from the radio. */
sealed interface LinkEvent {

    /**
     * A 24-byte beacon was heard. This is the whole multi-hop path in the common case: no
     * connection was opened, nothing was paired, and the sender does not know we exist.
     */
    data class BeaconHeard(
        val payload: ByteArray,
        val peer: PeerHandle,
        val rssiDbm: Int,
        val atMillis: Long,
    ) : LinkEvent {
        // ByteArray in a data class needs these, otherwise dedup-by-equality misbehaves.
        override fun equals(other: Any?): Boolean =
            this === other || (other is BeaconHeard &&
                payload.contentEquals(other.payload) && peer == other.peer && atMillis == other.atMillis)

        override fun hashCode(): Int = 31 * (31 * payload.contentHashCode() + peer.hashCode()) + atMillis.hashCode()
    }

    /** A rich bundle arrived over a connection. */
    data class BundleReceived(
        val payload: ByteArray,
        val peer: PeerHandle,
        val atMillis: Long,
    ) : LinkEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is BundleReceived &&
                payload.contentEquals(other.payload) && peer == other.peer && atMillis == other.atMillis)

        override fun hashCode(): Int = 31 * (31 * payload.contentHashCode() + peer.hashCode()) + atMillis.hashCode()
    }

    /** A scan window opened or closed. Used by the energy ledger to bill scan time. */
    data class ScanWindow(val open: Boolean, val atMillis: Long) : LinkEvent

    /** The radio became unusable (Bluetooth switched off, permission revoked). */
    data class RadioUnavailable(val reason: String, val atMillis: Long) : LinkEvent
}
