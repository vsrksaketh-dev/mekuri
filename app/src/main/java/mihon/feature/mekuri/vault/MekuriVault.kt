package mihon.feature.mekuri.vault

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import mihon.feature.mekuri.MekuriPrefs
import mihon.feature.mekuri.MekuriPrefs.AutoLock
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Mekuri: the vault. Shelves (categories) and sources put in the vault stay out of the library, Browse, global
 * search, Updates and History until it's unlocked with the phone's fingerprint/PIN. Vault sources are always
 * incognito. When it locks again is the user's choice ([MekuriPrefs.autoLock]): on leaving the app (default), on
 * leaving the vault shelves, after a few minutes, or only by hand.
 *
 * What's hidden is stored as preferences (so it travels with backups); the unlocked state is memory only.
 */
object MekuriVault {

    data class Filter(
        val unlocked: Boolean = false,
        val categories: Set<Long> = emptySet(),
        val sources: Set<Long> = emptySet(),
        /** Library manga whose every category is in the vault. */
        val manga: Set<Long> = emptySet(),
    ) {
        fun hidesCategory(id: Long): Boolean = !unlocked && id in categories
        fun hidesSource(id: Long): Boolean = !unlocked && id in sources
        fun hides(mangaId: Long, sourceId: Long): Boolean =
            !unlocked && (sourceId in sources || mangaId in manga)
    }

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val hiddenManga = MutableStateFlow<Set<Long>>(emptySet())
    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private var scope: CoroutineScope? = null
    private var timer: Job? = null
    private val mode: AutoLock get() = if (MekuriPrefs.ready) {
        AutoLock.of(
            MekuriPrefs.autoLock.get(),
        )
    } else {
        AutoLock.LeaveApp
    }

    private var categoriesPref: Preference<Set<String>>? = null
    private var sourcesPref: Preference<Set<String>>? = null

    fun init(preferenceStore: PreferenceStore, scope: CoroutineScope) {
        val categories = preferenceStore.getStringSet("mekuri_vault_categories", emptySet())
        val sources = preferenceStore.getStringSet("mekuri_vault_sources", emptySet())
        categoriesPref = categories
        sourcesPref = sources
        this.scope = scope
        combine(_unlocked, categories.changes(), sources.changes(), hiddenManga) { unlocked, c, s, m ->
            Filter(
                unlocked = unlocked,
                categories = c.mapNotNull(String::toLongOrNull).toSet(),
                sources = s.mapNotNull(String::toLongOrNull).toSet(),
                manga = m,
            )
        }
            .onEach { _filter.value = it }
            .launchIn(scope)

        // Leaving the app locks it, except in "only when I lock it" mode
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && mode != AutoLock.Manual) lock()
            },
        )
    }

    fun unlock() {
        _unlocked.value = true
        timer?.cancel()
        val minutes = mode.minutes
        if (minutes > 0) {
            timer = scope?.launch {
                delay(minutes * 60_000L)
                lock()
            }
        }
    }

    fun lock() {
        timer?.cancel()
        timer = null
        _unlocked.value = false
    }

    /** Called when the user leaves the vault shelves (back to the deck, or another tab). */
    fun onLeftVaultShelves() {
        if (_unlocked.value && mode == AutoLock.LeaveVault) lock()
    }

    fun isSourceInVault(sourceId: Long): Boolean = sourceId in _filter.value.sources

    /** Vault shelves straight from preferences (for code that may run before the library has loaded). */
    fun storedCategories(): Set<Long> = categoriesPref?.get()?.mapNotNull(String::toLongOrNull)?.toSet().orEmpty()

    fun storedSources(): Set<Long> = sourcesPref?.get()?.mapNotNull(String::toLongOrNull)?.toSet().orEmpty()

    /** True for anything that lives in the vault, locked or not (for notifications and other places that outlive it). */
    fun keepsPrivate(mangaId: Long, sourceId: Long): Boolean =
        _filter.value.let { sourceId in it.sources || mangaId in it.manga }

    fun setCategoryInVault(categoryId: Long, inVault: Boolean) {
        val pref = categoriesPref ?: return
        val id = categoryId.toString()
        pref.set(if (inVault) pref.get() + id else pref.get() - id)
    }

    fun setSourceInVault(sourceId: Long, inVault: Boolean) {
        val pref = sourcesPref ?: return
        val id = sourceId.toString()
        pref.set(if (inVault) pref.get() + id else pref.get() - id)
    }

    /** Called by the library with the manga that only live on vault shelves. */
    fun updateHiddenManga(ids: Set<Long>) {
        if (hiddenManga.value != ids) hiddenManga.value = ids
    }
}
