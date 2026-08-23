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
import com.setu.mesh.core.routing.CancelledSet
import com.setu.mesh.core.routing.Outbox
import com.setu.mesh.core.routing.ReconsiderTracker
import com.setu.mesh.core.routing.RelayDecision
import com.setu.mesh.core.routing.SeenSet
import com.setu.mesh.core.routing.SuppressReason
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
 * One decision this node made — or a non-decision it needs told apart from one — about a beacon
 * it heard. Kept in [MeshNode.recentForwardingDecisions] so a field tester can answer "did this
 * node even decide to forward it, and why not" without a debugger attached, which was RC4's whole
 * problem: [onBeaconHeard]'s [RelayDecision] used to be computed and immediately discarded.
 *
 * [attempt] is the [ForwardingPolicy.decide] attempt number this record reflects: 1 for the first
 * time the policy ran for this dedup key, 2 or 3 for a reconsideration of a lost
 * [SuppressReason.PROBABILISTIC] roll (see [onBeaconHeard]), or 0 when the policy never ran at
 * all — a [SuppressReason.MALFORMED] frame, or a [SuppressReason.DUPLICATE] hearing this node
 * already has a terminal answer for.
 */
data class ForwardingRecord(
    val atMillis: Long,
    val messageId: MessageId,
    val origin: NodeId,
    val type: MessageType,
    val hops: Int,
    val rssiDbm: Int?,
    val decision: RelayDecision,
    val attempt: Int,
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
    private val cancelled = CancelledSet()
    private val neighbours = LinkedHashMap<Int, NeighbourEnergy>()

    /**
     * Dedup key → reconsideration attempts spent so far on a lost [SuppressReason.PROBABILISTIC]
     * roll. See [onBeaconHeard] and `docs/PROTOCOL.md` §6.
     */
    private val reconsider = ReconsiderTracker()

    /**
     * Guards [forwardingHistory]: [onBeaconHeard] writes from the link-event collector coroutine
     * ([run]'s `launch`), while [recentForwardingDecisions] is read from the UI thread (a
     * `DiagnosticsScreen` poll loop, same pattern as [snapshot]). A plain `ArrayDeque` is not
     * safe for that without one.
     */
    private val forwardingHistoryLock = Any()
    private val forwardingHistory = ArrayDeque<ForwardingRecord>()

    /** Smoothed signal strength for origins heard **directly**, keyed by origin id. */
    private val directSignals = LinkedHashMap<Int, DirectSignal>()

    private var sequence = 0
    private var ownMessageId: MessageId? = null
    /**
     * Tracks the outstanding own-SAFE's outbox key so that a subsequent [originateSos] can remove
     * it. [markSafe] nulls [ownMessageId] (correctly — the SOS is cancelled), but the SAFE itself
     * occupies the outbox under the *original* SOS's messageId. Without this, nothing ever removes
     * that isOwn=true entry: [Outbox.purgeStale] and [Outbox.evictOne] both exempt own entries.
     */
    private var ownSafeMessageId: MessageId? = null
    private var carouselOffset = 0
    private var lastRadioProfile: RadioProfile? = null

    // Attentive mode: see setAttentive() and isAttentive() below. Two independent triggers
    // (screen foreground, recent SOS activity) fold into one gate rather than each caller
    // threading its own "am I attentive" condition through planNow/refreshSnapshot.
    @Volatile
    private var foregroundAttentive: Boolean = false
    @Volatile
    private var attentiveUntilMillis: Long = 0L

    /**
     * Debug-only field-test aid: when set, [onBeaconHeard] forces every forwarding decision to
     * [RelayDecision.Relay] instead of leaving it to the probabilistic roll, so a field test is
     * never at the mercy of a dice roll. Dedup and TTL still apply in full — this removes only
     * the probability gate, not the protocol's actual safety valves.
     *
     * `:core` cannot itself check `ApplicationInfo.FLAG_DEBUGGABLE` without importing Android,
     * which this module must never do — so unlike a release build being unable to reach this at
     * all, the guard against enabling it lives at the call site instead. See
     * `SetuService.setAlwaysRelayOverride`.
     */
    @Volatile
    private var alwaysRelayOverride: Boolean = false

    private val _snapshot = MutableStateFlow(NodeSnapshot(id = id))
    val snapshot: StateFlow<NodeSnapshot> = _snapshot.asStateFlow()

    val ledger get() = governor.ledger

    /**
     * Set only on a node acting as a gateway (uplink to the outside world). Not a constructor
     * param: [GatewayRole] holds a reference back to this node to originate the RECEIPT, so it
     * has to be built after the node exists. Null on every ordinary relay-only node.
     */
    var gatewayRole: GatewayRole? = null

    /**
     * Fires once per distinct SOS this node accepts delivery for as a gateway -- i.e. only when
     * [gatewayRole] is set, has an uplink, and has not already accepted this message. Carries the
     * full beacon (position, severity, souls) so the transport layer can hand it to whatever the
     * real uplink is (SMS, an API call, a dispatcher radio) without `core` knowing any of that
     * exists.
     */
    var onGatewayAlert: ((SosBeacon) -> Unit)? = null

    /**
     * Fires once per distinct SOS this node newly observes and is not the origin of -- i.e. the
     * moment a relay first picks up a stranger's emergency, not when our own SOS echoes back.
     * Gated on the same dedup check as [onGatewayAlert], so a re-heard/re-relayed copy of a
     * message already seen never fires this twice.
     *
     * This is a UX hook, not a protocol concern: `core` has no idea it exists to trigger a
     * buzz/sound/notification, only that "a new emergency just became known to this device" is
     * a moment worth telling the transport layer about.
     */
    var onSosObserved: ((SosBeacon) -> Unit)? = null

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
            positionAccuracyClass = host.positionAccuracyClass(),
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
        ownMessageId?.let { outbox.remove(it, MessageType.SOS) }
        // Also remove any stale own-SAFE left by a prior markSafe → originateSos cycle.
        // markSafe nulls ownMessageId, so the line above cannot reach the SAFE entry.
        ownSafeMessageId?.let { outbox.remove(it, MessageType.SAFE); ownSafeMessageId = null }

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
            // SAFE carries this node's own current position/accuracy, not the outstanding SOS's --
            // whoever hears "I am safe" should learn where that confirmation actually came from.
            position = host.position() ?: GeoPoint.UNKNOWN,
            epochMinute = epochMinute,
            flags = SituationFlags(),
            souls = 0,
            originBattery = host.batteryPercent(),
            positionAccuracyClass = host.positionAccuracyClass(),
        )
        outbox.remove(original, MessageType.SOS)
        ownMessageId = null
        ownSafeMessageId = original
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
            // A RECEIPT's position describes the gateway that accepted delivery, not the
            // victim's -- that gateway's own fix quality is what's relevant here, so it gets the
            // same treatment as the other two origination points.
            position = host.position() ?: GeoPoint.UNKNOWN,
            epochMinute = epochMinute,
            flags = SituationFlags(),
            souls = 0,
            originBattery = host.batteryPercent(),
            positionAccuracyClass = host.positionAccuracyClass(),
        )
        seen.addIfNew(dedupKey(receipt), nowMillis)
        outbox.remove(forMessage, MessageType.SOS)
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
        if (beacon == null) {
            // Not a policy outcome at all: bad CRC or an unknown version, rejected before routing
            // ever sees it. Distinct from PROBABILISTIC so a field tester can tell "the radio
            // handed us garbage" from "the dice roll came up wrong" -- both used to be invisible
            // in exactly the same way. There is no decoded beacon to describe here, so identity
            // fields are sentinels; rssiDbm is still real, since the radio reported it before
            // decoding was attempted.
            val decision = RelayDecision.Suppress(SuppressReason.MALFORMED)
            recordForwarding(
                ForwardingRecord(
                    atMillis = nowMillis,
                    messageId = MessageId(0),
                    origin = NodeId.UNKNOWN,
                    type = MessageType.RESERVED,
                    hops = 0,
                    rssiDbm = rssiDbm,
                    decision = decision,
                    attempt = 0,
                ),
            )
            return decision
        }

        noteDirectSignal(beacon, rssiDbm, nowMillis)

        // Every beacon doubles as a neighbour-energy advertisement and a coarse clock sample.
        neighbours[beacon.origin.raw] = NeighbourEnergy(beacon.origin, beacon.originBattery, nowMillis)
        governor.noteBeaconTimestamp(beacon.epochMinute, nowMillis, host.hasTrustedClock())
        outbox.noteCarrier(beacon.messageId, beacon.type, beacon.origin.raw)

        when (beacon.type) {
            MessageType.RECEIPT, MessageType.SAFE -> {
                // Delivery confirmed or cancelled: stop carrying the original SOS -- named
                // explicitly as MessageType.SOS, not beacon.type, because this beacon's id
                // *refers to* that SOS rather than identifying itself. Removing by beacon.type
                // here would delete the RECEIPT/SAFE's own outbox entry the moment its echo came
                // back from a neighbour, which is the bug this type-keyed outbox exists to fix.
                outbox.remove(beacon.messageId, MessageType.SOS)
                // Remember that this messageId's SOS is cancelled, so reconsideration and
                // post-SeenSet re-hearings cannot resurrect it.
                cancelled.record(beacon.messageId, nowMillis)
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

        // RC4's second defect: SeenSet.addIfNew alone used to be the entire forwarding gate, and
        // it ran *before* the policy -- so a message that lost its probabilistic roll was marked
        // seen and never looked at again for the rest of the 10-minute window, no matter how many
        // more times it was heard. The side effects above (clock sample, carrier count, RSSI)
        // still run exactly once per hearing regardless of any of this -- they are unconditional,
        // same as before the restructure -- but the *forwarding* decision now distinguishes three
        // cases: a first hearing, a hearing worth reconsidering, and a hearing with nothing left
        // to decide.
        val key = dedupKey(beacon)
        val firstHearing = seen.addIfNew(key, nowMillis)
        val relayed = beacon.relayed()

        val decision: RelayDecision
        var attempt = 0

        // A cancelled SOS must never be re-adopted, whether via reconsideration (the lost
        // PROBABILISTIC roll path) or as a "fresh" first hearing after SeenSet forgets M.
        // CancelledSet's 6-hour expiry outlives SeenSet's 10 minutes by design — see its
        // class doc. Only SOS beacons can be cancelled; SAFE/RECEIPT relay normally.
        if (beacon.type == MessageType.SOS && cancelled.contains(beacon.messageId, nowMillis)) {
            decision = RelayDecision.Suppress(SuppressReason.CANCELLED)
            reconsider.clear(key.raw)
        } else if (relayed == null) {
            // Hop budget spent on *this* hearing's path. Terminal, same as ENERGY_GATE below: a
            // shorter path might still bring a relayable copy later, but that would arrive as a
            // fresh hearing with its own ttl, not as a reconsideration of this one.
            decision = RelayDecision.Suppress(SuppressReason.TTL_EXHAUSTED)
            if (!firstHearing) reconsider.clear(key.raw)
        } else if (firstHearing) {
            attempt = 1
            decision = runForwardingPolicy(relayed, beacon, nowMillis)
            if (isReconsiderable(decision)) reconsider.recordAttempt(key.raw, attempt, nowMillis)
        } else {
            val attemptsSoFar = reconsider.attempts(key.raw, nowMillis)
            if (attemptsSoFar != null && attemptsSoFar < MAX_RECONSIDER_ATTEMPTS) {
                // The reconsideration itself: same policy, current context. neighboursHoldingCopy
                // has had a chance to rise since the last attempt -- other nodes may have relayed
                // in the meantime, which outbox.noteCarrier above just recorded for free -- and
                // densityDamp shrinks the relay probability precisely as that redundancy arrives.
                // That is what makes retrying a lost roll safe: it cannot multiply traffic the
                // way blindly re-relaying would, because the very thing that makes a retry more
                // likely to succeed (more carriers already seen) is also what damps its
                // probability back down. See docs/PROTOCOL.md §6.
                attempt = attemptsSoFar + 1
                decision = runForwardingPolicy(relayed, beacon, nowMillis)
                when {
                    decision is RelayDecision.Relay -> reconsider.clear(key.raw)
                    isReconsiderable(decision) && attempt < MAX_RECONSIDER_ATTEMPTS ->
                        reconsider.recordAttempt(key.raw, attempt, nowMillis)
                    // ENERGY_GATE (terminal) or the reconsideration budget just ran out.
                    else -> reconsider.clear(key.raw)
                }
            } else {
                // Nothing left to decide: already relayed, already given a terminal
                // TTL_EXHAUSTED/ENERGY_GATE verdict, or its reconsideration budget is spent. A
                // field tester still needs to see that this beacon arrived again -- just not as
                // a fresh policy run.
                decision = RelayDecision.Suppress(SuppressReason.DUPLICATE)
            }
        }

        // Gated on firstHearing, not just beacon.type: without that, every re-hear of an
        // already-seen SOS (this node relays it, a neighbour relays it back) would fire another
        // alert. Placing this on firstHearing means it fires exactly once per distinct SOS this
        // node ever learns of, which is what "send one SMS per emergency" / "buzz once" require.
        // Also excludes an SOS already on the cancelled set -- a stale echo of an already-resolved
        // emergency must not re-trigger "someone nearby needs help right now".
        if (beacon.type == MessageType.SOS && firstHearing && !cancelled.contains(beacon.messageId, nowMillis)) {
            gatewayRole?.let { role ->
                if (role.acceptDelivery(beacon.messageId, nowMillis) != null) {
                    onGatewayAlert?.invoke(beacon)
                }
            }
            // Not our own SOS echoing back -- that case is already surfaced via ownSosMaxHops
            // above and would be a false "new emergency" alert on every hop it travels.
            if (beacon.origin != id) {
                onSosObserved?.invoke(beacon)
            }
        }

        recordForwarding(
            ForwardingRecord(
                atMillis = nowMillis,
                messageId = beacon.messageId,
                origin = beacon.origin,
                type = beacon.type,
                hops = beacon.hops,
                rssiDbm = rssiDbm,
                decision = decision,
                attempt = attempt,
            ),
        )

        refreshSnapshot(nowMillis)
        return decision
    }

    /** True only for the one suppression reason [onBeaconHeard] is willing to look at again. */
    private fun isReconsiderable(decision: RelayDecision): Boolean =
        decision is RelayDecision.Suppress && decision.reason == SuppressReason.PROBABILISTIC

    /**
     * Runs [ForwardingPolicy.decide] for [relayed] (already ttl-1/hops+1'd from [original]) and
     * applies a [RelayDecision.Relay]'s side effects. Shared by the first hearing and every
     * reconsideration so the two paths cannot drift apart -- both need the *current* context
     * (battery, neighbour count), never a snapshot from whenever this dedup key was first heard.
     */
    private fun runForwardingPolicy(relayed: SosBeacon, original: SosBeacon, nowMillis: Long): RelayDecision {
        val decision = if (alwaysRelayOverride) {
            // Debug-only field-test aid: skips the dice roll entirely. Dedup and TTL above still
            // apply in full -- this removes only the probability gate, not the protocol's actual
            // safety valves.
            RelayDecision.Relay(1.0)
        } else {
            ForwardingPolicy.decide(
                beacon = relayed,
                context = ForwardingContext(
                    selfBatteryPercent = host.batteryPercent(),
                    selfCharging = host.isCharging(),
                    neighboursHoldingCopy = outbox.get(original.messageId, original.type)?.neighboursHoldingCopy ?: 0,
                    isOwnMessage = original.origin == id,
                ),
                random = random,
                energyGateOverride = tuning.energyGateOverride,
            )
        }

        if (decision is RelayDecision.Relay) {
            outbox.put(relayed, nowMillis, isOwn = false)
            governor.ledger.recordRelay()
        }
        return decision
    }

    /** Records [record] into [forwardingHistory], evicting the oldest once past capacity. */
    private fun recordForwarding(record: ForwardingRecord) {
        synchronized(forwardingHistoryLock) {
            forwardingHistory.addFirst(record)
            while (forwardingHistory.size > FORWARDING_HISTORY_CAPACITY) forwardingHistory.removeLast()
        }
    }

    /**
     * The last [FORWARDING_HISTORY_CAPACITY] forwarding decisions this node has made, newest
     * first. This is RC4's fix: on real hardware there was previously no way to answer "did this
     * node even decide to forward that beacon, and why not" -- see [ForwardingRecord].
     */
    fun recentForwardingDecisions(): List<ForwardingRecord> = synchronized(forwardingHistoryLock) {
        forwardingHistory.toList()
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

    /** See [alwaysRelayOverride]. */
    fun setAlwaysRelayOverride(active: Boolean) {
        alwaysRelayOverride = active
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
        // Reclaim an own-SAFE that has outlived its useful broadcast window. Own entries are
        // exempt from Outbox.purgeStale / evictOne, so this is the only path that removes them.
        // OWN_SAFE_BROADCAST_MILLIS matches SeenSet's expiry: once every peer that heard the
        // SAFE has it in their seen-set, broadcasting it further is pure airtime waste.
        ownSafeMessageId?.let { safeId ->
            val entry = outbox.get(safeId, MessageType.SAFE)
            if (entry != null && nowMillis - entry.addedAtMillis >= OWN_SAFE_BROADCAST_MILLIS) {
                outbox.remove(safeId, MessageType.SAFE)
                ownSafeMessageId = null
                refreshSnapshot(nowMillis)
            }
        }

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
            reconsider.purgeExpired(now)
            cancelled.purgeExpired(now)

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
     * 1.2s, 3.2s, 5.2s ... and every one of them misses [0s, 1s).
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
        val own = ownMessageId?.let { outbox.get(it, MessageType.SOS)?.beacon }
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
         * How long an own-originated SAFE keeps broadcasting before being reclaimed. Matches
         * [SeenSet.DEFAULT_EXPIRY_MILLIS]: once peers' seen-sets have had time to absorb the
         * SAFE, continuing to advertise it wastes airtime and — worse — [Outbox.carouselOrder]
         * sorts isOwn first, so it outranks live SOS beacons the entire time.
         */
        const val OWN_SAFE_BROADCAST_MILLIS = SeenSet.DEFAULT_EXPIRY_MILLIS

        /**
         * How long a direct signal reading stays meaningful. Short: people and phones move, and
         * a two-minute-old RSSI is not proximity, it is history.
         */
        const val SIGNAL_TTL_MILLIS = 60_000L

        /** RSSI swings several dB frame to frame; this damps it without hiding real movement. */
        const val RSSI_EMA_ALPHA = 0.3

        /**
         * How many times a lost [SuppressReason.PROBABILISTIC] roll gets reconsidered before
         * [onBeaconHeard] leaves it alone. Bounded rather than unlimited: [densityDamp] pulls the
         * probability down as [neighboursHoldingCopy]-style redundancy rises, so each extra
         * attempt is already worth less than the last -- three tries is enough to catch the
         * common case (a couple of unlucky rolls in a thin neighbourhood) without letting a
         * message that *keeps* losing become a standing source of re-evaluation for the rest of
         * its 10-minute [SeenSet] window.
         */
        const val MAX_RECONSIDER_ATTEMPTS = 3

        /**
         * Ring buffer size for [recentForwardingDecisions]. 32 is enough to cover several
         * beacon-carousel rotations' worth of activity -- what a field tester actually reads this
         * for -- without holding meaningfully more than that in memory.
         */
        const val FORWARDING_HISTORY_CAPACITY = 32
    }
}

/** One origin's smoothed direct-signal reading. See [MeshNode.directSignalDbm]. */
private data class DirectSignal(val smoothedRssiDbm: Double, val lastHeardMillis: Long)
