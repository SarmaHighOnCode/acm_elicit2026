package com.setu.mesh.core.power

import com.setu.mesh.core.model.NodeId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.sqrt

class ScannerElectionTest {

    @Test
    fun `determinism gives same answer for same inputs on two nodes`() {
        val nodeA = NodeId(1)
        val nodeB = NodeId(2)
        val nodeC = NodeId(3)

        // All have the same battery and are not charging
        val epoch = 10L

        // View from nodeA
        val neighboursA = listOf(
            NeighbourEnergy(nodeB, 50, 0L),
            NeighbourEnergy(nodeC, 50, 0L)
        )
        val aScans = ScannerElection.shouldScan(nodeA, 50, false, neighboursA, epoch)

        // View from nodeB
        val neighboursB = listOf(
            NeighbourEnergy(nodeA, 50, 0L),
            NeighbourEnergy(nodeC, 50, 0L)
        )
        val bScans = ScannerElection.shouldScan(nodeB, 50, false, neighboursB, epoch)

        // Quota is ceil(sqrt(3)) = 2. Exactly 2 nodes should scan.
        // And they should be consistent regardless of which node computes it.
        val neighboursC = listOf(
            NeighbourEnergy(nodeA, 50, 0L),
            NeighbourEnergy(nodeB, 50, 0L)
        )
        val cScans = ScannerElection.shouldScan(nodeC, 50, false, neighboursC, epoch)

        val scanners = listOf(aScans, bScans, cScans).count { it }
        assertEquals(2, scanners)
    }

    @Test
    fun `quota is ceil sqrt n`() {
        assertEquals(1, ScannerElection.scannerQuota(1))
        assertEquals(2, ScannerElection.scannerQuota(2))
        assertEquals(2, ScannerElection.scannerQuota(3))
        assertEquals(2, ScannerElection.scannerQuota(4))
        assertEquals(3, ScannerElection.scannerQuota(5))
        assertEquals(10, ScannerElection.scannerQuota(100))
    }

    @Test
    fun `rotation across epochs`() {
        val nodeA = NodeId(1)
        val nodeB = NodeId(2)
        val nodeC = NodeId(3)
        val nodeD = NodeId(4)

        // Quota is ceil(sqrt(4)) = 2.
        
        var aCount = 0
        var bCount = 0
        var cCount = 0
        var dCount = 0

        for (epoch in 1L..100L) {
            val neighboursA = listOf(
                NeighbourEnergy(nodeB, 50, 0L),
                NeighbourEnergy(nodeC, 50, 0L),
                NeighbourEnergy(nodeD, 50, 0L)
            )
            
            if (ScannerElection.shouldScan(nodeA, 50, false, neighboursA, epoch)) aCount++
            
            // To be thorough, simulate B, C, D as well
            if (ScannerElection.shouldScan(nodeB, 50, false, listOf(NeighbourEnergy(nodeA, 50, 0L), NeighbourEnergy(nodeC, 50, 0L), NeighbourEnergy(nodeD, 50, 0L)), epoch)) bCount++
            if (ScannerElection.shouldScan(nodeC, 50, false, listOf(NeighbourEnergy(nodeA, 50, 0L), NeighbourEnergy(nodeB, 50, 0L), NeighbourEnergy(nodeD, 50, 0L)), epoch)) cCount++
            if (ScannerElection.shouldScan(nodeD, 50, false, listOf(NeighbourEnergy(nodeA, 50, 0L), NeighbourEnergy(nodeB, 50, 0L), NeighbourEnergy(nodeC, 50, 0L)), epoch)) dCount++
        }

        // Each epoch has 2 scanners. Over 100 epochs, there are 200 scanning assignments.
        // They should be relatively evenly distributed over A, B, C, D.
        assertEquals(200, aCount + bCount + cCount + dCount)
        assertTrue(aCount > 10, "A should scan sometimes")
        assertTrue(bCount > 10, "B should scan sometimes")
        assertTrue(cCount > 10, "C should scan sometimes")
        assertTrue(dCount > 10, "D should scan sometimes")
    }

    @Test
    fun `empty neighbours returns true`() {
        assertTrue(ScannerElection.shouldScan(NodeId(1), 50, false, emptyList(), 1L))
    }

    @Test
    fun `a node a battery band higher wins consistently`() {
        // Self at 95% (band 9) against ten neighbours all at 50% (band 5) -- two full bands
        // below. Quota is ceil(sqrt(11)) = 4, so self's band alone guarantees a top-4 slot on
        // every single epoch: banding must not let a lucky tiebreak let a lower band win over
        // a strictly higher one.
        val self = NodeId(1)
        val neighbours = (2..11).map { NeighbourEnergy(NodeId(it), 50, 0L) }

        val epochs = 200L
        var selfScans = 0
        for (epoch in 1..epochs) {
            if (ScannerElection.shouldScan(self, 95, false, neighbours, epoch)) selfScans++
        }

        assertEquals(epochs.toInt(), selfScans, "a node a full battery band higher must scan every single epoch")
    }

    @Test
    fun `nodes within the same battery band rotate even with unequal battery`() {
        // A(44), B(46), D(41), E(49) are all in the 40% band (battery / 10 == 4) despite no
        // two of them sharing an exact battery reading. C(5%, band 0) pads the neighbourhood so
        // quota (ceil(sqrt(5)) = 3) is less than the four same-band contenders, forcing real
        // competition among them via the epoch-mixed tiebreak rather than exact-equality luck.
        val a = NodeId(1); val b = NodeId(2); val d = NodeId(3); val e = NodeId(4); val c = NodeId(5)
        val battery = mapOf(a to 44, b to 46, d to 41, e to 49, c to 5)
        val contenders = listOf(a, b, d, e)

        val scans = contenders.associateWith { 0 }.toMutableMap()
        var cScans = 0

        for (epoch in 1L..200L) {
            for (node in contenders) {
                val neighbours = (contenders + c).filter { it != node }
                    .map { NeighbourEnergy(it, battery.getValue(it), 0L) }
                if (ScannerElection.shouldScan(node, battery.getValue(node), false, neighbours, epoch)) {
                    scans[node] = scans.getValue(node) + 1
                }
            }
            val cNeighbours = (contenders).map { NeighbourEnergy(it, battery.getValue(it), 0L) }
            if (ScannerElection.shouldScan(c, battery.getValue(c), false, cNeighbours, epoch)) cScans++
        }

        contenders.forEach { node ->
            assertTrue(scans.getValue(node) > 0, "$node never scanned despite being in the winning band: $scans")
        }
        assertEquals(0, cScans, "the low-band node should never outrank same-band contenders")
    }
}
