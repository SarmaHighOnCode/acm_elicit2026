package com.setu.mesh.core.power

/**
 * Phase-locked wake windows — the mechanism that makes low duty cycles actually work.
 *
 * The naive version of "save power by scanning less" quietly destroys the mesh. Two nodes each
 * listening 5% of the time, with independent phase, overlap 0.25% of the time; at that point
 * they will essentially never hear each other and the network has silently died while both
 * phones report everything is fine.
 *
 * SETU derives the window from **absolute wall-clock time** rather than from each node's own
 * uptime:
 *
 * ```
 * epoch  = floor(unixMillis / EPOCH_MILLIS)
 * window = [epoch * EPOCH_MILLIS, + WINDOW_MILLIS)
 * ```
 *
 * Every node in the region therefore wakes inside the same one-second window without ever
 * having exchanged a scheduling message. Low tiers skip whole epochs but stay phase-aligned
 * (`epoch % n == 0`), so an EMBER node waking once every four minutes still lands exactly on a
 * window that a BRIDGE node is listening through.
 *
 * The 60-second epoch is also what keeps SETU inside Android's scan throttle of **5
 * `startScan` calls per 30 seconds**: at most one scan per epoch is one per minute.
 *
 * Clock quality degrades gracefully: GPS time, else last NTP sync, else consensus drift from
 * the `epochMin` field that every beacon already carries (see [applyPeerObservation]).
 */
class RendezvousScheduler(
    private val epochMillis: Long = DEFAULT_EPOCH_MILLIS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    /** Correction added to local time to align with the mesh's consensus clock. */
    var driftCorrectionMillis: Long = 0L
        private set

    private fun meshTime(localNowMillis: Long): Long = localNowMillis + driftCorrectionMillis

    fun epochIndex(localNowMillis: Long): Long = meshTime(localNowMillis) / epochMillis

    fun windowStartMillis(localNowMillis: Long): Long = epochIndex(localNowMillis) * epochMillis

    fun isInWindow(localNowMillis: Long): Boolean =
        (meshTime(localNowMillis) - windowStartMillis(localNowMillis)) < windowMillis

    /**
     * Whether [tier] participates in this epoch. Phase alignment is preserved because the test
     * is on the absolute epoch index, not on a counter local to the node.
     */
    fun scansInEpoch(epoch: Long, tier: PowerTier): Boolean =
        tier.scans && Math.floorMod(epoch, tier.epochsBetweenScans.toLong()) == 0L

    /** Milliseconds to sleep before [tier]'s next participating window opens. */
    fun millisUntilNextWindow(localNowMillis: Long, tier: PowerTier): Long {
        if (!tier.scans) return Long.MAX_VALUE
        var epoch = epochIndex(localNowMillis)
        if (!isInWindow(localNowMillis)) epoch += 1
        while (!scansInEpoch(epoch, tier)) epoch += 1
        return (epoch * epochMillis) - meshTime(localNowMillis)
    }

    /**
     * Nudge the local clock toward the mesh consensus using the coarse timestamp carried in a
     * received beacon. Deliberately a slow single-step correction rather than a jump: a node
     * with a wildly wrong clock should be pulled in over several observations instead of
     * yanking the whole neighbourhood's phase around.
     *
     * @param peerEpochMinute the `epochMin` field from a freshly received beacon
     * @param localNowMillis local wall clock at the moment it was received
     */
    fun applyPeerObservation(peerEpochMinute: Int, localNowMillis: Long, trustedLocalClock: Boolean) {
        if (trustedLocalClock) return
        val localEpochMinute =
            ((meshTime(localNowMillis) - com.setu.mesh.core.model.SETU_EPOCH_MILLIS) /
                com.setu.mesh.core.model.MILLIS_PER_MINUTE).toInt()
        val deltaMinutes = peerEpochMinute - localEpochMinute
        if (deltaMinutes == 0) return
        val deltaMillis = deltaMinutes * com.setu.mesh.core.model.MILLIS_PER_MINUTE
        driftCorrectionMillis += deltaMillis / DRIFT_DAMPING
    }

    companion object {
        const val DEFAULT_EPOCH_MILLIS: Long = 60_000L
        const val DEFAULT_WINDOW_MILLIS: Long = 1_000L

        /** Correct by a fraction of the observed error per beacon, so phase converges smoothly. */
        private const val DRIFT_DAMPING: Long = 4L
    }
}
