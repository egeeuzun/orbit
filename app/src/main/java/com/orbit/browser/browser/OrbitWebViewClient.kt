package com.orbit.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.orbit.browser.BuildConfig
import com.orbit.browser.adblock.AdblockService
import com.orbit.browser.adblock.RequestTypes
import com.orbit.browser.data.Prefs
import com.orbit.browser.util.UrlUtils
import org.json.JSONObject

/**
 * Engelleme kararlarının verildiği yer.
 *
 * [shouldInterceptRequest] arka plan iş parçacıklarından ve saniyede yüzlerce
 * kez çağrılır; bu yüzden burada tahsis ve kilit en aza indirildi.
 */
class OrbitWebViewClient(
    private val context: Context,
    private val tab: Tab,
    private val adblock: AdblockService,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) : WebViewClient() {

    interface Callbacks {
        fun onPageUrlChanged(tab: Tab, url: String)
        fun onPageStarted(tab: Tab)
        fun onPageFinished(tab: Tab)
        fun onBlockedCountChanged(tab: Tab)
        fun onExternalIntent(uri: Uri): Boolean
    }

    /** Son kozmetik enjeksiyonun adresi ve hazırlanmış betiği (ana iş parçacığı). */
    private var cosmeticUrl: String? = null
    private var cosmeticJs: String? = null

    /**
     * Engellenen istek için iptal yanıtı.
     *
     * Görseller için 1x1 saydam GIF dönerek sayfa düzeninin kaymasını engeller.
     * Diğer istekler için bilerek `null` gövdeli yanıt döner: `null` akış isteği
     * düşürür ve sayfa `onerror` görür. (Via'daki mantığın aynısı.)
     */
    private fun blockedResponse(request: WebResourceRequest, url: String, type: String): WebResourceResponse {
        val isImage = type == "image" || isImageRequest(request, url)
        return if (isImage) BLOCKED_IMAGE else BLOCKED
    }

    private fun isImageRequest(request: WebResourceRequest, url: String): Boolean {
        val path = request.url.path?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
            path.endsWith(".ico")
        ) return true
        return request.requestHeaders["Accept"]?.contains("image/") == true
    }

    /** Sayaçları işleyip iptal yanıtını döndürür. */
    private fun blockedFor(request: WebResourceRequest, url: String, type: String): WebResourceResponse {
        adblock.onBlocked()
        tab.countBlocked()
        callbacks.onBlockedCountChanged(tab)
        if (BuildConfig.DEBUG) Log.d(TAG, "engellendi $url")
        return blockedResponse(request, url, type)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // Турбо: популярные CDN-библиотеки отдаются из локального кэша
        // (эффективное значение с учётом пер-сайт профиля).
        LibCache.match(context, prefs, request.url.toString())?.let { return it }
        // Kozmetik CSS isteğini yakala: Via'daki gibi <link> üzerinden
        // gelen CSS isteğine doğrudan stil yanıtı dönülür.
        val path = request.url.path
        if (path == "/orbit_inject_blocker.css") {
            val pageUrl = tab.pageUrl
            val css = if (prefs.adBlockEnabled && prefs.cosmeticFiltering && pageUrl.isNotEmpty()) {
                adblock.engine.cosmetic(pageUrl)?.css.orEmpty()
            } else ""
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store, no-cache"
            )
            return WebResourceResponse(
                "text/css",
                "utf-8",
                200,
                "OK",
                headers,
                css.toByteArray().inputStream()
            )
        }

        // Denetimler ucuzdan pahalıya sıralı: alan okumaları önce, motor en son.

        // Ana çerçeve hiçbir zaman engellenmez: gezinmeyi tamamen kırar.
        if (request.isForMainFrame) return null
        if (tab.allowlisted) return null
        if (!prefs.adBlockEnabled) return null

        val pageUrl = tab.pageUrl
        if (pageUrl.isEmpty()) return null

        val url = request.url.toString()
        if (!UrlUtils.isHttp(url)) return null

        val type = RequestTypes.of(request, url)

        // Hızlı yol: kuralların dörtte üçü tek bir ana bilgisayar adından
        // ibaret ve bunlar sıralı bir dizide ikili aramayla yanıtlanıyor —
        // JNI sınırı geçilmiyor, adres ayrıştırılmıyor, motorun global
        // kilidi alınmıyor. Tablo motor hazır olmadan önce kurulduğu için
        // soğuk açılışta da engelleme çalışır.
        // `request.url` zaten çözümlenmiş bir `Uri`; adres yeniden
        // ayrıştırılmasın diye ana bilgisayar buradan okunur.
        val host = request.url.host
        if (host != null && adblock.hosts.blocks(host)) {
            return blockedFor(request, url, type)
        }

        // Aynı sayfa aynı adresi çoğu kez birden fazla ister; tekrarlar
        // motora hiç gitmez.
        val cache = adblock.decisions
        val cached = cache.get(url, pageUrl, type)
        val blocked = if (cached != null) {
            cached
        } else {
            // `blocks` motor yoksa da güvenli biçimde false döner; ayrıca
            // `isValid` sorgusu yapmak kilidi istek başına ikinci kez almak
            // demekti.
            adblock.engine.blocks(url, pageUrl, type).also {
                cache.put(url, pageUrl, type, it)
            }
        }

        if (!blocked) return null
        return blockedFor(request, url, type)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()
        // http/https dışındaki şemalar (tel:, mailto:, intent:, market:) sisteme devredilir.
        if (scheme != null && scheme != "http" && scheme != "https" && scheme != "about") {
            return callbacks.onExternalIntent(uri)
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // "about:blank" başlangıç ekranının taşıyıcısıdır, gerçek gezinme değil.
        if (url != BLANK) tab.isHome = false
        tab.pageUrl = url
        tab.blockedOnPage = 0
        tab.allowlisted = prefs.isAllowlisted(UrlUtils.host(url))
        callbacks.onPageUrlChanged(tab, url)
        callbacks.onPageStarted(tab)
        injectCosmetic(view, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        tab.pageUrl = url
        callbacks.onPageUrlChanged(tab, url)
        callbacks.onPageFinished(tab)
        // Belge başında enjeksiyon desteklenmiyorsa ikinci deneme burada olur.
        injectCosmetic(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Tek sayfalık uygulamalarda adres yalnızca burada değişir.
        if (url != tab.pageUrl) {
            tab.pageUrl = url
            tab.allowlisted = prefs.isAllowlisted(UrlUtils.host(url))
            callbacks.onPageUrlChanged(tab, url)
            injectCosmetic(view, url)
        }
    }

    /**
     * Sayfaya özel gizleme CSS'i, prosedürel filtreler ve genel kozmetik
     * taramasının açılışı. Betiğin kendisi `WebViewFactory` tarafından
     * belge başında yüklenir.
     *
     * Aynı adres için üç kez çağrılabiliyor (belge başı, sayfa sonu, tek
     * sayfalık uygulamada adres değişimi). Motor sorgusu ve — asıl pahalı
     * kısım — yüz kilobaytı bulabilen CSS'in JSON'a kaçışlanması bu yüzden
     * adres başına bir kez yapılır, sonraki çağrılar hazır metni kullanır.
     * Betiğin kendisi zaten tekrar uygulamaya karşı korumalı.
     */
    private fun injectCosmetic(view: WebView, url: String) {
        if (!prefs.adBlockEnabled || !prefs.cosmeticFiltering) return
        if (!UrlUtils.isHttp(url) || tab.allowlisted) return

        val cached = if (url == cosmeticUrl) cosmeticJs else null
        val js = cached ?: buildCosmeticJs(url)?.also {
            cosmeticUrl = url
            cosmeticJs = it
        } ?: return

        view.evaluateJavascript(js, null)
    }

    private fun buildCosmeticJs(url: String): String? {
        val result = adblock.engine.cosmetic(url) ?: return null
        val generic = prefs.genericCosmetic && !result.genericHide
        val hasCss = result.css.isNotEmpty()
        val hasProcedural = result.proceduralJson.length > 2

        if (!hasCss && !hasProcedural && !generic) return null

        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        val scheme = uri.scheme ?: "https"

        return buildString {
            append("(function(){var o=window.__orbit;if(!o)return;")
            if (hasCss && host.isNotEmpty()) {
                // Via'nın numarası: 26 KB CSS dizgisini JS olarak V8'e yollamak yerine
                // bir <link> öğesi enjekte edip shouldInterceptRequest'in /orbit_inject_blocker.css
                // isteğini yakalamasını sağlıyoruz. Native Blink CSS parser çalışır.
                append("if(!document.getElementById('__orbit_css')){")
                append("var l=document.createElement('link');l.id='__orbit_css';l.rel='stylesheet';")
                append("l.href='").append(scheme).append("://").append(host).append("/orbit_inject_blocker.css';")
                append("(document.head||document.documentElement).appendChild(l);}")
            }
            if (hasProcedural) {
                append("o.procedural(").append(JSONObject.quote(result.proceduralJson)).append(");")
            }
            append("o.generic(").append(generic).append(");")
            append("})();")
        }
    }

    private companion object {
        const val BLANK = "about:blank"
        const val TAG = "OrbitBlock"

        /** Bütün engellemelerde paylaşılan iptal yanıtı; gövdesi yok. */
        val BLOCKED = WebResourceResponse("text/plain", "utf-8", null)

        /** Görsel engellemeleri için paylaşılan 1x1 saydam GIF yanıtı. */
        private val GIF_1X1 = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x00, 0x00, 0x00, 0x21.toByte(), 0xf9.toByte(), 0x04, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b
        )

        val BLOCKED_IMAGE: WebResourceResponse
            get() = WebResourceResponse(
                "image/gif",
                "utf-8",
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                GIF_1X1.inputStream()
            )
    }
}
