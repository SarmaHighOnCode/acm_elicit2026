package com.setu.mesh.core.power

/**
 * Constant overrides for the two protocol knobs that `:sim` sweeps compare against their
 * default behaviour: the energy gate and scanner-election banding.
 *
 * This exists so a sweep can construct a node tuned one way and a node tuned another way in the
 * same process, side by side, with no shared mutable state between them. The alternative —
 * process-global `@Volatile var` flags flipped before and after each sweep arm — shipped in an
 * earlier revision of this code and had a real defect: if a sweep threw mid-run, the flag was
 * never reset (it was reset after the sweep body, not in a `finally`), silently corrupting every
 * later sweep sharing the same JVM. A per-node value can't leak between nodes.
 *
 * [energyGateOverride]: null preserves [com.setu.mesh.core.routing.ForwardingPolicy]'s normal
 * step function. Non-null pins the gate to that value for every decision this node makes —
 * `1.0` reproduces the old "ungated" sweep arm.
 *
 * [scannerBandSizePercent]: width of a scanner-election battery band, in percentage points.
 * Default 10 matches docs/POWER.md §3. `1` reproduces the old "no banding" sweep arm (every
 * distinct battery percentage becomes its own band).
 */
data class ProtocolTuning(
    val energyGateOverride: Double? = null,
    val scannerBandSizePercent: Int = ScannerElection.DEFAULT_BAND_SIZE_PERCENT,
) {
    companion object {
        val DEFAULT = ProtocolTuning()
    }
}
