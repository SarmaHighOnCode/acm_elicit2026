package com.setu.mesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.setu.mesh.core.power.PowerTier

/**
 * The badge the whole demo points at. Shows the current power tier, colour-coded, with a
 * plain-language sentence explaining what it means for this phone right now — not just a label,
 * because "EMBER" alone means nothing to a person who has never read `docs/POWER.md`.
 */
@Composable
fun TierBadge(tier: PowerTier, lastGasp: Boolean, modifier: Modifier = Modifier) {
    val (color, explanation) = tierPresentation(tier, lastGasp)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(color)
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (lastGasp) "LAST GASP" else tier.name,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Box(color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .height(16.dp)
            .width(16.dp)
            .background(color, RoundedCornerShape(8.dp)),
    )
}

/** Colour and plain-language explanation per tier. See `docs/POWER.md` §1. */
private fun tierPresentation(tier: PowerTier, lastGasp: Boolean): Pair<Color, String> {
    if (lastGasp) {
        return RedColor to "Battery critical. Burst-broadcasting everything this phone is " +
            "carrying so a healthier neighbour can take over before it dies."
    }
    return when (tier) {
        PowerTier.BRIDGE -> GreenColor to
            "Battery healthy. Scanning continuously and carrying messages for the whole neighbourhood."
        PowerTier.RELAY -> GreenColor to
            "Battery good. Relaying normally, connecting when a rich message needs it."
        PowerTier.GOSSIP -> AmberColor to
            "Battery getting thin. Still listening on a schedule, but no longer opening connections."
        PowerTier.FLARE -> OrangeColor to
            "Battery low. Ears open for one second a minute — still findable, less social."
        PowerTier.EMBER -> RedColor to
            "Battery critical. Still broadcasting, no longer listening, to stay findable for longer."
    }
}

private val GreenColor = Color(0xFF66BB6A)
private val AmberColor = Color(0xFFFFA726)
private val OrangeColor = Color(0xFFFF7043)
private val RedColor = Color(0xFFEF5350)
