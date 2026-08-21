package com.setu.mesh.core.routing

import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SosBeacon
import kotlin.random.Random

/** Why a node did or did not re-advertise. Surfaced in the UI so the behaviour is inspectable. */
sealed interface RelayDecision {
    data class Relay(val probability: Double) : RelayDecision
    data class Suppress(val reason: SuppressReason, val probability: Double = 0.0) : RelayDecision
}

enum class SuppressReason {
    /** Hop budget spent. */
    TTL_EXHAUSTED,

    /** Below the floor where a node relays anything but its own SOS. */
    ENERGY_GATE,

    /** The originator has more battery than we do, and is not critical — let them shout. */
    ALTRUISM_GRADIENT,

    /** Enough neighbours are already carrying this copy. */
    DENSITY_DAMPED,

    /** Passed every gate, lost the probabilistic roll. */
    PROBABILISTIC,
}

/** Everything the policy needs that is not in the beacon itself. */
data class ForwardingContext(
    val selfBatteryPercent: Int,
    val selfCharging: Boolean,
    /** Distinct neighbours already heard re-advertising this same message id. */
    val neighboursHoldingCopy: Int,
    /** True when this node originated the message. */
    val isOwnMessage: Boolean,
)

/**
 * Energy-aware epidemic forwarding.
 *
 * Classic epidemic routing relays everything to everyone and delivers beautifully right up to
 * the moment it flattens every battery in the region. The DTN literature's fix is to make the
 * forwarding decision a function of residual energy; SETU applies that with three multiplicative
 * gates and one hard rule.
 *
 * The hard rule: **a node always relays its own SOS.** No gate applies. A phone at 1% still
 * shouts for its owner — it just stops volunteering to carry for others.
 *
 * The three gates:
 *  - [energyGate] — a step function of our own battery. Below 5% we carry nothing but our own.
 *  - the altruism gradient — if the originator has *more* battery than we do and is not
 *    critical, we damp hard. They can afford to keep broadcasting; we may not be able to. This
 *    inverts the usual "route to the strongest peer" heuristic into something ethically right
 *    as well as energy-optimal: effort flows toward whoever is worse off.
 *  - [densityDamp] — if several neighbours are already re-advertising this message, extra
 *    copies buy delivery probability that is already bought. Redundancy has diminishing returns
 *    and constant cost.
 *
 * CRITICAL severity bypasses the gradient and softens the gate, because a wrong suppression
 * there costs a life while a wrong relay costs milliamps.
 */
object ForwardingPolicy {

    fun decide(
        beacon: SosBeacon,
        context: ForwardingContext,
        random: Random = Random.Default,
    ): RelayDecision {
        if (beacon.ttl <= 0) return RelayDecision.Suppress(SuppressReason.TTL_EXHAUSTED)

        // A node's own message is never gated. This is the floor of the whole design.
        if (context.isOwnMessage) return RelayDecision.Relay(1.0)

        val critical = beacon.severity() == Severity.CRITICAL
        val gate = energyGate(context.selfBatteryPercent, context.selfCharging, critical)
        if (gate <= 0.0) return RelayDecision.Suppress(SuppressReason.ENERGY_GATE)

        val gradient = altruismGradient(
            selfBattery = context.selfBatteryPercent,
            originBattery = beacon.originBattery,
            critical = critical,
        )
        if (gradient <= 0.0) return RelayDecision.Suppress(SuppressReason.ALTRUISM_GRADIENT)

        val density = densityDamp(context.neighboursHoldingCopy)
        if (density <= 0.0) return RelayDecision.Suppress(SuppressReason.DENSITY_DAMPED)

        val probability = (severityWeight(beacon) * gate * gradient * density).coerceIn(0.0, 1.0)
        return if (random.nextDouble() < probability) {
            RelayDecision.Relay(probability)
        } else {
            RelayDecision.Suppress(SuppressReason.PROBABILISTIC, probability)
        }
    }

    /** Step function on our own remaining battery. */
    fun energyGate(batteryPercent: Int, charging: Boolean, critical: Boolean): Double {
        if (charging) return 1.0
        return when {
            batteryPercent >= 40 -> 1.0
            batteryPercent >= 15 -> 0.6
            batteryPercent >= 5 -> 0.25
            // Below 5% we would normally carry nothing but our own message. A CRITICAL beacon
            // is the one exception: somebody is drowning, and we still have a radio.
            critical -> 0.15
            else -> 0.0
        }
    }

    /** Effort flows toward whoever is worse off. */
    fun altruismGradient(selfBattery: Int, originBattery: Int, critical: Boolean): Double {
        if (critical) return 1.0
        if (originBattery > BATTERY_SANE_MAX) return 1.0 // originator battery unknown
        return when {
            selfBattery >= originBattery -> 1.0
            selfBattery >= originBattery - GRADIENT_TOLERANCE -> 0.6
            else -> 0.25
        }
    }

    /** Diminishing returns on redundant copies. */
    fun densityDamp(neighboursHoldingCopy: Int): Double =
        if (neighboursHoldingCopy <= 0) 1.0
        else minOf(1.0, DESIRED_REDUNDANCY.toDouble() / neighboursHoldingCopy)

    private fun severityWeight(beacon: SosBeacon): Double = when (beacon.severity()) {
        Severity.CRITICAL -> 1.0
        Severity.HIGH -> 0.85
        Severity.MODERATE -> 0.6
        Severity.LOW -> 0.35
    }

    private fun SosBeacon.severity(): Severity = flags.severity

    /** How many independent carriers we consider "enough" for one message. */
    const val DESIRED_REDUNDANCY = 3

    /** Battery difference treated as noise rather than a real gradient. */
    private const val GRADIENT_TOLERANCE = 15

    /** Above this, the originator battery byte is a sentinel rather than a reading. */
    private const val BATTERY_SANE_MAX = 100
}
