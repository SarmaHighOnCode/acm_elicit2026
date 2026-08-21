package com.setu.mesh.core.power

/**
 * How hard a node is willing to work the radio, as a function of how much battery it has left.
 *
 * The ladder exists because the two radio modes have wildly different costs. Published
 * measurements on BLE silicon put continuous scanning near 40 mW against roughly 600 µW for
 * advertising — call it two orders of magnitude. So the tiers do not scale one dial down
 * uniformly; they *drop scanning first* and keep advertising alive as long as possible, because
 * advertising is what keeps a victim discoverable and scanning is what makes them a good
 * neighbour. A dying phone should be selfish, and SETU encodes exactly when.
 *
 * [EMBER] never scans at all. It is deaf, still shouting, and still findable.
 */
enum class PowerTier(
    /** Lowest battery percentage that still qualifies for this tier. */
    val minBatteryPercent: Int,
    /** Gap between beacon broadcasts. */
    val beaconIntervalMillis: Long,
    /** Length of one scan window when this tier scans at all. */
    val scanWindowMillis: Long,
    /** Scan once every N rendezvous epochs. Zero means never. */
    val epochsBetweenScans: Int,
    /** Whether this tier may spend energy on GATT connections for rich bundles. */
    val mayOpenConnections: Boolean,
) {
    /** Plugged in or comfortably charged: carries the neighbourhood. */
    BRIDGE(60, 500L, 8_000L, 1, true),

    /** Healthy: relays freely, connects when a rich bundle needs moving. */
    RELAY(30, 1_000L, 3_000L, 1, true),

    /** Getting thin: still listens, but stops paying for connections. */
    GOSSIP(15, 1_000L, 1_500L, 2, false),

    /** Low: a beacon with ears open one second per minute. */
    FLARE(5, 2_000L, 1_000L, 4, false),

    /** Critical: pure beacon. Deaf by design so it can keep shouting for hours. */
    EMBER(0, 10_000L, 0L, 0, false);

    val scans: Boolean get() = epochsBetweenScans > 0

    companion object {
        /**
         * A charging device volunteers for [BRIDGE] whatever its current level — it is gaining
         * energy, so spending it on the neighbourhood costs the owner nothing.
         */
        fun forBattery(batteryPercent: Int, charging: Boolean): PowerTier {
            if (charging) return BRIDGE
            return entries.firstOrNull { batteryPercent >= it.minBatteryPercent } ?: EMBER
        }
    }
}
