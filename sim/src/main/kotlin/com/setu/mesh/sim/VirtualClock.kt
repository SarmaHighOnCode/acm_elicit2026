package com.setu.mesh.sim

/**
 * Monotonic virtual time for the simulator.
 *
 * Starts at a realistic wall-clock value — not zero — because the rendezvous scheduler
 * derives its epoch phase from absolute time. Two nodes comparing `nowMillis / epochMillis`
 * must land in the same bucket, which requires realistic timestamps.
 */
class VirtualClock(startMillis: Long = DEFAULT_START_MILLIS) {

    private var _now: Long = startMillis

    fun nowMillis(): Long = _now

    fun advance(millis: Long) {
        require(millis >= 0) { "Cannot advance by negative time: $millis" }
        _now += millis
    }

    companion object {
        /** 2025-08-11T ~13:20 UTC — a believable disaster timestamp. */
        const val DEFAULT_START_MILLIS = 1_755_000_000_000L
    }
}
