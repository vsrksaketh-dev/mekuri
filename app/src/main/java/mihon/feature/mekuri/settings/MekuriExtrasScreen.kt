package mihon.feature.mekuri.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.feature.mekuri.MekuriPrefs
import mihon.feature.mekuri.MekuriPrefs.AutoLock
import mihon.feature.mekuri.MekuriPrefs.ShelfTheme
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.util.collectAsState

/**
 * Mekuri: the Mekuri-only options in one place: vault auto-lock, colours (reading progress, bookmarks, shelf
 * theme) and how new chapters are checked for.
 */
class MekuriExtrasScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val library = context.appGraph.libraryPreferences
        val scope = rememberCoroutineScope()

        val autoLock by MekuriPrefs.autoLock.collectAsState()
        val theme by MekuriPrefs.shelfTheme.collectAsState()
        val refreshOnOpen by MekuriPrefs.refreshOnOpen.collectAsState()
        val interval by library.autoUpdateInterval.collectAsState()
        val device by library.autoUpdateDeviceRestrictions.collectAsState()
        val manga by library.autoUpdateMangaRestrictions.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(title = "Mekuri", navigateUp = navigator::pop, scrollBehavior = scrollBehavior)
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                // Vault
                item(key = "vault") {
                    Section("Vault auto-lock", "When an unlocked vault locks itself again") {
                        AutoLock.entries.forEach { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { MekuriPrefs.autoLock.set(mode.key) }
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(
                                    selected = AutoLock.of(autoLock) == mode,
                                    onClick = { MekuriPrefs.autoLock.set(mode.key) },
                                )
                                Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                // Colours
                item(key = "progress") {
                    Section("Reading progress colour", "The line across the top of the reader and Continue reading") {
                        ColorPicker(MekuriPrefs.progressColor)
                    }
                }
                item(key = "bookmark") {
                    Section("Bookmark colour", "The bookmark mark in chapter lists") {
                        ColorPicker(MekuriPrefs.bookmarkColor)
                    }
                }
                item(key = "theme") {
                    Section("Shelf theme", "Colours for the shelf cards (a colour pinned to one shelf still wins)") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ShelfTheme.entries.forEach { option ->
                                ThemeTile(option, selected = ShelfTheme.of(theme) == option) {
                                    MekuriPrefs.shelfTheme.set(option.key)
                                }
                            }
                        }
                    }
                }

                // Updates
                item(key = "updates") {
                    Section("New chapters", "How Mekuri checks your library for new chapters") {
                        Text("Check automatically", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Off", 6 to "6 h", 12 to "12 h", 24 to "Daily", 48 to "2 days", 168 to "Weekly")
                                .forEach { (hours, label) ->
                                    FilterChip(
                                        selected = interval == hours,
                                        onClick = {
                                            library.autoUpdateInterval.set(hours)
                                            LibraryUpdateWorker.setupTask(context, hours)
                                        },
                                        label = { Text(label) },
                                    )
                                }
                        }
                        Toggle(
                            title = "Only on Wi-Fi",
                            checked = LibraryPreferences.DEVICE_ONLY_ON_WIFI in device,
                        ) {
                            library.autoUpdateDeviceRestrictions.toggle(LibraryPreferences.DEVICE_ONLY_ON_WIFI, it)
                            LibraryUpdateWorker.setupTask(context)
                        }
                        val skips = setOf(
                            LibraryPreferences.MANGA_HAS_UNREAD,
                            LibraryPreferences.MANGA_NON_READ,
                            LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
                        )
                        Toggle(
                            title = "Check every title",
                            subtitle = "Also titles with unread chapters, not started yet, or \"not due\" a release",
                            checked = manga.none { it in skips },
                        ) { every ->
                            library.autoUpdateMangaRestrictions.set(
                                if (every) manga - skips else manga + LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
                            )
                        }
                        Toggle(
                            title = "Skip completed titles",
                            checked = LibraryPreferences.MANGA_NON_COMPLETED in manga,
                        ) { library.autoUpdateMangaRestrictions.toggle(LibraryPreferences.MANGA_NON_COMPLETED, it) }
                        Toggle(
                            title = "Check a title when you open it",
                            subtitle = "Opening a library title looks for new chapters straight away",
                            checked = refreshOnOpen,
                        ) { MekuriPrefs.refreshOnOpen.set(it) }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val started = LibraryUpdateWorker.startNow(context.workManager)
                                    context.toast(if (started) "Checking your library…" else "Already checking")
                                }
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        ) { Text("Check now") }
                    }
                }
            }
        }
    }
}

private fun Preference<Set<String>>.toggle(value: String, on: Boolean) {
    set(if (on) get() + value else get() - value)
}

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Swatches plus a custom colour (hue and brightness). 0 = the theme's own accent. */
@Composable
private fun ColorPicker(pref: Preference<Int>) {
    val current by pref.collectAsState()
    var custom by remember { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Swatch(
            color = MaterialTheme.colorScheme.secondary,
            selected = current == 0,
            label = "Theme accent",
        ) { pref.set(0) }
        MekuriPrefs.swatches.forEach { argb ->
            val color = Color(argb)
            Swatch(color = color, selected = current == color.toArgb(), label = "Colour") { pref.set(color.toArgb()) }
        }
        TextButton(onClick = { custom = !custom }) { Text(if (custom) "Done" else "Custom…") }
    }
    if (custom) {
        val start = remember {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(if (current != 0) current else 0xFFFF8A6B.toInt(), hsv)
            hsv
        }
        var hue by remember { mutableFloatStateOf(start[0]) }
        var light by remember { mutableFloatStateOf(start[2]) }
        fun apply() = pref.set(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, light)))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.65f, 0.95f))) },
                    ),
                ),
        )
        Slider(value = hue, onValueChange = {
            hue = it
            apply()
        }, valueRange = 0f..359f)
        Text("Brightness", style = MaterialTheme.typography.labelMedium)
        Slider(value = light, onValueChange = {
            light = it
            apply()
        }, valueRange = 0.35f..1f)
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.15f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        if (selected) {
            Icon(MaterialSymbols.Rounded.Check, contentDescription = null, tint = Color.Black.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ThemeTile(theme: ShelfTheme, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            val colors = theme.colors.take(3).ifEmpty { listOf(0xFF7A6BC6, 0xFFC4762B, 0xFF1F8A70) }
            colors.forEach {
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(it)),
                )
            }
        }
        Text(theme.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp), maxLines = 1)
    }
}
