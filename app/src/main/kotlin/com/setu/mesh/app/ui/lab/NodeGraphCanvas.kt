package com.setu.mesh.app.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import com.setu.mesh.core.power.PowerTier

/**
 * One `Canvas`, one draw pass, for every node -- not a composable per node. At 100 nodes a
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
                    }
                    val hitRadiusSquared = (TAP_RADIUS_PX * TAP_RADIUS_PX)
                    if (hit != null) {
                        val nx = hit.x * size.width
                        val ny = hit.y * size.height
                        val distSq = (nx - tapOffset.x) * (nx - tapOffset.x) + (ny - tapOffset.y) * (ny - tapOffset.y)
                        if (distSq <= hitRadiusSquared) onTapNode(hit.nodeId)
                    }
                }
            },
    ) {
        drawLinks(nodes)
        nodes.forEach { node -> drawNode(node) }
    }
}

/** Thin lines between nodes close enough to plausibly be in radio range, for a sense of topology. */
private fun DrawScope.drawLinks(nodes: List<LabNodeView>) {
    val alive = nodes.filter { it.alive }
    for (i in alive.indices) {
        for (j in i + 1 until alive.size) {
            val a = alive[i]
            val b = alive[j]
            val dx = (a.x - b.x) * size.width
            val dy = (a.y - b.y) * size.height
            val distSq = dx * dx + dy * dy
            if (distSq <= LINK_DISTANCE_PX * LINK_DISTANCE_PX) {
                drawLine(
                    color = LinkColor,
                    start = Offset(a.x * size.width, a.y * size.height),
                    end = Offset(b.x * size.width, b.y * size.height),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

private fun DrawScope.drawNode(node: LabNodeView) {
    val center = Offset(node.x * size.width, node.y * size.height)
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
        drawCircle(color = Color.White, radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
    }
}

private fun tierColor(tier: PowerTier): Color = when (tier) {
    PowerTier.BRIDGE, PowerTier.RELAY -> Color(0xFF66BB6A)
    PowerTier.GOSSIP -> Color(0xFFFFA726)
    PowerTier.FLARE -> Color(0xFFFF7043)
    PowerTier.EMBER -> Color(0xFFEF5350)
}

private val DeadColor = Color(0xFF555555)
private val LinkColor = Color(0x33FFFFFF)

private const val NODE_RADIUS_PX = 10f
private const val LINK_DISTANCE_PX = 140f
private const val TAP_RADIUS_PX = 28f
