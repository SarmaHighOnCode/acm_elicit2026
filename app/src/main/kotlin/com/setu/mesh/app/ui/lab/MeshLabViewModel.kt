package com.setu.mesh.app.ui.lab

import com.setu.mesh.core.power.PowerTier
import com.setu.mesh.sim.Metrics
import com.setu.mesh.sim.Scenario
import com.setu.mesh.sim.SimNode
import com.setu.mesh.sim.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One node as the canvas needs it. Position is normalised to 0..1 within the world's bounds. */
data class LabNodeView(
    val nodeId: Int,
    val x: Float,
    val y: Float,
    val tier: PowerTier,
    val batteryPercent: Int,
    val alive: Boolean,
    val isGateway: Boolean,
    /** True for one publish cycle after this node's relay count increased. */
    val justRelayed: Boolean,
)

data class LabSnapshot(
    val scenario: String,
    val nodes: List<LabNodeView> = emptyList(),
    val metrics: Metrics? = null,
    val running: Boolean = false,
)

/**
 * Runs `:sim`'s `World` on a background dispatcher and publishes a lightweight snapshot at a
 * fixed rate. Every number here comes from the same `MeshNode`/`World` code the CLI simulator
 * and the sweeps use -- this class only reads state and steps time, never makes a routing
 * decision, same discipline as `AndroidLink`.
 */
class MeshLabViewModel {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var runLoop: Job? = null

    private var world: World? = null
    private var seed: Long = DEFAULT_SEED
    private var lastRelayCounts: MutableMap<Int, Long> = mutableMapOf()

    private val _snapshot = MutableStateFlow(LabSnapshot(scenario = DEFAULT_SCENARIO))
    val snapshot: StateFlow<LabSnapshot> = _snapshot.asStateFlow()

    var paused: Boolean = false
        private set

    val scenarioNames: List<String> get() = Scenario.NAMES

    init {
        reset(DEFAULT_SCENARIO)
    }

    /** Rebuilds the world from scratch with a fresh seed -- always converges to a clean state. */
    fun reset(scenarioName: String = _snapshot.value.scenario) {
        runLoop?.cancel()
        seed = System.nanoTime()
        lastRelayCounts = mutableMapOf()
        val newWorld = Scenario.build(scenarioName, NODE_COUNT, World.DEFAULT_RANGE_METRES, World.DEFAULT_LOSS_RATE, Random(seed))
        world = newWorld
        paused = false
        publish(newWorld, scenarioName)
        startLoop(newWorld, scenarioName)
    }

    fun togglePause() {
        paused = !paused
    }

    /** Tap-to-kill: zeroes one node's battery immediately. */
    fun kill(nodeId: Int) {
        world?.nodes?.firstOrNull { it.id.raw == nodeId }?.battery?.kill()
    }

    /** Drain slider: reduces every node's remaining charge by [fraction] at once. */
    fun drainAll(fraction: Double) {
        world?.nodes?.forEach { it.battery.drainByFraction(fraction) }
    }

    fun shutdown() {
        runLoop?.cancel()
    }

    private fun startLoop(activeWorld: World, scenarioName: String) {
        runLoop = scope.launch {
            while (true) {
                if (!paused) {
                    repeat(TICKS_PER_FRAME) { activeWorld.tick() }
                    publish(activeWorld, scenarioName)
                }
                delay(FRAME_INTERVAL_MILLIS)
            }
        }
    }

    private fun publish(activeWorld: World, scenarioName: String) {
        val nodes = activeWorld.nodes
        val positions = nodes.mapNotNull { it.host.position() }
        val minLat = positions.minOfOrNull { it.latitudeE7 } ?: 0
        val maxLat = positions.maxOfOrNull { it.latitudeE7 } ?: 1
        val minLon = positions.minOfOrNull { it.longitudeE7 } ?: 0
        val maxLon = positions.maxOfOrNull { it.longitudeE7 } ?: 1
        val latSpan = (maxLat - minLat).coerceAtLeast(1)
        val lonSpan = (maxLon - minLon).coerceAtLeast(1)

        val views = nodes.map { node -> toView(node, minLat, latSpan, minLon, lonSpan) }
        val metrics = Metrics.collect(nodes)

        _snapshot.value = LabSnapshot(scenario = scenarioName, nodes = views, metrics = metrics, running = true)
    }

    private fun toView(node: SimNode, minLat: Int, latSpan: Int, minLon: Int, lonSpan: Int): LabNodeView {
        // SimHost.position() narrows NodeHost's nullable contract to always-non-null.
        val pos = node.host.position()
        val x = (pos.longitudeE7 - minLon).toFloat() / lonSpan
        val y = (pos.latitudeE7 - minLat).toFloat() / latSpan

        val relayed = node.meshNode.ledger.beaconsRelayed
        val previous = lastRelayCounts[node.id.raw] ?: relayed
        val justRelayed = relayed > previous
        lastRelayCounts[node.id.raw] = relayed

        return LabNodeView(
            nodeId = node.id.raw,
            x = x,
            y = y,
            tier = node.meshNode.snapshot.value.tier,
            batteryPercent = node.battery.percent,
            alive = !node.battery.isDead,
            isGateway = node.isGateway,
            justRelayed = justRelayed,
        )
    }

    companion object {
        private const val DEFAULT_SCENARIO = "flood"
        private const val NODE_COUNT = 100
        private const val DEFAULT_SEED = 1L

        /** 8 ticks (2 virtual seconds) per 100ms real frame: ~20x speed, a 30-min scenario plays in ~90s. */
        private const val TICKS_PER_FRAME = 8
        private const val FRAME_INTERVAL_MILLIS = 100L
    }
}
