package mihon.feature.mekuri.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.feature.mekuri.MekuriPrefs
import mihon.feature.mekuri.shelf.fallbackColor
import mihon.feature.mekuri.vault.MekuriVault
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Mekuri: the richer home-screen widgets.
 * - Shelves: your shelves as coloured cards (shelf theme colours, a cover, counts, a "new" mark), like the library.
 * - New chapters: the latest unread chapters from updates, with covers; tap one to open the title.
 *
 * Vault shelves, vault sources and their manga never appear (widgets always treat the vault as locked), and both show
 * a plain "locked" card while the app lock is on.
 */
object MekuriWidgets {

    /** Shelf (category) the library should open, set when a shelf on the widget is tapped. */
    val pendingShelf = MutableStateFlow<Long?>(null)

    const val EXTRA_SHELF = "mekuri_open_shelf"

    private val widgets: List<GlanceAppWidget>
        get() = listOf(ShelvesWidget(), LatestUpdatesWidget(), ContinueReadingWidget())

    /** Keeps the widgets current: redraws them a moment after the library, updates or history change. */
    @OptIn(FlowPreview::class)
    fun init(context: Context, scope: CoroutineScope) {
        val graph = context.appGraph
        combine(
            graph.getLibraryManga.subscribe(),
            graph.getUpdates.subscribe(read = false, after = Clock.System.now() - 30.days),
            graph.getHistory.subscribe(""),
            graph.securityPreferences.useAuthenticator.changes(),
            MekuriPrefs.theme,
        ) { _, _, _, _, _ -> Unit }
            .debounce(2_000)
            .onEach {
                val manager = GlanceAppWidgetManager(context)
                widgets.forEach { widget ->
                    try {
                        if (manager.getGlanceIds(widget.javaClass).isNotEmpty()) widget.updateAll(context)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to update Mekuri widget" }
                    }
                }
            }
            .launchIn(scope)
    }

    internal val Background = Color(0xFF15131D)
    internal val Ink = ColorProvider(Color(0xFFF3F1FA))
    internal val Muted = ColorProvider(Color(0xFFA39DB8))
    internal val Coral = Color(0xFFFF8A6B)

    internal fun openApp(context: Context, block: Intent.() -> Unit = {}): Intent =
        Intent(context, Class.forName(Constants.MAIN_ACTIVITY)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            block()
        }

    internal suspend fun loadCover(context: Context, data: Any, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(data)
                .allowHardware(false)
                .size(width, height)
                .build()
            (context.imageLoader.execute(request) as? SuccessResult)?.image?.asDrawable(context.resources)?.toBitmap()
        }

    @Composable
    internal fun Frame(context: Context, open: Intent, content: @Composable () -> Unit) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .background(Background)
                .clickable(actionStartActivity(open))
                .padding(12.dp),
        ) { content() }
    }

    @Composable
    internal fun Header(title: String, trailing: String? = null) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                style = TextStyle(color = ColorProvider(Coral), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (trailing != null) Text(trailing, style = TextStyle(color = Muted, fontSize = 11.sp))
        }
    }

    @Composable
    internal fun Locked() {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Mekuri is locked", style = TextStyle(color = Muted, fontSize = 14.sp))
        }
    }
}

/** Shelves widget: up to six of your shelves as coloured cards. */
class ShelvesWidget : GlanceAppWidget() {

    private class Tile(val category: Category, val count: Int, val unread: Long, val isNew: Boolean, val cover: Bitmap?)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.appGraph
        val locked = graph.securityPreferences.useAuthenticator.get()
        val tiles = if (locked) emptyList() else loadTiles(context)
        val theme = if (MekuriPrefs.ready) MekuriPrefs.ShelfTheme.of(MekuriPrefs.shelfTheme.get()) else null
        provideContent {
            MekuriWidgets.Frame(context, MekuriWidgets.openApp(context) { action = Constants.SHORTCUT_LIBRARY }) {
                if (locked) {
                    MekuriWidgets.Locked()
                    return@Frame
                }
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    MekuriWidgets.Header("Your shelves", "${tiles.size} shown")
                    Spacer(GlanceModifier.height(8.dp))
                    if (tiles.isEmpty()) {
                        Text("Make a shelf in Mekuri to see it here", style = TextStyle(MekuriWidgets.Muted))
                    }
                    tiles.chunked(3).forEach { row ->
                        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                            row.forEachIndexed { i, tile ->
                                if (i > 0) Spacer(GlanceModifier.width(8.dp))
                                val index = tiles.indexOf(tile)
                                val color = theme?.colorFor(index) ?: fallbackColor(tile.category.id)
                                ShelfTile(context, tile, color)
                            }
                            repeat(3 - row.size) {
                                Spacer(GlanceModifier.width(8.dp))
                                Box(modifier = GlanceModifier.defaultWeight()) {}
                            }
                        }
                        Spacer(GlanceModifier.height(8.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.ShelfTile(context: Context, tile: Tile, color: Color) {
        val open = MekuriWidgets.openApp(context) {
            action = Constants.SHORTCUT_LIBRARY
            putExtra(MekuriWidgets.EXTRA_SHELF, tile.category.id)
            // A distinct intent per shelf, so each tile keeps its own tap target
            data = android.net.Uri.parse("mekuri://shelf/${tile.category.id}")
        }
        Box(
            modifier = GlanceModifier
                .defaultWeight()
                .fillMaxHeight()
                .cornerRadius(16.dp)
                .background(color)
                .clickable(actionStartActivity(open))
                .padding(8.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    if (tile.isNew) {
                        Text(
                            "NEW",
                            style = TextStyle(
                                color = ColorProvider(MekuriWidgets.Coral),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Text(
                        tile.category.name,
                        maxLines = 1,
                        style = TextStyle(ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    )
                    Text(
                        if (tile.unread > 0) "${tile.count} · ${tile.unread} new" else "${tile.count} titles",
                        maxLines = 1,
                        style = TextStyle(ColorProvider(Color.White.copy(alpha = 0.8f)), fontSize = 10.sp),
                    )
                }
                if (tile.cover != null) {
                    Image(
                        provider = ImageProvider(tile.cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.size(width = 28.dp, height = 42.dp).cornerRadius(6.dp),
                    )
                }
            }
        }
    }

    private suspend fun loadTiles(context: Context): List<Tile> {
        val graph = context.appGraph
        val vaultCategories = MekuriVault.storedCategories()
        val vaultSources = MekuriVault.storedSources()
        val library = graph.getLibraryManga.await().filterNot { it.manga.source in vaultSources }
        val weekAgo = Clock.System.now() - 7.days
        return graph.getCategories.await()
            .filterNot { it.id in vaultCategories }
            .mapNotNull { category ->
                val items = library.filter { category.id in it.categories }
                if (category.isSystemCategory && items.isEmpty()) return@mapNotNull null
                category to items
            }
            .take(6)
            .map { (category, items) ->
                val latest = items.maxByOrNull { it.lastRead ?: Instant.DISTANT_PAST }
                Tile(
                    category = category,
                    count = items.size,
                    unread = items.sumOf { it.unreadCount },
                    isNew = items.any { it.unreadCount > 0 && (it.chapterFetchedAt ?: Instant.DISTANT_PAST) > weekAgo },
                    cover = latest?.let { MekuriWidgets.loadCover(context, it.manga, 84, 126) },
                )
            }
    }
}

/** New chapters widget: the latest unread chapters, newest first. */
class LatestUpdatesWidget : GlanceAppWidget() {

    private class Row(val update: UpdatesWithRelations, val cover: Bitmap?)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.appGraph
        val locked = graph.securityPreferences.useAuthenticator.get()
        val rows = if (locked) emptyList() else loadRows(context)
        provideContent {
            MekuriWidgets.Frame(context, MekuriWidgets.openApp(context) { action = Constants.SHORTCUT_UPDATES }) {
                if (locked) {
                    MekuriWidgets.Locked()
                    return@Frame
                }
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    MekuriWidgets.Header("New chapters", if (rows.isEmpty()) null else "${rows.size} latest")
                    Spacer(GlanceModifier.height(8.dp))
                    if (rows.isEmpty()) {
                        Text("You're all caught up", style = TextStyle(MekuriWidgets.Muted, fontSize = 14.sp))
                    }
                    rows.forEach { row -> UpdateRow(context, row) }
                }
            }
        }
    }

    @Composable
    private fun UpdateRow(context: Context, row: Row) {
        val open = MekuriWidgets.openApp(context) {
            action = Constants.SHORTCUT_MANGA
            putExtra(Constants.MANGA_EXTRA, row.update.mangaId)
            data = android.net.Uri.parse("mekuri://manga/${row.update.mangaId}")
        }
        androidx.glance.layout.Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity(open))
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.cover != null) {
                Image(
                    provider = ImageProvider(row.cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.size(width = 30.dp, height = 44.dp).cornerRadius(6.dp),
                )
            } else {
                Box(
                    modifier = GlanceModifier
                        .size(width = 30.dp, height = 44.dp)
                        .cornerRadius(6.dp)
                        .background(Color(0xFF2A2638)),
                ) {}
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    row.update.mangaTitle,
                    maxLines = 1,
                    style = TextStyle(MekuriWidgets.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    "${row.update.chapterName} · ${ago(row.update.dateFetch)}",
                    maxLines = 1,
                    style = TextStyle(MekuriWidgets.Muted, fontSize = 11.sp),
                )
            }
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .cornerRadius(4.dp)
                    .background(MekuriWidgets.Coral),
            ) {}
        }
    }

    private suspend fun loadRows(context: Context): List<Row> {
        val graph = context.appGraph
        val vaultCategories = MekuriVault.storedCategories()
        val vaultSources = MekuriVault.storedSources()
        val hiddenManga = if (vaultCategories.isEmpty()) {
            emptySet()
        } else {
            graph.getLibraryManga.await()
                .filter { it.categories.isNotEmpty() && it.categories.all { c -> c in vaultCategories } }
                .map { it.id }
                .toSet()
        }
        return graph.getUpdates.await(read = false, after = Clock.System.now() - 30.days)
            .asSequence()
            .filterNot { it.sourceId in vaultSources || it.mangaId in hiddenManga }
            .sortedByDescending { it.dateFetch }
            // One row per title: its newest chapter
            .distinctBy { it.mangaId }
            .take(4)
            .toList()
            .map { Row(it, MekuriWidgets.loadCover(context, it.coverData, 90, 132)) }
    }

    private fun ago(instant: Instant): String {
        val d = Clock.System.now() - instant
        return when {
            d.inWholeMinutes < 60 -> "${d.inWholeMinutes.coerceAtLeast(1)} min ago"
            d.inWholeHours < 24 -> "${d.inWholeHours} h ago"
            d.inWholeDays == 1L -> "yesterday"
            else -> "${d.inWholeDays} days ago"
        }
    }
}

class ShelvesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShelvesWidget()
}

class LatestUpdatesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LatestUpdatesWidget()
}
