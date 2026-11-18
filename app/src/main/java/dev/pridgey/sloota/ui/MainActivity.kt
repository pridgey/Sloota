package dev.pridgey.sloota.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.pridgey.sloota.BuildConfig
import dev.pridgey.sloota.Targets
import dev.pridgey.sloota.log.EventLog
import dev.pridgey.sloota.update.UpdateBanner
import dev.pridgey.sloota.update.UpdateRow
import dev.pridgey.sloota.update.checkForUpdatesIfDue
import dev.pridgey.sloota.update.rememberUpdateBannerVisible
import dev.pridgey.sloota.watch.WatchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkForUpdatesIfDue(this)
        setContent { SlootaTheme { Home() } }
    }
}

@Composable
private fun SlootaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private class Access(val watching: Boolean, val playback: Boolean)

private fun readAccess(context: Context): Access {
    val me = ComponentName(context, WatchService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val watching = enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    val playback = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    return Access(watching, playback)
}

private fun isInstalled(context: Context, pkg: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

@Composable
private fun Home() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(readAccess(context)) }
    var stats by remember { mutableStateOf<EventLog.Stats?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val showUpdate = rememberUpdateBannerVisible()

    LifecycleResumeEffect(Unit) {
        access = readAccess(context)
        refresh++
        onPauseOrDispose { }
    }
    LaunchedEffect(refresh) { stats = withContext(Dispatchers.IO) { EventLog.stats() } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column {
                Text("Sloota", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Logger. Use your apps normally for a day or two; this records what they report so the stopping-point rules can be built from real data.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showUpdate) UpdateBanner()

            Section("Access") {
                AccessRow(
                    title = "Watch apps",
                    detail = "Accessibility service. Sees screens, scrolls and taps in the apps below.",
                    on = access.watching,
                    action = "Turn on",
                ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                if (!access.watching) {
                    Hint(
                        "Switch greyed out? Android blocks sideloaded apps from accessibility until you allow it: " +
                            "App info → ⋮ → Allow restricted settings, then try again.",
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
                AccessRow(
                    title = "Video playback",
                    detail = "Notification access. Only used to read play, pause and video length.",
                    on = access.playback,
                    action = "Turn on",
                ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }

            Section("Watching") {
                Targets.apps.forEach { app ->
                    val installed = app.packages.any { isInstalled(context, it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(app.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (installed) "installed" else "not installed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("Mark stopping points") {
                Text(
                    "Add the “Mark stop” tile to Quick Settings. Tap it whenever you're at a spot where stopping would feel natural. " +
                        "Those marks are what the rules get checked against.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Log") {
                val s = stats
                if (s == null) {
                    Text("…", style = MaterialTheme.typography.bodyLarge)
                } else {
                    StatRow("Events today", "%,d".format(s.todayEvents))
                    StatRow("Today's file", Formatter.formatShortFileSize(context, s.todayBytes))
                    StatRow("All logs", "${Formatter.formatShortFileSize(context, s.totalBytes)} · ${s.days} day${if (s.days == 1) "" else "s"}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) { EventLog.exportZip(context) }
                            if (zip == null) {
                                Toast.makeText(context, "No logs yet", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Share Sloota logs"))
                        }
                    }) { Text("Share logs") }
                    OutlinedButton(onClick = { confirmClear = true }) { Text("Clear") }
                }
                Text(
                    "Also at ${EventLog.directory.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("About") {
                StatRow("Version", BuildConfig.VERSION_NAME)
                // UpdateRow is a ListItem with its own 16dp insets; let it bleed so its text lines up with the page.
                Box(Modifier.bleed(16.dp)) { UpdateRow() }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all logs?") },
            text = { Text("This deletes every day's log from the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    EventLog.clear()
                    refresh++
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun AccessRow(title: String, detail: String, on: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (on) {
            Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun Hint(text: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 22.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick) { Text("Open App info") }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

/** Widens content by [x] on each side, past its parent's padding. */
private fun Modifier.bleed(x: Dp) = layout { measurable, constraints ->
    val extra = x.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra),
    )
    layout(placeable.width - 2 * extra, placeable.height) { placeable.place(-extra, 0) }
}
