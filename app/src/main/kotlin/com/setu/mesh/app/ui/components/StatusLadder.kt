package com.setu.mesh.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The emotional core of the SOS screen: the four rungs a message climbs from "sent" to
 * "a rescuer confirmed it reached them". Driven entirely from [NodeSnapshot] fields -- no
 * step here is invented, each maps to a real protocol event.
 */
@Composable
fun StatusLadder(
    carrying: Int,
    maxHops: Int,
    delivered: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Rung(done = true, text = "SOS created")
        Rung(
            done = carrying > 0,
            text = if (carrying > 0) "Carried by $carrying phone${if (carrying == 1) "" else "s"}" else "Waiting for a neighbour to hear it",
        )
        Rung(
            done = maxHops > 0,
            text = if (maxHops > 0) "$maxHops hop${if (maxHops == 1) "" else "s"} out" else "Not yet relayed further",
        )
        Rung(
            done = delivered,
            text = if (delivered) "Reached a rescuer" else "Waiting for a rescuer to confirm",
            emphasize = delivered,
        )
    }
}

@Composable
private fun Rung(done: Boolean, text: String, emphasize: Boolean = false) {
    val color = when {
        emphasize -> MaterialTheme.colorScheme.tertiary
        done -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row {
        Text(
            text = if (done) "✓" else "⏳",
            color = color,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            color = color,
            style = if (emphasize) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
    Spacer(Modifier.height(10.dp))
}
