package mihon.feature.mekuri.cloudflare

import android.content.Context
import android.content.Intent
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

/**
 * Mekuri: one-tap Cloudflare check. Mihon already passes Cloudflare's automatic checks in the background; some
 * sites show a checkbox that needs a person. Instead of open site → tick → back → refresh, a refresh that hits it
 * offers "Verify": the site opens, you tick the box, the page closes by itself once Cloudflare lets you through,
 * and the title refreshes. The tick is always yours; nothing here clicks it.
 */
object CloudflareVerify {

    const val EXTRA_VERIFY = "mekuri_cloudflare_verify"

    private val _verified = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** Source ids that just passed a check, so open screens can retry. */
    val verified: SharedFlow<Long> = _verified.asSharedFlow()

    /** True when [error] (or its cause) is Cloudflare's "needs a person" check. */
    fun isChallenge(context: Context, error: Throwable): Boolean {
        val failure = context.stringResource(MR.strings.information_cloudflare_bypass_failure)
        return generateSequence(error) { it.cause }.take(8).any {
            it.message == failure || it.javaClass.simpleName == "CloudflareBypassException"
        }
    }

    suspend fun intentFor(context: Context, sourceManager: SourceManager, manga: Manga): Intent? {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null
        val url = try {
            source.getMangaUrl(manga.toSManga())
        } catch (_: Exception) {
            source.baseUrl
        }
        return WebViewActivity.newIntent(context, url, source.id, manga.title).apply {
            putExtra(EXTRA_VERIFY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Waits until the site hands out a new Cloudflare clearance cookie (the check was passed). */
    suspend fun awaitClearance(cookieJar: AndroidCookieJar, url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        fun clearance() = cookieJar.get(httpUrl).firstOrNull { it.name == "cf_clearance" }?.value
        val before = clearance()
        while (true) {
            delay(700)
            val now = clearance()
            if (!now.isNullOrBlank() && now != before) return
        }
    }

    fun markVerified(sourceId: Long) {
        _verified.tryEmit(sourceId)
    }
}
