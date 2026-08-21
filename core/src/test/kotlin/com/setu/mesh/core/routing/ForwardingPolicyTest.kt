package com.setu.mesh.core.routing

import com.setu.mesh.core.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ForwardingPolicyTest {

    private fun testBeacon(originBattery: Int, severity: Severity): SosBeacon = SosBeacon(
        type = MessageType.SOS,
        ttl = 7,
        hops = 0,
        messageId = MessageId(1),
        origin = NodeId(1),
        position = GeoPoint.of(0.0, 0.0),
        epochMinute = 0,
        flags = SituationFlags(severity = severity),
        souls = 1,
        originBattery = originBattery
    )

    private fun decide(
        selfBatt: Int,
        charging: Boolean,
        originBatt: Int,
        severity: Severity,
        k: Int,
        isOwn: Boolean = false
    ): RelayDecision {
        val beacon = testBeacon(originBatt, severity)
        val context = ForwardingContext(
            selfBatteryPercent = selfBatt,
            selfCharging = charging,
            neighboursHoldingCopy = k,
            isOwnMessage = isOwn
        )
        // using seed 42 as instructed, although for asserting probability it doesn't matter much.
        return ForwardingPolicy.decide(beacon, context, Random(42))
    }

    @Test
    fun `truth table - case 1`() {
        // 100 | false | 50 | LOW | 0 | Relay, p = 0.35
        val decision = decide(100, false, 50, Severity.LOW, 0)
        val prob = if (decision is RelayDecision.Relay) decision.probability else (decision as RelayDecision.Suppress).probability
        assertEquals(0.35, prob, 0.01)
    }

    @Test
    fun `truth table - case 2`() {
        // 50 | false | 50 | CRITICAL | 0 | Relay, p = 1.0
        val decision = decide(50, false, 50, Severity.CRITICAL, 0)
        assertTrue(decision is RelayDecision.Relay)
        assertEquals(1.0, (decision as RelayDecision.Relay).probability, 0.01)
    }

    @Test
    fun `truth table - case 3`() {
        // 4 | false | 50 | HIGH | 0 | Suppress(ENERGY_GATE)
        val decision = decide(4, false, 50, Severity.HIGH, 0)
        assertTrue(decision is RelayDecision.Suppress)
        assertEquals(SuppressReason.ENERGY_GATE, (decision as RelayDecision.Suppress).reason)
    }

    @Test
    fun `truth table - case 4`() {
        // 4 | false | 50 | CRITICAL | 0 | Relay, p ≈ 0.15
        val decision = decide(4, false, 50, Severity.CRITICAL, 0)
        val prob = if (decision is RelayDecision.Relay) decision.probability else (decision as RelayDecision.Suppress).probability
        assertEquals(0.15, prob, 0.01)
    }

    @Test
    fun `truth table - case 5`() {
        // 20 | false | 90 | MODERATE | 0 | heavily damped by the altruism gradient
        //
        // gradient is 0.25 (not <= 0), so this does not hit the ALTRUISM_GRADIENT hard-suppress
        // branch; it is damped through to the probability roll instead:
        //   p = severityWeight(MODERATE)=0.6 * energyGate(20%)=0.6 * gradient(20 vs 90)=0.25 * density(k=0)=1.0
        //     = 0.09
        val decision = decide(20, false, 90, Severity.MODERATE, 0)
        val prob = if (decision is RelayDecision.Relay) decision.probability else (decision as RelayDecision.Suppress).probability
        assertEquals(0.09, prob, 0.01)
    }

    @Test
    fun `truth table - case 6`() {
        // 90 | false | 20 | MODERATE | 0 | Relay, p = 0.6
        val decision = decide(90, false, 20, Severity.MODERATE, 0)
        val prob = if (decision is RelayDecision.Relay) decision.probability else (decision as RelayDecision.Suppress).probability
        assertEquals(0.6, prob, 0.01)
    }

    @Test
    fun `truth table - case 7`() {
        // 100 | false | 50 | HIGH | 12 | damped, p ≈ 0.85 × 3/12
        val decision = decide(100, false, 50, Severity.HIGH, 12)
        val prob = if (decision is RelayDecision.Relay) decision.probability else (decision as RelayDecision.Suppress).probability
        assertEquals(0.85 * (3.0 / 12.0), prob, 0.01)
    }

    @Test
    fun `truth table - case 8`() {
        // any | any | any | any | any | isOwnMessage = true → always Relay(1.0)
        val decision = decide(1, false, 100, Severity.LOW, 100, isOwn = true)
        assertTrue(decision is RelayDecision.Relay)
        assertEquals(1.0, (decision as RelayDecision.Relay).probability, 0.01)
    }
}
