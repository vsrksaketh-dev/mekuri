package mihon.feature.mekuri

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Mekuri: one-time defaults applied on startup, separate from upstream's versionCode-based migrations
 * (Mekuri keeps upstream's versionCode). Each step runs once; bump [CURRENT] and add a step for new ones.
 */
object MekuriSetup {

    private const val CURRENT = 2

    fun run(
        preferenceStore: PreferenceStore,
        uiPreferences: UiPreferences,
        sourcePreferences: SourcePreferences,
        readerPreferences: ReaderPreferences,
        libraryPreferences: LibraryPreferences,
        scheduleLibraryUpdates: () -> Unit,
    ) {
        val done = preferenceStore.getInt(Preference.appStateKey("mekuri_setup_version"), 0)
        val from = done.get()
        if (from >= CURRENT) return

        if (from < 1) {
            // Mekuri theme only; dark unless the user already picked a mode
            uiPreferences.appTheme.set(AppTheme.DEFAULT)
            if (!uiPreferences.themeMode.isSet()) uiPreferences.themeMode.set(ThemeMode.DARK)
            // English-only sources and extensions (no multi-language "all" sources)
            sourcePreferences.enabledLanguages.set(setOf("en"))
            // Long strip is the only mode, so the "Long strip" toast on every chapter is noise
            readerPreferences.showReadingMode.set(false)
        }

        if (from < 2) {
            // New chapters were being missed: Mihon skips titles with unread chapters, titles not started and
            // titles "outside their release window", and never checks on its own by default. Check every title
            // (completed ones still skipped), twice a day.
            libraryPreferences.autoUpdateMangaRestrictions.set(
                libraryPreferences.autoUpdateMangaRestrictions.get().filter {
                    it == LibraryPreferences.MANGA_NON_COMPLETED
                }.toSet(),
            )
            if (libraryPreferences.autoUpdateInterval.get() == 0) {
                libraryPreferences.autoUpdateInterval.set(12)
                scheduleLibraryUpdates()
            }
        }

        done.set(CURRENT)
    }
}
