package dev.pridgey.sloota.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pridgey.sloota.Targets
import dev.pridgey.sloota.gate.AppDay
import dev.pridgey.sloota.gate.Gate
import dev.pridgey.sloota.gate.Phase
import dev.pridgey.sloota.gate.Rules
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

private val LIMIT_CHOICES = listOf(0, 15, 30, 45, 60, 90, 120)
private val CAP_CHOICES = listOf(0, 3, 5, 10, 15)
private val MORE_CHOICES = listOf(5, 10, 15, 30)

/** Re-reads the gate whenever it saves, and every few seconds so today's minutes stay current. */
@Composable
fun rememberGateVersion(): Int {
    val saved by Gate.changes.collectAsState()
    var clock by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            clock++
        }
    }
    return saved * 100_000 + clock
}

fun minutes(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> "<1 min"
        m < 60 -> "$m min"
        m % 60 == 0L -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

private fun limitLabel(min: Int) = if (min == 0) "Off" else minutes(min * 60_000L)

private fun minuteNow(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

private fun hoursLabel(start: Int, end: Int) = "${Rules.clock(start)} – ${Rules.clock(end)}"

private fun status(day: AppDay): String = when {
    day.phase == Phase.DONE -> "Done for today"
    day.phase == Phase.WINDING_DOWN -> "Wrapping up at the next good stop"
    day.windowed && !Rules.inWindow(day, minuteNow()) -> "Opens at ${Rules.clock(day.windowStart)}"
    day.limited -> "${minutes(day.usedMs)} of ${minutes(day.budgetMs)}"
    day.windowed -> "Open until ${Rules.clock(day.windowEnd)} · ${minutes(day.usedMs)} today"
    else -> "No limit · ${minutes(day.usedMs)} today"
}

// --- Home pieces ---

/** The three counters: under the limit, hit the limit, added more time. */
@Composable
fun StatTiles(stats: Rules.Stats) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            Modifier.weight(1f),
            stats.underDays,
            "Under your limit",
            if (stats.underDays > 0) "Yay, you used less!" else "Days you stay under show up here",
            highlight = true,
        )
        StatTile(Modifier.weight(1f), stats.hits, "You hit your Sloota", if (stats.hits == 1) "time" else "times")
        StatTile(Modifier.weight(1f), stats.extensions, "Went back for more time", if (stats.extensions == 1) "time" else "times")
    }
}

@Composable
private fun StatTile(modifier: Modifier, value: Int, label: String, caption: String, highlight: Boolean = false) {
    Surface(
        modifier.fillMaxHeight(),
        shape = RoundedCornerShape(18.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "%,d".format(value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppTodayRow(app: Targets.App, day: AppDay, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(status(day), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (day.limited) UsageBar(day)
        }
    }
}

@Composable
private fun UsageBar(day: AppDay) {
    val fraction = if (day.budgetMs > 0) (day.usedMs.toFloat() / day.budgetMs).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = if (day.phase == Phase.OPEN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

// --- One app's page: limit, more time, its record ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppScreen(app: Targets.App, onBack: () -> Unit) {
    val context = LocalContext.current
    val version = rememberGateVersion()
    val day = remember(version) { Gate.day(app) }
    val configured = remember(version) { Gate.limitMinutes(app) }
    val cap = remember(version) { Gate.capMinutes(app) }
    val hours = remember(version) { Gate.windowConfigured(app) }
    val openNow = remember(version) { Gate.inHours(app) }
    var editingHours by remember(app.id) { mutableStateOf(false) }
    val stats = remember(version) { Gate.stats(app) }
    var more by remember(app.id) { mutableStateOf<Int?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.width(4.dp))
                Text(app.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }

            Section("Today") {
                Text(minutes(day.usedMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text(status(day), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (day.limited) UsageBar(day)
                if (day.extraMs > 0) {
                    Text(
                        "Includes ${minutes(day.extraMs)} added today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("Daily limit") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIMIT_CHOICES.forEach { min ->
                        FilterChip(
                            selected = configured == min,
                            onClick = { Gate.setLimit(app, min) },
                            label = { Text(limitLabel(min)) },
                        )
                    }
                }
                val todayMin = (day.limitMs / 60_000).toInt()
                Text(
                    if (todayMin != configured) {
                        "Today: ${limitLabel(todayMin)}. From tomorrow: ${limitLabel(configured)}."
                    } else {
                        "When time's up, ${app.name} keeps going until a good place to stop (the end of a video, " +
                            "backing out of a post), then closes for the day. Lower limits start now; higher ones start tomorrow."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Open hours") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        hours?.let { hoursLabel(it.first, it.second) } ?: "Any time",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { editingHours = true }) { Text(if (hours == null) "Set hours" else "Change") }
                    if (hours != null) TextButton(onClick = { Gate.setWindow(app, null) }) { Text("Remove") }
                }
                Text(
                    when {
                        hours == null -> "Only allow ${app.name} during set hours."
                        day.limited -> "Your daily limit only counts inside these hours. Whichever runs out first ends the " +
                            "session at a good place to stop. Outside these hours ${app.name} stays closed."
                        else -> "When the hours end, ${app.name} keeps going until a good place to stop, then closes " +
                            "until they open again."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Hard cap") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CAP_CHOICES.forEach { min ->
                        FilterChip(
                            selected = cap == min,
                            onClick = { Gate.setCap(app, min) },
                            label = { Text(limitLabel(min)) },
                        )
                    }
                }
                Text(
                    if (cap == 0) {
                        "Off: once time's up, ${app.name} waits for a good place to stop, however long that takes."
                    } else {
                        "If no good place to stop comes within ${minutes(cap * 60_000L)} of time running out, " +
                            "${app.name} closes anyway."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (day.limited && !openNow) {
                Section("More time today") {
                    Text(
                        "${app.name} is outside its open hours, so there's no more time until ${Rules.clock(day.windowStart)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (day.limited) {
                Section("More time today") {
                    Text(
                        "Pick how much, then press and hold.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MORE_CHOICES.forEach { min ->
                            FilterChip(
                                selected = more == min,
                                onClick = { more = if (more == min) null else min },
                                label = { Text("+$min min") },
                            )
                        }
                    }
                    val pick = more
                    HoldButton(
                        label = if (pick == null) "Choose an amount" else "Hold to add $pick min to ${app.name}",
                        enabled = pick != null,
                    ) {
                        if (pick != null) {
                            Gate.addTime(app, pick)
                            more = null
                            Toast.makeText(context, "Added $pick min to ${app.name} today", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            Section("Your ${app.name} record") {
                StatTiles(stats)
            }
        }
    }

    if (editingHours) {
        HoursDialog(
            initial = hours ?: (18 * 60 to 21 * 60),
            onDismiss = { editingHours = false },
            onSave = {
                Gate.setWindow(app, it)
                editingHours = false
            },
        )
    }
}

/** Two steps: pick when the hours start, then when they end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoursDialog(initial: Pair<Int, Int>, onDismiss: () -> Unit, onSave: (Pair<Int, Int>) -> Unit) {
    var start by remember { mutableStateOf<Int?>(null) }
    val pickingEnd = start != null
    val state = rememberTimePickerState(initialHour = initial.first / 60, initialMinute = initial.first % 60)
    // Moving to the second step: show the end time.
    LaunchedEffect(pickingEnd) {
        if (pickingEnd) {
            state.hour = initial.second / 60
            state.minute = initial.second % 60
        }
    }
    val picked = state.hour * 60 + state.minute
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pickingEnd) "Open until" else "Open from") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                if (pickingEnd && picked == start) {
                    Text("Pick a different time from the start.", color = MaterialTheme.colorScheme.error)
                } else if (pickingEnd && picked < start!!) {
                    Text("Runs past midnight.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !pickingEnd || picked != start,
                onClick = { if (pickingEnd) onSave(start!! to picked) else start = picked },
            ) { Text(if (pickingEnd) "Save" else "Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A button that only fires after being held down for [holdMs]. Letting go early drains it back. */
@Composable
private fun HoldButton(label: String, enabled: Boolean, holdMs: Int = 2500, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) colors.secondaryContainer else colors.surfaceVariant)
            .semantics { role = Role.Button }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val fill = scope.launch {
                        val left = ((1f - progress.value) * holdMs).toInt().coerceAtLeast(1)
                        progress.animateTo(1f, tween(left, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDone()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (fill.isActive) {
                        fill.cancel()
                        scope.launch { progress.animateTo(0f, tween(300)) }
                    }
                })
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(colors.primary.copy(alpha = 0.35f)))
        Text(
            label,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (enabled) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
    }
}
