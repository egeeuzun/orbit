package com.orbit.browser.browser

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.orbit.browser.adblock.AdblockService
import com.orbit.browser.data.Prefs

/**
 * Sekmeler ve — asıl önemlisi — canlı WebView sayısı.
 *
 * Her WebView kendi oluşturucu (renderer) belleğini tutar; 1 GB RAM'li bir
 * cihazda birkaç tanesi bile sistemi takas etmeye zorlar. Bu yüzden yalnızca
 * [Prefs.liveTabLimit] kadar sekme canlı kalır, geri kalanı durumu diske
 * değil belleğe serileştirilip yok edilir ve seçildiğinde canlandırılır.
 */
class TabManager(
    private val context: Context,
    private val container: FrameLayout,
    private val prefs: Prefs,
    private val adblock: AdblockService,
    private val clientCallbacks: OrbitWebViewClient.Callbacks,
    private val chromeHost: OrbitChromeClient.Host,
    private val onDownload: (url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, size: Long) -> Unit
) {

    private val _tabs = ArrayList<Tab>()
    val tabs: List<Tab> get() = _tabs

    /** En son kullanılan canlı sekmeler; baştaki en yenidir. */
    private val liveOrder = ArrayList<Tab>()

    private var nextId = 1L

    var current: Tab? = null
        private set

    val size: Int get() = _tabs.size

    fun newTab(url: String?, incognito: Boolean = false, select: Boolean = true): Tab {
        val tab = Tab(nextId++, incognito)
        tab.pendingUrl = url
        tab.isHome = url == null || url == Prefs.HOME_URL
        _tabs.add(tab)
        if (select) select(tab)
        return tab
    }

    /** Закрытая вкладка с готовым WebView — для WebChromeClient transport
     *  (window.open): без WebView transport вернул бы false, и WebView ушёл бы
     *  в пустой таб. WebView создаётся сразу, чтобы перехваты сработали. */
    fun newWebTab(): Tab? {
        val tab = Tab(nextId++, current?.incognito == true)
        _tabs.add(tab)
        try {
            wake(tab)
        } catch (_: Throwable) {
            return null
        }
        return tab
    }

    fun select(tab: Tab) {
        if (current === tab && (tab.isLive || idleHome(tab))) return
        current?.let { detachView(it) }
        current = tab

        // Başlangıç ekranı yerel bir görünüm. WebView'i burada kurmak, soğuk
        // açılışta WebView sağlayıcısının yüklenmesini (zayıf cihazda birkaç
        // yüz milisaniye, üstelik ana iş parçacığında) ilk kareden önce
        // yapmak demekti. Adres girilene kadar beklenir.
        if (idleHome(tab)) {
            touch(tab)
            return
        }

        val web = wake(tab)
        container.addView(web, 0)
        web.onResume()
        web.resumeTimers()
        touch(tab)
    }

    /** Henüz gerçek bir adres yüklenmemiş başlangıç sekmesi. */
    private fun idleHome(tab: Tab): Boolean =
        tab.isHome && tab.savedState == null &&
            (tab.pendingUrl == null || tab.pendingUrl == Prefs.HOME_URL)

    fun close(tab: Tab) {
        val index = _tabs.indexOf(tab)
        if (index < 0) return
        _tabs.remove(tab)
        liveOrder.remove(tab)

        // Önce geçerli sekmeyi değiştir, sonra WebView'i yok et: WebView
        // yok edildiğinde odak başka bir görünüme taşınır ve odak
        // dinleyicileri (adres çubuğu dahil) o anda eski sekmeyi okumasın.
        if (current === tab) {
            current = null
            val next = _tabs.getOrNull(index) ?: _tabs.lastOrNull()
            if (next != null) select(next) else newTab(prefs.homePage, false)
        }

        tab.webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            WebViewFactory.destroy(it)
        }
        release(tab)
        tab.savedState = null
    }

    fun closeAll() {
        for (t in ArrayList(_tabs)) {
            t.webView?.let { WebViewFactory.destroy(it) }
            release(t)
            t.savedState = null
        }
        _tabs.clear()
        liveOrder.clear()
        current = null
    }

    /** Sekmeyi görünümden ayırır ama listede tutar. */
    private fun detachView(tab: Tab) {
        val web = tab.webView ?: return
        web.onPause()
        web.pauseTimers()
        (web.parent as? ViewGroup)?.removeView(web)
    }

    private fun wake(tab: Tab): WebView {
        tab.webView?.let { return it }

        // Köprü sekmeye özel: genel kozmetik CSS'i eşzamansız hesaplanıp
        // *doğru* WebView'e geri itiliyor.
        val bridge = CosmeticBridge(adblock) { prefs.adBlockEnabled && prefs.cosmeticFiltering }
        val web = WebViewFactory.create(context, prefs, bridge, tab.incognito)
        bridge.attach(web)
        tab.bridge = bridge
        web.webViewClient = OrbitWebViewClient(context, tab, adblock, prefs, clientCallbacks)
        web.webChromeClient = OrbitChromeClient(tab, chromeHost)
        web.setDownloadListener { url, ua, disposition, mime, size ->
            onDownload(url, ua, disposition, mime, size)
        }
        // Sekme uyandığında masaüstü tercihi korunmalı.
        if (tab.desktopMode) WebViewFactory.applyDesktopMode(web, true)
        tab.webView = web

        val state = tab.savedState
        val restored = state != null && web.restoreState(state) != null
        if (!restored) {
            val url = tab.pendingUrl ?: tab.pageUrl.takeIf { it.isNotEmpty() } ?: prefs.homePage
            // Başlangıç ekranı yerel bir görünüm; WebView boş belge tutar.
            web.loadUrl(if (url == Prefs.HOME_URL) BLANK else url, extraHeaders())
        }
        // Bekleyen adres burada temizlenmez: `loadUrl` eşzamansız çalışır,
        // `onPageStarted` yolda ve `pageUrl` hâlâ boş. Adres çubuğunu
        // ("yeni sekme açıldı ama üstte hâlâ eski URL görünüyor" hatası)
        // yönlendirme anında güncel tutmak için bekleyen URL, gerçek gezinme
        // başlayınca `OrbitWebViewClient.onPageStarted` içinde sıfırlanır;
        // durum geri yüklendiyse zaten anlamsız olduğu için hemen düşer.
        if (restored) tab.pendingUrl = null
        return web
    }

    /** Sekmeyi canlı listede en öne alır ve sınırı aşanları uyutur. */
    private fun touch(tab: Tab) {
        liveOrder.remove(tab)
        liveOrder.add(0, tab)
        val limit = prefs.liveTabLimit.coerceAtLeast(1)
        while (liveOrder.size > limit) {
            val victim = liveOrder.removeAt(liveOrder.size - 1)
            if (victim !== current) hibernate(victim)
        }
    }

    /** Durumu belleğe alıp WebView'i yok eder; oluşturucu belleği geri gelir. */
    private fun hibernate(tab: Tab) {
        val web = tab.webView ?: return
        val bundle = Bundle()
        web.saveState(bundle)
        tab.savedState = bundle
        release(tab)
        WebViewFactory.destroy(web)
    }

    /** WebView'e bağlı her şeyi bırakır; sırada bekleyen sorgular boşa düşer. */
    private fun release(tab: Tab) {
        tab.bridge?.detach()
        tab.bridge = null
        tab.webView = null
    }

    /** Sistem bellek baskısı bildirdiğinde geçerli sekme dışındaki her şey uyur. */
    fun trimMemory() {
        for (tab in ArrayList(liveOrder)) {
            if (tab !== current) {
                liveOrder.remove(tab)
                hibernate(tab)
            }
        }
    }

    fun extraHeaders(): Map<String, String> =
        if (prefs.doNotTrack) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()

    fun loadInCurrent(url: String) {
        val tab = current ?: newTab(url)
        tab.isHome = url == Prefs.HOME_URL
        val target = if (tab.isHome) BLANK else url
        if (tab.isHome) tab.pageUrl = ""
        val web = tab.webView
        if (web != null) {
            web.loadUrl(target, extraHeaders())
        } else {
            tab.pendingUrl = url
            select(tab)
        }
    }

    private companion object {
        const val BLANK = "about:blank"
    }

    /**
     * Uygulama arka plana geçerken WebView bilerek duraklatılmıyor:
     * `WebView.onPause` + `pauseTimers` çağrıları Chromium'un ses hattını
     * susturuyordu. Ses (radyo, video sesi) arka planda çalışabilsin diye
     * yalnızca gerçek hibernate sırasında (bellek baskısı, sekme kapatma)
     * WebView durdurulur; sıradan arka plana geçişte Chromium kendi başına
     * yükü azaltır. Süreç foreground kalabilsin diye MainActivity ayrıca
     * `MediaKeepAliveService` başlatır — kullanıcı bildirimdeki "Durdur"
     * düğmesiyle her an durdurabilir.
     */
    fun onPause() {
        // Bilerek boş: sekme arka plana geçse bile WebView'ı susturmuyoruz.
    }

    fun onResume() {
        // WebView.onResume çağırmaya gerek yok — onPause hiç çağrılmadı.
    }

    fun updateTheme() {
        for (tab in _tabs) {
            tab.webView?.let {
                WebViewFactory.applyTheme(it, prefs)
                // Karartma ayarı ya da tema değişmiş olabilir; taban rengi de
                // yeniden hesaplanır (aksi hâlde eski renk sayfanın altında kalır).
                WebViewFactory.applyBaseBackground(it, prefs, blank = tab.isHome)
            }
        }
    }

    fun saveSession() {
        if (!prefs.restoreTabs) {
            prefs.savedSession = ""
            return
        }
        val nonIncognitoTabs = _tabs.filter { !it.incognito }
        if (nonIncognitoTabs.isEmpty()) {
            prefs.savedSession = ""
            return
        }
        try {
            val array = org.json.JSONArray()
            var selectedIndex = 0
            for ((index, tab) in nonIncognitoTabs.withIndex()) {
                if (tab === current) selectedIndex = index
                val obj = org.json.JSONObject()
                val url = tab.displayUrl()
                obj.put("url", if (tab.isHome) Prefs.HOME_URL else url)
                obj.put("title", tab.title)
                array.put(obj)
            }
            val root = org.json.JSONObject()
            root.put("selectedIndex", selectedIndex)
            root.put("tabs", array)
            prefs.savedSession = root.toString()
        } catch (_: Exception) {}
    }

    fun restoreSession(): Boolean {
        if (!prefs.restoreTabs) return false
        val sessionStr = prefs.savedSession
        if (sessionStr.isEmpty()) return false

        return try {
            val root = org.json.JSONObject(sessionStr)
            val selectedIndex = root.optInt("selectedIndex", 0)
            val array = root.optJSONArray("tabs") ?: return false
            if (array.length() == 0) return false

            var selectedTab: Tab? = null
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url", Prefs.HOME_URL)
                val isSelected = i == selectedIndex
                val tab = newTab(url, select = false)
                tab.title = obj.optString("title", "")
                if (isSelected) selectedTab = tab
            }

            val target = selectedTab ?: _tabs.firstOrNull()
            if (target != null) {
                select(target)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
