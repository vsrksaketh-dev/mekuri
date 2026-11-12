package mihon.feature.mekuri

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Mekuri: personal options that aren't Mihon settings (vault auto-lock, colours, shelf theme, refresh on open).
 * Stored as normal preferences, so they travel with backups.
 */
object MekuriPrefs {

    /** When the unlocked vault locks itself again. */
    enum class AutoLock(val key: String, val title: String, val minutes: Int = 0) {
        LeaveApp("leave_app", "When I leave the app"),
        LeaveVault("leave_vault", "When I leave the vault shelves"),
        OneMinute("1m", "After 1 minute", 1),
        FiveMinutes("5m", "After 5 minutes", 5),
        FifteenMinutes("15m", "After 15 minutes", 15),
        Manual("manual", "Only when I lock it"),
        ;

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key } ?: LeaveApp
        }
    }

    /** Colour sets for the shelf cards. [Covers] takes each shelf's colour from its covers. */
    enum class ShelfTheme(val key: String, val title: String, val colors: List<Long>) {
        Covers("covers", "From covers", emptyList()),
        Mono("mono", "Monochrome", listOf(0xFF2A2A2E, 0xFF3A3A40, 0xFF4A4A52, 0xFF33333A, 0xFF5A5A63)),
        Sumi("sumi", "Sumi ink", listOf(0xFF1E1C22, 0xFF8E2C25, 0xFF3B3833, 0xFF5B4A3A, 0xFF2D2A33)),
        Twilight("twilight", "Twilight", listOf(0xFF3B2C8F, 0xFF5B3FD9, 0xFF2F3FA0, 0xFF6A3FB0, 0xFF24326E)),
        Sunset("sunset", "Sunset", listOf(0xFFB0532B, 0xFFC2477A, 0xFF8C2F3F, 0xFFC4762B, 0xFF7A2E4F)),
        Ocean("ocean", "Ocean", listOf(0xFF1F4E8C, 0xFF1F6B7A, 0xFF2F6FD6, 0xFF1A3F66, 0xFF3E7C8C)),
        Forest("forest", "Forest", listOf(0xFF1F6B57, 0xFF3A5A2A, 0xFF2E7D5B, 0xFF4E6B35, 0xFF1C4A3F)),
        Pastel("pastel", "Pastel", listOf(0xFF8C7BC9, 0xFFC98BA7, 0xFF7BA9C9, 0xFF8FB38A, 0xFFC9A97B)),
        ;

        fun colorFor(index: Int): Color? = colors.takeIf { it.isNotEmpty() }?.let { Color(it[index % it.size]) }

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key } ?: Covers
        }
    }

    /** Ready-made accent colours; 0 means "use the theme's accent". */
    val swatches: List<Long> = listOf(
        0xFFFF8A6B, 0xFFC8372D, 0xFFFFC14D, 0xFF8BD17C, 0xFF4CC2B5,
        0xFF5EA8FF, 0xFFB9A8FF, 0xFFFF7EB6, 0xFFF4EFE6, 0xFF9E9E9E,
    )

    lateinit var autoLock: Preference<String>
        private set
    lateinit var progressColor: Preference<Int>
        private set
    lateinit var bookmarkColor: Preference<Int>
        private set
    lateinit var shelfTheme: Preference<String>
        private set
    lateinit var refreshOnOpen: Preference<Boolean>
        private set

    private val _progressArgb = MutableStateFlow(0)
    val progressArgb: StateFlow<Int> = _progressArgb.asStateFlow()
    private val _bookmarkArgb = MutableStateFlow(0)
    val bookmarkArgb: StateFlow<Int> = _bookmarkArgb.asStateFlow()
    private val _theme = MutableStateFlow(ShelfTheme.Covers)
    val theme: StateFlow<ShelfTheme> = _theme.asStateFlow()

    fun init(preferenceStore: PreferenceStore, scope: CoroutineScope) {
        autoLock = preferenceStore.getString("mekuri_vault_autolock", AutoLock.LeaveApp.key)
        progressColor = preferenceStore.getInt("mekuri_progress_color", 0)
        bookmarkColor = preferenceStore.getInt("mekuri_bookmark_color", 0)
        shelfTheme = preferenceStore.getString("mekuri_shelf_theme", ShelfTheme.Covers.key)
        refreshOnOpen = preferenceStore.getBoolean("mekuri_refresh_on_open", true)
        progressColor.changes().onEach { _progressArgb.value = it }.launchIn(scope)
        bookmarkColor.changes().onEach { _bookmarkArgb.value = it }.launchIn(scope)
        shelfTheme.changes().onEach { _theme.value = ShelfTheme.of(it) }.launchIn(scope)
    }

    /** True once [init] ran (widgets and workers can run before the app does). */
    val ready: Boolean get() = ::refreshOnOpen.isInitialized
}
