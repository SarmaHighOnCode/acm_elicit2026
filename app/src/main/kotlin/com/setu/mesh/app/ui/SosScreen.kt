package com.setu.mesh.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.service.SelfFix
import com.setu.mesh.app.service.SetuService
import com.setu.mesh.app.ui.components.StatusLadder
import com.setu.mesh.app.ui.components.TierBadge
import com.setu.mesh.app.ui.components.formatSelfFixLine
import com.setu.mesh.app.ui.theme.SafeHopShapes
import com.setu.mesh.app.ui.theme.neumorphic
import com.setu.mesh.core.engine.NodeSnapshot
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SituationFlags
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The screen a stranded person uses. Design constraint: they are panicking, possibly
 * one-handed, possibly in the dark or in water, phone at 4%. Every normal UI assumption is
 * wrong here, so the SOS button is circular, centred, and fixed -- it can never be scrolled out
 * of view -- and sends immediately with no form to fill in first. Triage refines afterwards,
 * never blocks sending.
 *
 * State lives here rather than in a ViewModel, for two specific reasons:
 *
 *  - **"Is an SOS outstanding?" is derived from the protocol, never mirrored.** It is exactly
 *    `snapshot.ownSos != null`. An earlier version tracked it as a separate UI boolean, which
 *    could desync from reality -- on rotation, or after a service restart, the screen would show
 *    "not sent" while the mesh was still broadcasting the SOS. In an emergency app that is the
 *    worst possible class of bug, so the mirror is gone.
 *  - **Triage inputs use `rememberSaveable`**, so a rotation does not silently reset someone's
 *    "trapped / water rising" answers back to defaults.
 *
 * `onDeveloperEntry` is invoked by a long-press on the tier badge -- see [Header] -- and opens
 * the hidden Mesh Lab / Diagnostics host. No visual hint that gesture exists: no toast, no badge,
 * no "hold for dev tools" text.
 */
@Composable
fun SosScreen(onDeveloperEntry: () -> Unit = {}) {
    val snapshot by SetuService.snapshot.collectAsState()

    var severity by rememberSaveable { mutableStateOf(Severity.HIGH) }
    var souls by rememberSaveable { mutableIntStateOf(1) }
    var trapped by rememberSaveable { mutableStateOf(false) }
    var medicalNeed by rememberSaveable { mutableStateOf(false) }
    var waterRising by rememberSaveable { mutableStateOf(false) }

    // Single source of truth: the node either holds an outstanding own SOS or it does not.
    val sosActive = snapshot?.ownSos != null

    // Also derived from the protocol, not mirrored: the outstanding beacon's own `position`
    // field is exactly what went out on the radio (see MeshNode.originateSos), so "did my SOS
    // carry a location" reads directly from it rather than from a separately tracked flag that
    // could desync the same way `sosActive` used to.
    val ownSosMissingPosition = snapshot?.ownSos?.let { it.position == GeoPoint.UNKNOWN } ?: false

    // Polled rather than pushed for the same reason MeshViewModel polls carriedMessages(): the
    // fix has no flow of its own, only a getter, so a timer is what makes "3 s ago" keep counting.
    var selfFix by remember { mutableStateOf<SelfFix?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            selfFix = SetuService.selfFix()
            delay(SELF_FIX_POLL_INTERVAL_MILLIS)
        }
    }

    fun flags() = SituationFlags(
        severity = severity,
        trapped = trapped,
        medicalNeed = medicalNeed,
        waterRising = waterRising,
    )

    // Editing triage before the first send is free; after it, each edit re-sends so the mesh
    // carries the corrected situation rather than the stale one.
    fun resendIfActive() {
        if (sosActive) SetuService.originateSos(flags(), souls)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Below this height the stacked layout leaves the fixed region too short for a
            // usable button (this is the case in landscape on most phones, once the nav bar and
            // header are accounted for) -- switch to a side-by-side Row instead of shrinking the
            // circle toward its floor.
            val isShort = maxHeight < 400.dp

            Column(modifier = Modifier.fillMaxSize()) {
                Header(snapshot = snapshot, onDeveloperEntry = onDeveloperEntry)
                SelfFixLine(selfFix)

                if (isShort) {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        FixedSosRegion(
                            sosActive = sosActive,
                            ownSosMissingPosition = ownSosMissingPosition,
                            onSend = { SetuService.originateSos(flags(), souls) },
                            onMarkSafe = { SetuService.markSafe() },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        ScrollingTriageRegion(
                            snapshot = snapshot,
                            sosActive = sosActive,
                            severity = severity,
                            souls = souls,
                            trapped = trapped,
                            medicalNeed = medicalNeed,
                            waterRising = waterRising,
                            onSeverity = { severity = it; resendIfActive() },
                            onSouls = { souls = it.coerceIn(1, 255); resendIfActive() },
                            onTrapped = { trapped = !trapped; resendIfActive() },
                            onMedical = { medicalNeed = !medicalNeed; resendIfActive() },
                            onWater = { waterRising = !waterRising; resendIfActive() },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                } else {
                    // Fixed region: never part of the scroll below, so the button and the safe
                    // control are always reachable without scrolling, at any screen size.
                    FixedSosRegion(
                        sosActive = sosActive,
                        ownSosMissingPosition = ownSosMissingPosition,
                        onSend = { SetuService.originateSos(flags(), souls) },
                        onMarkSafe = { SetuService.markSafe() },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    ScrollingTriageRegion(
                        snapshot = snapshot,
                        sosActive = sosActive,
                        severity = severity,
                        souls = souls,
                        trapped = trapped,
                        medicalNeed = medicalNeed,
                        waterRising = waterRising,
                        onSeverity = { severity = it; resendIfActive() },
                        onSouls = { souls = it.coerceIn(1, 255); resendIfActive() },
                        onTrapped = { trapped = !trapped; resendIfActive() },
                        onMedical = { medicalNeed = !medicalNeed; resendIfActive() },
                        onWater = { waterRising = !waterRising; resendIfActive() },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The fixed, never-scrolling region that owns the SOS button (and, once active, the safe
 * control). Its own `BoxWithConstraints` is the fix for a real bug: sizing the button from the
 * *whole screen's* constraints let the diameter floor exceed the space this region actually gets
 * once the header and the scroll region below have taken their share, coercing `size()` into a
 * non-square box that `clip(CircleShape)` rendered as an oval. Measuring from this composable's
 * own incoming constraints -- and subtracting what the safe control needs when it's showing --
 * means the diameter is always sized to the space that is actually available here.
 */
@Composable
private fun FixedSosRegion(
    sosActive: Boolean,
    ownSosMissingPosition: Boolean,
    onSend: () -> Unit,
    onMarkSafe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val showMissingLocationWarning = sosActive && ownSosMissingPosition
        val reservedForSafeControl = if (sosActive) SAFE_CONTROL_RESERVED_HEIGHT else 0.dp
        val reservedForMissingLocation = if (showMissingLocationWarning) MISSING_LOCATION_RESERVED_HEIGHT else 0.dp
        val availableHeight = (maxHeight - reservedForSafeControl - reservedForMissingLocation).coerceAtLeast(0.dp)
        val diameter = (minOf(maxWidth, availableHeight) * SOS_BUTTON_SIZE_FRACTION)
            .coerceIn(SOS_BUTTON_MIN_DIAMETER, SOS_BUTTON_MAX_DIAMETER)

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            SosButton(
                sent = sosActive,
                diameter = diameter,
                onSend = onSend,
            )
            if (showMissingLocationWarning) {
                // Prominent and in the fixed region on purpose -- this is the one fact a victim
                // most needs to know right after tapping SOS, and it must never be scrolled out
                // of view the way a line buried in the triage section could be.
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Sent without location — still searching for GPS",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (sosActive) {
                Spacer(Modifier.height(20.dp))
                MarkSafeButton(onClick = onMarkSafe)
            }
        }
    }
}

@Composable
private fun ScrollingTriageRegion(
    snapshot: NodeSnapshot?,
    sosActive: Boolean,
    severity: Severity,
    souls: Int,
    trapped: Boolean,
    medicalNeed: Boolean,
    waterRising: Boolean,
    onSeverity: (Severity) -> Unit,
    onSouls: (Int) -> Unit,
    onTrapped: () -> Unit,
    onMedical: () -> Unit,
    onWater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        if (sosActive) {
            StatusLadder(
                carrying = snapshot?.carrying ?: 0,
                maxHops = snapshot?.ownSosMaxHops ?: 0,
                delivered = snapshot?.ownSosDelivered ?: false,
            )
            Spacer(Modifier.height(24.dp))
        } else {
            Text(
                text = if (snapshot == null) "Starting…" else "Tap SOS to send your location and situation.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }

        TriageControls(
            severity = severity,
            souls = souls,
            trapped = trapped,
            medicalNeed = medicalNeed,
            waterRising = waterRising,
            onSeverity = onSeverity,
            onSouls = onSouls,
            onTrapped = onTrapped,
            onMedical = onMedical,
            onWater = onWater,
        )

        if (sosActive) {
            Spacer(Modifier.height(24.dp))
            EnergySummary(snapshot)
        }
    }
}

/**
 * Fixed header. The long-press-for-dev-tools gesture lives here, attached with `combinedClickable`
 * at the call site rather than baked into `TierBadge` itself, so the badge stays a plain
 * data-display component everywhere else it's used. `indication = null` is deliberate: any
 * visible press feedback here would itself be the hint this gesture is supposed not to have.
 */
@Composable
private fun Header(snapshot: NodeSnapshot?, onDeveloperEntry: () -> Unit) {
    if (snapshot == null) return
    val devInteractionSource = remember { MutableInteractionSource() }
    TierBadge(
        tier = snapshot.tier,
        lastGasp = snapshot.lastGasp,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .combinedClickable(
                interactionSource = devInteractionSource,
                indication = null,
                onClick = {},
                onLongClick = onDeveloperEntry,
            ),
    )
}

/**
 * Compact fix-quality line, same wording as the responder map's footer (see
 * [com.setu.mesh.app.ui.components.formatSelfFixLine]) -- so a victim can tell at a glance
 * whether their SOS is carrying a real position before they even tap send. Real values only:
 * no fix reads as "none yet", never a guess.
 */
@Composable
private fun SelfFixLine(selfFix: SelfFix?) {
    Text(
        text = formatSelfFixLine(selfFix, System.currentTimeMillis()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

/**
 * The button itself. Flat and saturated on purpose -- this is the one control on the whole
 * screen that must never carry the neumorphic accent, per `docs/design.md`: a soft shadow reads
 * as decoration, not as "the thing that sends your SOS."
 */
@Composable
private fun SosButton(
    sent: Boolean,
    diameter: Dp,
    onSend: () -> Unit,
) {
    val color = if (sent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
    val onColor = if (sent) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSecondary

    Box(
        // requiredSize, not size: size() lets an incoming constraint from a cramped parent
        // coerce the box into a non-square shape, which clip(CircleShape) then renders as an
        // oval instead of a circle. requiredSize ignores incoming constraints entirely, so this
        // box is always exactly diameter x diameter regardless of what its parent offers.
        modifier = Modifier
            .requiredSize(diameter)
            .clip(CircleShape)
            .background(color)
            .clickable { onSend() }
            .semantics { contentDescription = "Send emergency SOS" },
        contentAlignment = Alignment.Center,
    ) {
        if (sent) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SOS SENT",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = onColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap to resend",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColor,
                )
            }
        } else {
            Text(
                text = "SOS",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = onColor,
            )
        }
    }
}

/**
 * Pulled out from inside the SOS button (B9): a nested `clickable` `Text` inside a circular
 * button was both an accessibility bug and something that was never going to fit inside a
 * circle. Its own control now, rendered only once an SOS is outstanding.
 */
@Composable
private fun MarkSafeButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "Mark yourself safe, cancelling the SOS" },
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
    ) {
        Text("I am safe now", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TriageControls(
    severity: Severity,
    souls: Int,
    trapped: Boolean,
    medicalNeed: Boolean,
    waterRising: Boolean,
    onSeverity: (Severity) -> Unit,
    onSouls: (Int) -> Unit,
    onTrapped: () -> Unit,
    onMedical: () -> Unit,
    onWater: () -> Unit,
) {
    NeumorphicSection {
        Text(
            text = "Situation",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Severity is emergency state, not a secondary surface -- stays flat per
            // docs/design.md, even though it sits inside a neumorphic section container.
            Severity.entries.forEach { value ->
                FilterChip(
                    selected = severity == value,
                    onClick = { onSeverity(value) },
                    label = {
                        Text(
                            text = value.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Severity: ${value.name}" },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    NeumorphicSection {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "People here",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CounterButton("−", { onSouls(souls - 1) }, "Decrease people count")
                Text(
                    text = souls.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                CounterButton("+", { onSouls(souls + 1) }, "Increase people count")
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    NeumorphicSection {
        ToggleRow("Trapped", trapped, onTrapped)
        Spacer(Modifier.height(8.dp))
        ToggleRow("Medical need", medicalNeed, onMedical)
        Spacer(Modifier.height(8.dp))
        ToggleRow("Water rising", waterRising, onWater)
    }
}

/**
 * The one reusable "secondary surface" shape for this screen: neumorphic card container per
 * `docs/design.md` (14dp radius, two shadow layers). Every triage group lives inside one of
 * these -- this is the accent dose, not the whole screen.
 */
@Composable
private fun NeumorphicSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neumorphic(cornerRadius = SafeHopShapes.cornerSmall)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(SafeHopShapes.cornerSmall))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun CounterButton(symbol: String, onClick: () -> Unit, description: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .height(56.dp)
            .width(56.dp)
            .neumorphic(cornerRadius = 12.dp, elevation = 5.dp, pressed = pressed)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .neumorphic(cornerRadius = 12.dp, elevation = 4.dp, pressed = pressed)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "$label, ${if (checked) "on" else "off"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .width(28.dp)
                .background(
                    // The checkbox itself still needs saturated on/off contrast -- neumorphism
                    // encodes state as shadow, and a checked/unchecked toggle is exactly the
                    // state a neumorphic surface hides best.
                    if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Only rendered once real numbers exist. A placeholder here would look like a real measurement,
 * which is exactly what `docs/POWER.md` warns against doing anywhere in this project.
 */
@Composable
private fun EnergySummary(snapshot: NodeSnapshot?) {
    val energy = snapshot?.energyMilliampHours ?: return
    if (energy <= 0.0) return
    Text(
        text = "SafeHop has used %.2f mAh and carried %d messages so far.".format(energy, snapshot.beaconsRelayed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// -- SOS button sizing --------------------------------------------------------------------
// Fraction of the fixed region's own (width, height-minus-safe-control) that the button
// occupies, clamped so it neither vanishes on a tiny region nor overwhelms a huge one.
private const val SOS_BUTTON_SIZE_FRACTION = 0.55f
private val SOS_BUTTON_MIN_DIAMETER = 120.dp
private val SOS_BUTTON_MAX_DIAMETER = 260.dp

// Spacer (20dp) + MarkSafeButton (48dp min height) below the circle when sosActive -- held out
// of the button's own share of the fixed region so the two never compete for the same space.
private val SAFE_CONTROL_RESERVED_HEIGHT = 68.dp

// Spacer (12dp) + the "sent without location" line, held out of the button's share the same way
// SAFE_CONTROL_RESERVED_HEIGHT is, for the same reason.
private val MISSING_LOCATION_RESERVED_HEIGHT = 44.dp

// GPS fixes arrive at most once a second (AndroidNodeHost); polling faster would just repaint
// the same age in seconds, and polling this screen's fix independently of MeshViewModel's is
// what keeps "3 s ago" counting even when Help others isn't the visible tab.
private const val SELF_FIX_POLL_INTERVAL_MILLIS = 1_000L
