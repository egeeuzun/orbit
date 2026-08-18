package com.orbit.browser.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.orbit.browser.data.Prefs

/**
 * Локальный кэш популярных CDN-библиотек (турбо-пакет).
 *
 * Точные версии: кэш срабатывает только на URL, в которых зашита одна из
 * известных версий, — байт-в-байт совпадение с оригиналом, поэтому SRI-хэши
 * на сайтах не ломаются. Мисматч версий (jquery-2.x и т.п.) уходит в сеть.
 */
object LibCache {

    private class Entry(val marker: String, val asset: String, val mime: String)

    private val JS = "application/javascript"
    private val CSS = "text/css"

    private val entries = listOf(
        Entry("cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js", "jquery-3.7.1.min.js", JS),
        Entry("code.jquery.com/jquery-3.7.1.min.js", "jquery-3.7.1.min.js", JS),
        Entry("cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js", "jquery-3.7.1.min.js", JS),
        Entry("cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css", "bootstrap-5.3.3.min.css", CSS),
        Entry("cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.3/css/bootstrap.min.css", "bootstrap-5.3.3.min.css", CSS),
        Entry("cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js", "bootstrap-5.3.3.bundle.min.js", JS),
        Entry("cdnjs.cloudflare.com/ajax/libs/bootstrap/5.3.3/js/bootstrap.bundle.min.js", "bootstrap-5.3.3.bundle.min.js", JS),
        Entry("cdn.jsdelivr.net/npm/react@18.3.1/umd/react.production.min.js", "react-18.3.1.production.min.js", JS),
        Entry("cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.production.min.js", "react-18.3.1.production.min.js", JS),
        Entry("cdn.jsdelivr.net/npm/react-dom@18.3.1/umd/react-dom.production.min.js", "react-dom-18.3.1.production.min.js", JS),
        Entry("cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.production.min.js", "react-dom-18.3.1.production.min.js", JS),
        Entry("cdn.jsdelivr.net/npm/vue@3.4.38/dist/vue.global.prod.min.js", "vue-3.4.38.global.prod.min.js", JS),
        Entry("cdnjs.cloudflare.com/ajax/libs/vue/3.4.38/vue.global.prod.min.js", "vue-3.4.38.global.prod.min.js", JS),
        Entry("cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js", "lodash-4.17.21.min.js", JS),
        Entry("cdnjs.cloudflare.com/ajax/libs/lodash.js/4.17.21/lodash.min.js", "lodash-4.17.21.min.js", JS)
    )

    /** Возвращает локальную копию, если URL совпадает с известной версией
     *  и для хоста эффективно включена опция lib. */
    fun match(context: Context, prefs: Prefs, url: String): WebResourceResponse? {
        val host = Uri.parse(url).host ?: return null
        val global = prefs.turboPack && prefs.turboLibCache
        if (!prefs.effectiveFeature(host, Prefs.FEATURE_LIB, global)) return null
        val needle = url.lowercase()
        val e = entries.firstOrNull { needle.contains(it.marker) } ?: return null
        return try {
            val stream = context.assets.open("libcache/${e.asset}")
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "public, max-age=31536000, immutable"
            )
            WebResourceResponse(e.mime, "utf-8", 200, "OK", headers, stream)
        } catch (t: Throwable) {
            null
        }
    }
}
