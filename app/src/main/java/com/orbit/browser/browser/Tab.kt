package com.orbit.browser.browser

import android.os.Bundle
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bir sekme. Düşük bellekli cihazlarda aynı anda yalnızca birkaç [webView]
 * canlı tutulur; geri kalanı [savedState] içinde saklanır ve gerektiğinde
 * yeniden canlandırılır.
 */
class Tab(val id: Long, val incognito: Boolean) {

    var webView: OrbitWebView? = null
    var savedState: Bundle? = null

    /** Bu sekmenin WebView'ine bağlı kozmetik köprüsü; WebView ile birlikte doğar ve ölür. */
    var bridge: CosmeticBridge? = null

    /** Henüz WebView oluşturulmadan yüklenmesi istenen adres. */
    var pendingUrl: String? = null

    /** Arka plan iş parçacığından okunur (istek filtreleme). */
    @Volatile
    var pageUrl: String = ""

    @Volatile
    var allowlisted: Boolean = false

    var title: String = ""
    var progress: Int = 100
    var desktopMode: Boolean = false

    /** Başlangıç ekranı gösteriliyor; WebView boş bir belge tutar. */
    var isHome: Boolean = false

    private val blocked = AtomicInteger(0)

    var blockedOnPage: Int
        get() = blocked.get()
        set(value) = blocked.set(value)

    fun countBlocked(): Int = blocked.incrementAndGet()

    val isLive: Boolean get() = webView != null

    /** Görüntülenecek adres: canlı WebView yoksa saklanan bilgi kullanılır. */
    fun displayUrl(): String = pendingUrl ?: pageUrl
}
