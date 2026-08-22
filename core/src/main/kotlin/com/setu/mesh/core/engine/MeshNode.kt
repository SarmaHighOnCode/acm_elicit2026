package com.setu.mesh.core.engine

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.link.Link
import com.setu.mesh.core.link.LinkEvent
import com.setu.mesh.core.link.PeerHandle
import com.setu.mesh.core.link.RadioProfile
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
import kotlin.math.roundToInt
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
    /** True while attentive mode is requested -- foreground, or a recent SOS. See [MeshNode.setAttentive]. */
    val attentive: Boolean = false,
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
    private val keyStore: com.setu.mesh.core.crypto.KeyStore = object : com.setu.mesh.core.crypto.KeyStore {
        override fun getPublicKey(originId: NodeId): java.security.PublicKey? = null
    }
) {
    private val seen = SeenSet()
    private val outbox = Outbox()
    private val neighbours = LinkedHashMap<Int, NeighbourEnergy>()

    /** Smoothed signal strength for origins heard **directly**, keyed by origin id. */
    private val directSignals = LinkedHashMap<Int, DirectSignal>()

    private var sequence = 0
    private var ownMessageId: MessageId? = null
    private var carouselOffset = 0
    private var lastRadioProfile: RadioProfile? = null

    // Attentive mode: see setAttentive() and isAttentive() below. Two independent triggers
    // (screen foreground, recent SOS activity) fold into one gate rather than each caller
    // threading its own "am I attentive" condition through planNow/refreshSnapshot.
    @Volatile
    private var foregroundAttentive: Boolean = false
    @Volatile
    private var attentiveUntilMillis: Long = 0L

    private val _snapshot = MutableStateFlow(NodeSnapshot(id = id))
    val snapshot: StateFlow<NodeSnapshot> = _snapshot.asStateFlow()

    val ledger get() = governor.ledger

    // ---------------------------------------------------------------- origination

    /**
     * Create this node's own SOS. It is carried and broadcast unconditionally, at every battery
     * level, until a RECEIPT comes back or the user marks themselves safe.
     *
     * Calling this again while an SOS is outstanding is a **resend**: it supersedes the previous
     * one rather than adding to it, so a node never carries more than one of its own SOS at a
     * time. Peers that already heard the superseded beacon keep their copy until it ages out --
     * there is no recall mechanism in 24 bytes -- which is why the responder view collapses
     * multiple reports from one origin down to the newest.
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

        // A resend *replaces* our SOS; it never adds a second one. The id has to change --
        // dedup is keyed on it, so reusing it would make every peer that already saw the old
        // beacon suppress the correction and the updated triage would never propagate -- which
        // means the superseded entry has to be dropped here, explicitly.
        //
        // Without this, every "tap to resend" and every triage toggle leaves another own-copy
        // in the outbox. Own entries are exempt from both Outbox.purgeStale and
        // Outbox.evictOne, so nothing ever reclaims them: the carried count only climbs, and
        // past capacity the outbox grows without bound.
        ownMessageId?.let { outbox.remove(it) }

        ownMessageId = messageId
        seen.addIfNew(dedupKey(beacon), nowMillis)
        outbox.put(beacon, nowMillis, isOwn = true)
        // The originator wants its RECEIPT back fast, not whenever the next rendezvous window
        // happens to land.
        attentiveUntilMillis = nowMillis + ATTENTIVE_AFTER_SOS_MILLIS
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
    fun onBeaconHeard(
        payload: ByteArray,
        from: PeerHandle,
        nowMillis: Long,
        /** Radio signal strength this frame arrived at, when the transport reports one. */
        rssiDbm: Int? = null,
    ): RelayDecision {
        val beacon = BeaconCodec.decode(payload)
            ?: return RelayDecision.Suppress(com.setu.mesh.core.routing.SuppressReason.TTL_EXHAUSTED)

        noteDirectSignal(beacon, rssiDbm, nowMillis)

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
                // Any SOS, ours or not: the neighbourhood is live right now, so relay onward
                // promptly instead of sitting on it for up to a minute waiting on the next
                // rendezvous window. RECEIPT/SAFE do not trigger this -- they mean the situation
                // is winding down, not starting.
                attentiveUntilMillis = nowMillis + ATTENTIVE_AFTER_SOS_MILLIS
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

    /**
     * Hold or release attentive mode for as long as the app is in the foreground. Driven from
     * `:app` by Activity lifecycle (`onStart`/`onStop`), not a Compose lifecycle observer.
     *
     * The screen being on already costs an order of magnitude more than the radio, so lighting
     * up the scanner too is nearly free by comparison -- and a user staring at the app expects
     * it to be live, not waiting out a rendezvous window that might be fifty seconds away.
     */
    fun setAttentive(active: Boolean) {
        foregroundAttentive = active
    }

    /**
     * One gate for every attentive trigger (foreground, own SOS, a neighbour's SOS) so
     * [planNow] and [refreshSnapshot] can never disagree about whether the node is attentive
     * right now. Do not inline this condition at either call site.
     */
    private fun isAttentive(nowMillis: Long): Boolean =
        foregroundAttentive || nowMillis < attentiveUntilMillis

    fun planNow(nowMillis: Long = host.nowMillis()): RadioPlan {
        expireNeighbours(nowMillis)
        expireDirectSignals(nowMillis)
        return governor.plan(
            selfId = id,
            batteryPercent = host.batteryPercent(),
            charging = host.isCharging(),
            neighbours = neighbours.values.toList(),
            nowMillis = nowMillis,
            attentive = isAttentive(nowMillis),
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

    /**
     * Snapshot of everything this node currently carries, in the same order the beacon
     * carousel would broadcast them. Read-only: unlike [beaconsToAdvertise], this does not
     * rotate the carousel offset, so a UI can call it freely without disturbing what actually
     * goes out over the radio. Exists for the responder view, which needs to list carried SOS
     * messages rather than just a count.
     */
    fun carriedMessages(nowMillis: Long = host.nowMillis()): List<SosBeacon> =
        outbox.carouselOrder(nowMillis).map { it.beacon }

    // ---------------------------------------------------------------- run loop

    /** Drives [link] from [planNow]. Cancel the surrounding scope to stop. */
    suspend fun run() = coroutineScope {
        launch {
            link.events.collect { event ->
                when (event) {
                    is LinkEvent.BeaconHeard ->
                        onBeaconHeard(event.payload, event.peer, event.atMillis, event.rssiDbm)
                    is LinkEvent.BundleReceived -> {
                        val bundle = com.setu.mesh.core.codec.BundleCodec.decode(event.payload, keyStore)
                        if (bundle != null && bundle.verification != com.setu.mesh.core.model.BundleVerification.SignatureInvalid) {
                            val encodedBeacon = com.setu.mesh.core.codec.BeaconCodec.encode(bundle.beacon)
                            onBeaconHeard(encodedBeacon, event.peer, event.atMillis)
                        }
                    }
                    is LinkEvent.ScanWindow -> Unit
                    is LinkEvent.RadioUnavailable -> Unit
                }
            }
        }

        // Wall clock at the previous iteration, so advertising can be billed against time that
        // actually elapsed. See the billAdvertising call below.
        var lastBilledMillis = host.nowMillis()

        while (coroutineContext.isActive) {
            val now = host.nowMillis()
            val plan = planNow(now)

            outbox.purgeStale(now)
            seen.purgeExpired(now)

            // Only on change: retuning the radio is not free, and the tier is stable for long
            // stretches. Without this guard it would fire on every loop iteration.
            val profile = radioProfileFor(plan.tier)
            if (profile != lastRadioProfile) {
                link.applyRadioProfile(profile)
                lastRadioProfile = profile
            }

            val beacons = beaconsToAdvertise(link.capabilities.advertisingSlots, now)
            if (link.capabilities.canAdvertise) {
                link.setAdvertisedBeacons(beacons)
                // Bill the time that actually elapsed since the last iteration, not one beacon
                // interval. The loop period is only the beacon interval when nothing blocks --
                // and an iteration that scans blocks for the whole scan window, which in
                // attentive mode is every iteration and 12s of it. Billing a flat interval there
                // under-reports advertising energy by ~24x. The ledger is the source of the
                // headline mAh figure in the UI and in docs/POWER.md, so it has to measure
                // elapsed time rather than assume the loop kept to its nominal period.
                governor.ledger.billAdvertising(now - lastBilledMillis, plan.beaconIntervalMillis)
            }
            // Advanced unconditionally: a node that cannot advertise must not accumulate a
            // silent debt that lands as one enormous bill the moment the radio comes back.
            lastBilledMillis = now

            if (plan.scanThisEpoch && plan.inRendezvousWindow && plan.scanWindowMillis > 0) {
                _snapshot.value = _snapshot.value.copy(scanning = true)
                link.scanFor(plan.scanWindowMillis)
                governor.ledger.billScan(plan.scanWindowMillis)
                _snapshot.value = _snapshot.value.copy(scanning = false)
            }

            refreshSnapshot(now)
            delay(nextSleepMillis(plan, now))
        }
    }

    /**
     * How long to sleep before the next loop iteration.
     *
     * Sleeping a flat [RadioPlan.beaconIntervalMillis] is *not* safe here, and this is subtle
     * enough to be worth spelling out. The rendezvous window is 1s wide inside a 60s epoch, and
     * the loop wakes on a fixed grid whose phase is set by whenever the node happened to start.
     * If that grid never lands inside the window, the node never scans -- not rarely, *never*.
     * Concretely, a FLARE node (2s interval) that starts 1.2s into an epoch has wake times at
     * 1.2s, 3.2s, 5.2s ... and every one of them misses [0s, 1s). It stays permanently deaf
     * while reporting itself perfectly healthy.
     *
     * The simulator cannot catch this: `World.tick()` steps a fixed 250ms, which always samples
     * the window. It is a hardware-only failure, which is exactly the kind worth designing out
     * rather than discovering on stage.
     *
     * So when this node intends to scan this epoch but is not currently inside the window, it
     * sleeps no longer than the time remaining until the window opens. Repeated application
     * converges on landing exactly at the window boundary, whatever the starting phase.
     */
    /**
     * Translates the protocol's power tier into transport-level radio intent. EMBER maps to
     * LOW_POWER_LONG_RANGE rather than plain LOW_POWER on purpose: a node at that tier has
     * stopped listening entirely and broadcasts rarely, so each broadcast should reach as far
     * as it can. Spending transmit power there buys reach with energy the node is no longer
     * spending on scanning. See `docs/POWER.md` §1.
     */
    private fun radioProfileFor(tier: PowerTier): RadioProfile = when (tier) {
        PowerTier.BRIDGE -> RadioProfile.HIGH_PERFORMANCE
        PowerTier.RELAY -> RadioProfile.BALANCED
        PowerTier.GOSSIP -> RadioProfile.BALANCED
        PowerTier.FLARE -> RadioProfile.LOW_POWER
        PowerTier.EMBER -> RadioProfile.LOW_POWER_LONG_RANGE
    }

    private fun nextSleepMillis(plan: RadioPlan, nowMillis: Long): Long {
        val base = plan.beaconIntervalMillis
        // Consult the plan for whether attentive was actually honoured, rather than asking
        // isAttentive() again here -- a request can be refused by the battery guard in
        // PowerGovernor.plan(), and if that happened this plan is an ordinary tier plan that
        // must still park for its rendezvous window like any other. scanWindowMillis is the
        // tell: PowerGovernor only ever sets it to ATTENTIVE_SCAN_WINDOW_MILLIS when the
        // request was honoured, no PowerTier uses that value.
        //
        // Attentive short-circuits the window-parking logic below entirely. That logic exists
        // to land the loop's wake grid inside next epoch's 1s window; attentive mode has no
        // window to wait for (PowerGovernor forces inRendezvousWindow = true), so parking for
        // governor.millisUntilNextWindow here would sleep tens of seconds while the plan says
        // "scan now" -- silently undoing the whole feature while looking correct in review.
        val attentiveHonoured = plan.scanWindowMillis == PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS
        val sleep = if (attentiveHonoured) {
            base
        } else if (plan.tier.scans && !plan.inRendezvousWindow) {
            // Gated on tier.scans, NOT on plan.scanThisEpoch. scanThisEpoch is only true during
            // the epochs this tier actually participates in; a tier that scans every 4th epoch
            // would otherwise drift freely through the three idle epochs and arrive at the
            // participating one at an arbitrary phase -- reintroducing exactly the bug this
            // exists to prevent. millisUntilNextWindow already skips ahead to the next
            // *participating* epoch.
            minOf(base, governor.millisUntilNextWindow(nowMillis, plan.tier))
        } else {
            base
        }
        return sleep.coerceAtLeast(MIN_LOOP_DELAY_MILLIS)
    }

    // ---------------------------------------------------------------- internals

    /**
     * Seen-set key folds the message type in, so a RECEIPT that references message M is deduped
     * independently of M itself while still pointing at it.
     */
    private fun dedupKey(beacon: SosBeacon): MessageId =
        MessageId(beacon.messageId.raw xor (beacon.type.wire * TYPE_MIX))

    /**
     * Record how strong a beacon arrived, but **only from its originator**.
     *
     * This is the whole subtlety of using RSSI here. Signal strength describes the hop it was
     * measured on, not the distance to the person who needs help: a beacon that reached us
     * through two relays arrives at whatever strength the *last relay* transmitted at, and
     * treating that as proximity to the victim would point a rescuer confidently at the wrong
     * person. `hops == 0` is the only case where the sender and the subject are the same device.
     *
     * Smoothed rather than taken raw because RSSI on a phone swings several dB frame to frame
     * from multipath and from a hand moving over the antenna.
     */
    private fun noteDirectSignal(beacon: SosBeacon, rssiDbm: Int?, nowMillis: Long) {
        if (rssiDbm == null || beacon.hops != 0) return
        val existing = directSignals[beacon.origin.raw]
        val smoothed = if (existing == null || nowMillis - existing.lastHeardMillis > SIGNAL_TTL_MILLIS) {
            rssiDbm.toDouble()
        } else {
            existing.smoothedRssiDbm + RSSI_EMA_ALPHA * (rssiDbm - existing.smoothedRssiDbm)
        }
        directSignals[beacon.origin.raw] = DirectSignal(smoothed, nowMillis)
    }

    /**
     * Smoothed signal strength for [originRaw], or null when that origin has not been heard
     * first-hand recently. Null is the honest answer for a relayed report and callers must
     * render it as "unknown", never as "far".
     */
    fun directSignalDbm(originRaw: Int, nowMillis: Long = host.nowMillis()): Int? =
        directSignals[originRaw]
            ?.takeIf { nowMillis - it.lastHeardMillis <= SIGNAL_TTL_MILLIS }
            ?.smoothedRssiDbm
            ?.roundToInt()

    private fun expireDirectSignals(nowMillis: Long) {
        directSignals.entries.removeAll { nowMillis - it.value.lastHeardMillis > SIGNAL_TTL_MILLIS }
    }

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
            attentive = isAttentive(nowMillis),
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
            attentive = isAttentive(nowMillis),
        )
    }

    private fun toEpochMinute(nowMillis: Long): Int =
        ((nowMillis - SETU_EPOCH_MILLIS) / MILLIS_PER_MINUTE).toInt()

    private companion object {
        const val NEIGHBOUR_TTL_MILLIS = 5 * 60 * 1000L
        const val MIN_LOOP_DELAY_MILLIS = 200L
        const val TYPE_MIX = 0x9E3779B1.toInt()

        /**
         * How long an SOS -- our own, or a neighbour's -- holds the node attentive. Two minutes
         * covers a plausible RECEIPT round trip and gives relaying a wide window to catch the
         * next hop promptly instead of sitting on a live message for up to a minute.
         */
        const val ATTENTIVE_AFTER_SOS_MILLIS = 120_000L

        /**
         * How long a direct signal reading stays meaningful. Short: people and phones move, and
         * a two-minute-old RSSI is not proximity, it is history.
         */
        const val SIGNAL_TTL_MILLIS = 60_000L

        /** RSSI swings several dB frame to frame; this damps it without hiding real movement. */
        const val RSSI_EMA_ALPHA = 0.3

    }
}

/** One origin's smoothed direct-signal reading. See [MeshNode.directSignalDbm]. */
private data class DirectSignal(val smoothedRssiDbm: Double, val lastHeardMillis: Long)
