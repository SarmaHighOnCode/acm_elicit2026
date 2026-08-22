package com.setu.mesh.app.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.setu.mesh.core.power.PowerTier
import kotlin.math.ceil

/**
 * One `Canvas`, one draw pass, for every node -- not a composable per node. At 100+ nodes a
 * per-node composable approach cannot hold frame rate; this can.
 */
@Composable
fun NodeGraphCanvas(
    nodes: List<LabNodeView>,
    onTapNode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(nodes) {
                detectTapGestures { tapOffset ->
                    val hit = nodes.minByOrNull { node ->
                        val nx = node.x * size.width
                        val ny = node.y * size.height
                        val dx = nx - tapOffset.x
                        val dy = ny - tapOffset.y
                        dx * dx + dy * dy
                    } ?: return@detectTapGestures
                    val nx = hit.x * size.width
                    val ny = hit.y * size.height
                    val distSq = (nx - tapOffset.x) * (nx - tapOffset.x) + (ny - tapOffset.y) * (ny - tapOffset.y)
                    if (distSq <= TAP_RADIUS_PX * TAP_RADIUS_PX) onTapNode(hit.nodeId)
                }
            },
    ) {
        val alive = nodes.filter { it.alive }
        val points = alive.map { Offset(it.x * size.width, it.y * size.height) }
        drawLinks(alive, points, size)
        nodes.forEach { node -> drawNode(node, Offset(node.x * size.width, node.y * size.height)) }
    }
}

/**
 * Thin lines between nodes close enough to plausibly be in radio range.
 *
 * Uses a uniform spatial grid rather than checking all pairs. Cells are exactly one link-distance
 * across, so any two nodes within range must share a cell or sit in adjacent cells -- checking a
 * node against its own cell plus four forward neighbours (E, SW, S, SE) covers every pair exactly
 * once. That turns an O(n^2) scan into O(n * k) where k is the local density.
 *
 * At the current 100 nodes the all-pairs version was not actually the frame-time bottleneck (the
 * `drawLine` calls dominate); this matters because the node count is the obvious dial to turn up
 * for a bigger demo, and 500 nodes all-pairs is 125,000 checks per frame.
 */
private fun DrawScope.drawLinks(alive: List<LabNodeView>, points: List<Offset>, canvasSize: Size) {
    if (alive.size < 2) return

    val cell = LINK_DISTANCE_PX
    val cols = ceil(canvasSize.width / cell).toInt().coerceAtLeast(1)
    val rows = ceil(canvasSize.height / cell).toInt().coerceAtLeast(1)

    // Bucket index -> indices into `points`.
    val buckets = HashMap<Int, MutableList<Int>>(alive.size * 2)
    val cellOf = IntArray(points.size)
    points.forEachIndexed { index, point ->
        val col = (point.x / cell).toInt().coerceIn(0, cols - 1)
        val row = (point.y / cell).toInt().coerceIn(0, rows - 1)
        val key = row * cols + col
        cellOf[index] = key
        buckets.getOrPut(key) { mutableListOf() }.add(index)
    }

    val maxDistSq = LINK_DISTANCE_PX * LINK_DISTANCE_PX

    points.forEachIndexed { index, point ->
        val key = cellOf[index]
        val col = key % cols
        val row = key / cols

        // Own cell (higher indices only) plus the four forward neighbours. This visits each
        // unordered pair exactly once without needing a seen-set.
        for ((dCol, dRow) in FORWARD_NEIGHBOURS) {
            val nCol = col + dCol
            val nRow = row + dRow
            if (nCol !in 0 until cols || nRow !in 0 until rows) continue
            val neighbours = buckets[nRow * cols + nCol] ?: continue
            val sameCell = dCol == 0 && dRow == 0

            for (other in neighbours) {
                if (sameCell && other <= index) continue
                val q = points[other]
                val dx = point.x - q.x
                val dy = point.y - q.y
                if (dx * dx + dy * dy <= maxDistSq) {
                    drawLine(color = LinkColor, start = point, end = q, strokeWidth = 1f)
                }
            }
        }
    }
}

private fun DrawScope.drawNode(node: LabNodeView, center: Offset) {
    val radius = when {
        node.isGateway -> NODE_RADIUS_PX * 1.6f
        node.justRelayed -> NODE_RADIUS_PX * 1.3f
        else -> NODE_RADIUS_PX
    }
    val color = if (!node.alive) DeadColor else tierColor(node.tier)

    if (node.justRelayed && node.alive) {
        drawCircle(color = color.copy(alpha = 0.3f), radius = radius * 2f, center = center)
    }
    drawCircle(color = color, radius = radius, center = center)
    if (node.isGateway) {
        drawCircle(color = Color.White, radius = radius, center = center, style = Stroke(width = 2f))
    }
}

private fun tierColor(tier: PowerTier): Color = when (tier) {
    PowerTier.BRIDGE, PowerTier.RELAY -> Color(0xFF66BB6A)
    PowerTier.GOSSIP -> Color(0xFFFFA726)
    PowerTier.FLARE -> Color(0xFFFF7043)
    PowerTier.EMBER -> Color(0xFFEF5350)
}

/** Own cell, then E, SW, S, SE. Half the Moore neighbourhood, so each pair is visited once. */
private val FORWARD_NEIGHBOURS = listOf(
    0 to 0,
    1 to 0,
    -1 to 1,
    0 to 1,
    1 to 1,
)

private val DeadColor = Color(0xFF555555)
private val LinkColor = Color(0x33FFFFFF)

private const val NODE_RADIUS_PX = 10f
private const val LINK_DISTANCE_PX = 140f
private const val TAP_RADIUS_PX = 28f
