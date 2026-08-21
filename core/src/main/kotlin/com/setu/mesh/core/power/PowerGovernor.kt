package com.setu.mesh.core.power

import com.setu.mesh.core.model.NodeId

/**
 * What the radio should be doing right now.
 *
 * The engine treats this as an instruction, not a suggestion: it is the single place where
 * "how much battery do I have" turns into "how loud am I, and am I listening".
 */
data class RadioPlan(
    val tier: PowerTier,
    val beaconIntervalMillis: Long,
    val scanThisEpoch: Boolean,
    val scanWindowMillis: Long,
    val mayOpenConnections: Boolean,
    /** True while the node is spending its final reserve to hand off everything it holds. */
    val lastGasp: Boolean,
) {
    val advertising: Boolean get() = beaconIntervalMillis > 0
}

/**
 * Composes the tier ladder, the rendezvous schedule and the scanner election into one decision.
 *
 * This is the direct answer to the challenge question — *how does the relay chain keep working
 * when every device is nearly dead?* — expressed as code rather than prose:
 *
 *  1. Falling battery drops the node down [PowerTier], which cuts scanning long before it cuts
 *     advertising, because being *findable* matters more than being *sociable*.
 *  2. [RendezvousScheduler] keeps the surviving low duty cycles phase-aligned, so a node awake
 *     one second per minute still reliably meets its neighbours.
 *  3. [ScannerElection] pushes the expensive listening onto whoever can currently afford it,
 *     using information the beacons already carry.
 *  4. Below [LAST_GASP_BATTERY_PERCENT] the node stops conserving and spends what is left
 *     making sure somebody else is holding its messages.
 */
class PowerGovernor(
    private val scheduler: RendezvousScheduler = RendezvousScheduler(),
    val ledger: EnergyLedger = EnergyLedger(),
) {

    fun plan(
        selfId: NodeId,
        batteryPercent: Int,
        charging: Boolean,
        neighbours: List<NeighbourEnergy>,
        nowMillis: Long,
    ): RadioPlan {
        val epoch = scheduler.epochIndex(nowMillis)

        if (!charging && batteryPercent <= LAST_GASP_BATTERY_PERCENT) {
            // Conservation is pointless now; the phone is going to die either way. Spend the
            // remainder shouting so a healthier neighbour picks up custody of everything we
            // are carrying. Your phone dying is not your SOS dying.
            return RadioPlan(
                tier = PowerTier.EMBER,
                beaconIntervalMillis = LAST_GASP_BEACON_INTERVAL_MILLIS,
                scanThisEpoch = false,
                scanWindowMillis = 0L,
                mayOpenConnections = false,
                lastGasp = true,
            )
        }

        val tier = PowerTier.forBattery(batteryPercent, charging)

        val scans = tier.scans &&
            scheduler.scansInEpoch(epoch, tier) &&
            ScannerElection.shouldScan(selfId, batteryPercent, charging, neighbours, epoch)

        return RadioPlan(
            tier = tier,
            beaconIntervalMillis = tier.beaconIntervalMillis,
            scanThisEpoch = scans,
            scanWindowMillis = if (scans) tier.scanWindowMillis else 0L,
            mayOpenConnections = tier.mayOpenConnections,
            lastGasp = false,
        )
    }

    /** Exposed so the engine can sleep until the next window rather than busy-polling. */
    fun millisUntilNextWindow(nowMillis: Long, tier: PowerTier): Long =
        scheduler.millisUntilNextWindow(nowMillis, tier)

    fun noteBeaconTimestamp(peerEpochMinute: Int, nowMillis: Long, trustedClock: Boolean) {
        scheduler.applyPeerObservation(peerEpochMinute, nowMillis, trustedClock)
    }

    companion object {
        /** Below this, switch from conserving energy to offloading messages. */
        const val LAST_GASP_BATTERY_PERCENT = 3

        /** Deliberately aggressive: this burst is meant to be heard, not to last. */
        const val LAST_GASP_BEACON_INTERVAL_MILLIS = 250L
    }
}
