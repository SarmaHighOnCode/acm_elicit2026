package com.setu.mesh.core.engine

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.link.Link
import com.setu.mesh.core.link.LinkEvent
import com.setu.mesh.core.link.PeerHandle
import com.setu.mesh.core.model.DEFAULT_TTL
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.MILLIS_PER_MINUTE
import com.setu.mesh.core.model.MessageId
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.SETU_EPOCH_MILLIS
import com.setu.mesh.core.model.SituationFlags
import com.setu.mesh.core.model.SosBeacon
import com.setu.mesh.core.power.NeighbourEnergy
import com.setu.mesh.core.power.PowerGovernor
import com.setu.mesh.core.power.PowerTier
import com.setu.mesh.core.power.ProtocolTuning
import com.setu.mesh.core.power.RadioPlan
import com.setu.mesh.core.routing.ForwardingContext
import com.setu.mesh.core.routing.ForwardingPolicy
import com.setu.mesh.core.routing.Outbox
import com.setu.mesh.core.routing.RelayDecision
import com.setu.mesh.core.routing.SeenSet
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Everything the UI and the simulator need to render one node. */
data class NodeSnapshot(
    val id: NodeId,
    val tier: PowerTier = PowerTier.BRIDGE,
    val batteryPercent: Int = 100,
    val carrying: Int = 0,
    val neighbourCount: Int = 0,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val lastGasp: Boolean = false,
    /** The SOS this node originated, if any. */
    val ownSos: SosBeacon? = null,
    /** Furthest hop count observed for our own SOS, i.e. how far it is known to have travelled. */
    val ownSosMaxHops: Int = 0,
    /** True once a RECEIPT for our own SOS came back through the mesh. */
    val ownSosDelivered: Boolean = false,
    val energyMilliampHours: Double = 0.0,
    val beaconsRelayed: Long = 0L,
)

/**
 * The protocol engine. Transport-agnostic by construction: it talks to a [Link] and a
 * [NodeHost] and knows nothing about Bluetooth, Android, or the simulator.
 *
 * That is the point. The same instance of this class is driven by real BLE radios on a phone
 * and by a virtual world of two hundred nodes in `:sim`, which is what makes the scale claims
 * in the demo honest rather than a separate mock implementation that happens to agree.
 *
 * The decision methods ([onBeaconHeard], [planNow], [beaconsToAdvertise]) are deliberately
 * synchronous and side-effect-visible so they can be unit-tested with no coroutine machinery and
 * stepped deterministically by the simulator. [run] is a thin loop over them.
 */
class MeshNode(
    val id: NodeId,
    private val link: Link,
    private val host: NodeHost,
    private val governor: PowerGovernor = PowerGovernor(),
    private val random: Random = Random.Default,
    /** See [ProtocolTuning]. Default reproduces normal forwarding-policy behaviour exactly. */
    private val tuning: ProtocolTuning = ProtocolTuning.DEFAULT,
) {
    private val seen = SeenSet()
    private val outbox = Outbox()
    private val neighbours = LinkedHashMap<Int, NeighbourEnergy>()

    private var sequence = 0
    private var ownMessageId: MessageId? = null
    private var carouselOffset = 0

    private val _snapshot = MutableStateFlow(NodeSnapshot(id = id))
    val snapshot: StateFlow<NodeSnapshot> = _snapshot.asStateFlow()

    val ledger get() = governor.ledger

    // ---------------------------------------------------------------- origination

    /**
     * Create this node's own SOS. It is carried and broadcast unconditionally, at every battery
     * level, until a RECEIPT comes back or the user marks themselves safe.
     */
    fun originateSos(flags: SituationFlags, souls: Int, nowMillis: Long = host.nowMillis()): MessageId {
        val epochMinute = toEpochMinute(nowMillis)
        val messageId = MessageId.of(id, ++sequence, epochMinute)

        val beacon = SosBeacon(
            type = MessageType.SOS,
            ttl = DEFAULT_TTL,
            hops = 0,
            messageId = messageId,
            origin = id,
            position = host.position() ?: GeoPoint.UNKNOWN,
            epochMinute = epochMinute,
            flags = flags,
            souls = souls,
            originBattery = host.batteryPercent(),
        )

        ownMessageId = messageId
        seen.addIfNew(dedupKey(beacon), nowMillis)
        outbox.put(beacon, nowMillis, isOwn = true)
        refreshSnapshot(nowMillis)
        return messageId
    }

    /** Cancel our SOS. Propagates as a SAFE beacon so the mesh reclaims buffer and airtime. */
    fun markSafe(nowMillis: Long = host.nowMillis()) {
        val original = ownMessageId ?: return
        val epochMinute = toEpochMinute(nowMillis)
        val safe = SosBeacon(
            type = MessageType.SAFE,
            ttl = DEFAULT_TTL,
            hops = 0,
            // A SAFE/RECEIPT carries the id of the message it refers to, not a fresh id. There
            // is no room in 24 bytes for both, and the reference is the useful half. Dedup
            // stays correct because the seen-set key folds in the message type.
            messageId = original,
            origin = id,
            position = host.position() ?: GeoPoint.UNKNOWN,
            epochMinute = epochMinute,
            flags = SituationFlags(),
            souls = 0,
            originBattery = host.batteryPercent(),
        )
        outbox.remove(original)
        ownMessageId = null
        seen.addIfNew(dedupKey(safe), nowMillis)
        outbox.put(safe, nowMillis, isOwn = true)
        refreshSnapshot(nowMillis)
    }

    /**
     * Emit a RECEIPT for [forMessage] into the mesh. Carriers that hear it drop the original,
     * which is how delivery confirmation returns airtime and buffer to the network.
     */
    fun originateReceipt(forMessage: MessageId, nowMillis: Long = host.nowMillis()): MessageId {
        val epochMinute = toEpochMinute(nowMillis)
        val receipt = SosBeacon(
            type = MessageType.RECEIPT,
            ttl = DEFAULT_TTL,
            hops = 0,
            messageId = forMessage,
            origin = id,
            position = host.position() ?: GeoPoint.UNKNOWN,
            epochMinute = epochMinute,
            flags = SituationFlags(),
            souls = 0,
            originBattery = host.batteryPercent(),
        )
        seen.addIfNew(dedupKey(receipt), nowMillis)
        outbox.remove(forMessage)
        outbox.put(receipt, nowMillis, isOwn = true)
        refreshSnapshot(nowMillis)
        return forMessage
    }

    // ---------------------------------------------------------------- reception

    /**
     * Handle one overheard beacon. This is the entire multi-hop path in the common case: no
     * connection was opened and the sender does not know we exist.
     */
    fun onBeaconHeard(payload: ByteArray, from: PeerHandle, nowMillis: Long): RelayDecision {
        val beacon = BeaconCodec.decode(payload)
            ?: return RelayDecision.Suppress(com.setu.mesh.core.routing.SuppressReason.TTL_EXHAUSTED)

        // Every beacon doubles as a neighbour-energy advertisement and a coarse clock sample.
        neighbours[beacon.origin.raw] = NeighbourEnergy(beacon.origin, beacon.originBattery, nowMillis)
        governor.noteBeaconTimestamp(beacon.epochMinute, nowMillis, host.hasTrustedClock())
        outbox.noteCarrier(beacon.messageId, beacon.origin.raw)

        when (beacon.type) {
            MessageType.RECEIPT, MessageType.SAFE -> {
                // Delivery confirmed or cancelled: stop carrying the original. Freeing airtime
                // is the point -- confirmation is an energy optimisation, not just a nicety.
                outbox.remove(beacon.messageId)
                if (beacon.messageId == ownMessageId && beacon.type == MessageType.RECEIPT) {
                    _snapshot.value = _snapshot.value.copy(ownSosDelivered = true)
                }
            }

            MessageType.SOS -> {
                if (beacon.messageId == ownMessageId) {
                    // Our own SOS came back to us from further out; record how far it reached.
                    _snapshot.value = _snapshot.value.copy(
                        ownSosMaxHops = maxOf(_snapshot.value.ownSosMaxHops, beacon.hops),
                    )
                }
            }

            else -> Unit
        }

        if (!seen.addIfNew(dedupKey(beacon), nowMillis)) {
            return RelayDecision.Suppress(com.setu.mesh.core.routing.SuppressReason.PROBABILISTIC)
        }

        val relayed = beacon.relayed()
            ?: return RelayDecision.Suppress(com.setu.mesh.core.routing.SuppressReason.TTL_EXHAUSTED)

        val decision = ForwardingPolicy.decide(
            beacon = relayed,
            context = ForwardingContext(
                selfBatteryPercent = host.batteryPercent(),
                selfCharging = host.isCharging(),
                neighboursHoldingCopy = outbox.get(beacon.messageId)?.neighboursHoldingCopy ?: 0,
                isOwnMessage = beacon.origin == id,
            ),
            random = random,
            energyGateOverride = tuning.energyGateOverride,
        )

        if (decision is RelayDecision.Relay) {
            outbox.put(relayed, nowMillis, isOwn = false)
            governor.ledger.recordRelay()
        }

        refreshSnapshot(nowMillis)
        return decision
    }

    // ---------------------------------------------------------------- radio plan

    fun planNow(nowMillis: Long = host.nowMillis()): RadioPlan {
        expireNeighbours(nowMillis)
        return governor.plan(
            selfId = id,
            batteryPercent = host.batteryPercent(),
            charging = host.isCharging(),
            neighbours = neighbours.values.toList(),
            nowMillis = nowMillis,
        )
    }

    /**
     * Next slice of the beacon carousel. When the radio has fewer advertising slots than we are
     * carrying messages, successive calls rotate through the outbox so everything gets airtime.
     */
    fun beaconsToAdvertise(slots: Int, nowMillis: Long): List<ByteArray> {
        val ordered = outbox.encodedCarousel(nowMillis)
        if (ordered.isEmpty()) return emptyList()
        if (slots >= ordered.size) return ordered

        val slice = List(slots) { ordered[(carouselOffset + it) % ordered.size] }
        carouselOffset = (carouselOffset + slots) % ordered.size
        return slice
    }

    // ---------------------------------------------------------------- run loop

    /** Drives [link] from [planNow]. Cancel the surrounding scope to stop. */
    suspend fun run() = coroutineScope {
        launch {
            link.events.collect { event ->
                when (event) {
                    is LinkEvent.BeaconHeard -> onBeaconHeard(event.payload, event.peer, event.atMillis)
                    is LinkEvent.BundleReceived -> Unit // rich bundles: see docs/PROTOCOL.md roadmap
                    is LinkEvent.ScanWindow -> Unit
                    is LinkEvent.RadioUnavailable -> Unit
                }
            }
        }

        while (coroutineContext.isActive) {
            val now = host.nowMillis()
            val plan = planNow(now)

            outbox.purgeStale(now)
            seen.purgeExpired(now)

            val beacons = beaconsToAdvertise(link.capabilities.advertisingSlots, now)
            if (link.capabilities.canAdvertise) {
                link.setAdvertisedBeacons(beacons)
                governor.ledger.billAdvertising(plan.beaconIntervalMillis, plan.beaconIntervalMillis)
            }

            if (plan.scanThisEpoch && plan.inRendezvousWindow && plan.scanWindowMillis > 0) {
                _snapshot.value = _snapshot.value.copy(scanning = true)
                link.scanFor(plan.scanWindowMillis)
                governor.ledger.billScan(plan.scanWindowMillis)
                _snapshot.value = _snapshot.value.copy(scanning = false)
            }

            refreshSnapshot(now)
            delay(plan.beaconIntervalMillis.coerceAtLeast(MIN_LOOP_DELAY_MILLIS))
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Seen-set key folds the message type in, so a RECEIPT that references message M is deduped
     * independently of M itself while still pointing at it.
     */
    private fun dedupKey(beacon: SosBeacon): MessageId =
        MessageId(beacon.messageId.raw xor (beacon.type.wire * TYPE_MIX))

    private fun expireNeighbours(nowMillis: Long) {
        neighbours.entries.removeAll { nowMillis - it.value.lastHeardMillis > NEIGHBOUR_TTL_MILLIS }
    }

    private fun refreshSnapshot(nowMillis: Long) {
        val plan = governor.plan(
            selfId = id,
            batteryPercent = host.batteryPercent(),
            charging = host.isCharging(),
            neighbours = neighbours.values.toList(),
            nowMillis = nowMillis,
        )
        val own = ownMessageId?.let { outbox.get(it)?.beacon }
        _snapshot.value = _snapshot.value.copy(
            tier = plan.tier,
            batteryPercent = host.batteryPercent(),
            carrying = outbox.size,
            neighbourCount = neighbours.size,
            advertising = plan.advertising,
            lastGasp = plan.lastGasp,
            ownSos = own,
            energyMilliampHours = governor.ledger.totalMilliampHours,
            beaconsRelayed = governor.ledger.beaconsRelayed,
        )
    }

    private fun toEpochMinute(nowMillis: Long): Int =
        ((nowMillis - SETU_EPOCH_MILLIS) / MILLIS_PER_MINUTE).toInt()

    private companion object {
        const val NEIGHBOUR_TTL_MILLIS = 5 * 60 * 1000L
        const val MIN_LOOP_DELAY_MILLIS = 200L
        const val TYPE_MIX = 0x9E3779B1.toInt()
    }
}
