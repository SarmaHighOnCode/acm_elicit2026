package com.setu.mesh.core.power

/**
 * Per-operation current draw. Defaults are **order-of-magnitude estimates**, not measurements.
 *
 * They are here so the simulator has a cost model and the UI has something to show on day one.
 * `docs/POWER.md` must replace them with numbers measured on the actual demo handsets before
 * any of them are quoted as fact. Shipping a claimed battery figure that nobody measured is the
 * fastest way to lose an argument with a judge who owns a multimeter.
 */
data class RadioCostModel(
    /** Average draw for a beacon broadcast once per second. Scales with rate. */
    val advertiseMilliampsAt1Hz: Double = 1.2,
    /** Average draw while a scan window is open. The dominant cost by a wide margin. */
    val scanMilliamps: Double = 18.0,
    /** Average draw while a GATT connection is up and moving a bundle. */
    val connectionMilliamps: Double = 12.0,
)

/**
 * Tracks what SETU has actually cost the user, in mAh.
 *
 * This is not instrumentation for its own sake. An app that quietly drains a phone during a
 * disaster gets uninstalled before the disaster, and a mesh with no nodes relays nothing. Being
 * able to say "1.8% of your battery in three hours, and you carried 47 messages for 12 people"
 * is what earns the install — so the ledger is a product feature, and the simulator's battery
 * model is driven by the same numbers.
 */
class EnergyLedger(
    private val cost: RadioCostModel = RadioCostModel(),
    /** Battery capacity used to convert mAh into a percentage of the phone. */
    private val batteryCapacityMilliampHours: Double = 4000.0,
) {
    var advertisingMilliampHours: Double = 0.0
        private set
    var scanningMilliampHours: Double = 0.0
        private set
    var connectionMilliampHours: Double = 0.0
        private set

    var beaconsSent: Long = 0L
        private set
    var beaconsRelayed: Long = 0L
        private set
    var bundlesCarried: Long = 0L
        private set

    val totalMilliampHours: Double
        get() = advertisingMilliampHours + scanningMilliampHours + connectionMilliampHours

    /** Share of a full charge consumed so far, 0..1. */
    val batteryFractionUsed: Double
        get() = totalMilliampHours / batteryCapacityMilliampHours

    fun billAdvertising(durationMillis: Long, beaconIntervalMillis: Long) {
        if (durationMillis <= 0 || beaconIntervalMillis <= 0) return
        val rateHz = 1000.0 / beaconIntervalMillis
        advertisingMilliampHours += toMilliampHours(cost.advertiseMilliampsAt1Hz * rateHz, durationMillis)
        beaconsSent += (durationMillis / beaconIntervalMillis)
    }

    fun billScan(durationMillis: Long) {
        if (durationMillis <= 0) return
        scanningMilliampHours += toMilliampHours(cost.scanMilliamps, durationMillis)
    }

    fun billConnection(durationMillis: Long) {
        if (durationMillis <= 0) return
        connectionMilliampHours += toMilliampHours(cost.connectionMilliamps, durationMillis)
    }

    fun recordRelay() {
        beaconsRelayed++
    }

    fun recordBundleCarried() {
        bundlesCarried++
    }

    /**
     * Hours the node can keep running SETU at its current draw, given remaining battery.
     * This is what the SOS screen shows as "you stay reachable for N hours".
     */
    fun projectedHoursRemaining(batteryPercent: Int, elapsedMillis: Long): Double {
        if (elapsedMillis <= 0 || totalMilliampHours <= 0.0) return Double.POSITIVE_INFINITY
        val drawMilliamps = totalMilliampHours / (elapsedMillis / MILLIS_PER_HOUR)
        if (drawMilliamps <= 0.0) return Double.POSITIVE_INFINITY
        val remainingMilliampHours = batteryCapacityMilliampHours * (batteryPercent / 100.0)
        return remainingMilliampHours / drawMilliamps
    }

    private fun toMilliampHours(milliamps: Double, durationMillis: Long): Double =
        milliamps * (durationMillis / MILLIS_PER_HOUR)

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
