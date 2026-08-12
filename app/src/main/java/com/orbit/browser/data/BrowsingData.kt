package com.orbit.browser.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage

/** Çerezler, yerel depolama, geçmiş ve önbellek dosyaları. */
object BrowsingData {

    fun clear(context: Context, clearHistory: Boolean = true) {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        if (clearHistory) BrowserDb.get(context).clearHistory()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
