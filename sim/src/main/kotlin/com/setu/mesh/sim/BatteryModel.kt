package com.setu.mesh.sim

/**
 * Simulated battery that drains from a starting percentage.
 *
 * Each tick the world reads the node's [com.setu.mesh.core.power.EnergyLedger] total,
 * computes the delta since the last read, and drains that amount. A baseline idle draw
 * is also applied so nodes eventually die even when the radio is silent.
 */
class BatteryModel(
    startPercent: Int,
    private val capacityMilliampHours: Double = DEFAULT_CAPACITY_MAH,
    private val idleDrawMilliamps: Double = DEFAULT_IDLE_DRAW_MA,
) {
    private var remainingMilliampHours: Double = capacityMilliampHours * (startPercent / 100.0)
    private var lastLedgerTotal: Double = 0.0

    val percent: Int get() = ((remainingMilliampHours / capacityMilliampHours) * 100).toInt().coerceIn(0, 100)

    val isDead: Boolean get() = remainingMilliampHours <= 0.0

    /**
     * Drain both the radio cost (from the ledger delta) and the idle draw for this tick.
     *
     * @param currentLedgerTotal the node's `EnergyLedger.totalMilliampHours` at this tick
     * @param tickMillis how many virtual milliseconds this tick represents
     */
    fun drain(currentLedgerTotal: Double, tickMillis: Long) {
        // Radio cost: whatever the MeshNode billed since last tick
        val radioDelta = (currentLedgerTotal - lastLedgerTotal).coerceAtLeast(0.0)
        lastLedgerTotal = currentLedgerTotal

        // Idle cost: baseline CPU/display/modem draw
        val idleCost = idleDrawMilliamps * (tickMillis / MILLIS_PER_HOUR)

        remainingMilliampHours = (remainingMilliampHours - radioDelta - idleCost).coerceAtLeast(0.0)
    }

    companion object {
        const val DEFAULT_CAPACITY_MAH = 4000.0
        /** ~10 mA idle draw ≈ roughly 1% per 4 hours on a 4000 mAh battery. */
        const val DEFAULT_IDLE_DRAW_MA = 10.0
        private const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
