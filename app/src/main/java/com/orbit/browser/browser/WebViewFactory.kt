package com.orbit.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.orbit.browser.BuildConfig
import com.orbit.browser.R
import com.orbit.browser.data.Prefs

/**
 * WebView kurulumu — ayarların çoğu 1 GB RAM'li bir cihazda akıcı kalmak
 * için seçildi.
 */
object WebViewFactory {

    /** Masaüstü görünümü için kullanılan güncel Chrome istemci kimliği. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/152.0.0.0 Safari/537.36"

    private var cosmeticScript: String? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun create(context: Context, prefs: Prefs, bridge: CosmeticBridge, incognito: Boolean): OrbitWebView {
        val web = OrbitWebView(context)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.isScrollbarFadingEnabled = true
        // Sayfa yüklenene kadar görünen zemin: koyu temada koyu, aydınlıkta
        // açık — about:blank dahil beyaz flaş olmasın.
        val isNight = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        web.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (isNight) R.color.m3_surface_dark else R.color.m3_surface_light
            )
        )
        // Yazılım katmanı zayıf GPU'larda kaydırmayı yavaşlatır; donanım açık kalır.
        web.overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS

        val s = web.settings
        s.javaScriptEnabled = prefs.javaScriptEnabled
        s.domStorageEnabled = true
        s.loadsImagesAutomatically = !prefs.blockImages
        s.blockNetworkImage = prefs.blockImages
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setGeolocationEnabled(false)
        s.saveFormData = false
        s.cacheMode = if (incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT

        // Gizli sekmede disk yazımı olmasın.
        if (incognito) {
            s.domStorageEnabled = false
        }

        applyTheme(web, prefs)
        applySafeBrowsing(s, prefs)
        applyCosmeticRuntime(context, web, prefs)

        web.addJavascriptInterface(bridge, "OrbitCosmetic")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, !prefs.blockThirdPartyCookies)
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        return web
    }

    fun applyTheme(web: WebView, prefs: Prefs) {
        val forceDark = prefs.forceDarkWeb
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, forceDark)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                web.settings,
                if (forceDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
    }

    /**
     * Safe Browsing her gezinmeye bir denetim turu ekler; zayıf cihazlarda
     * bu, adres girildikten sonraki ilk baytın gecikmesinde hissedilir.
     * Varsayılan açık kalır, isteyen Ayarlar > Gizlilik'ten kapatabilir.
     */
    private fun applySafeBrowsing(s: WebSettings, prefs: Prefs) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, prefs.safeBrowsing)
        }
    }

    /**
     * Kozmetik çalışma zamanını sayfa betiklerinden *önce* çalıştırır.
     *
     * Betik 16 KB ve `"*"` kalıbıyla kayıtlı olduğu için **her belgede, her
     * iframe dahil** ayrıştırılıp çalıştırılıyordu — kozmetik filtreleme
     * kapalıyken bile. Kapalıyken hiçbir işe yaramayan bu maliyet artık
     * ödenmiyor; ayar değişince [applyCosmeticRuntime] yeniden çağrılarak
     * betik canlı görünüme eklenir ya da kaldırılır.
     */
    fun applyCosmeticRuntime(context: Context, web: WebView, prefs: Prefs) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val wanted = prefs.adBlockEnabled && prefs.cosmeticFiltering
        val existing = web.getTag(R.id.orbit_cosmetic_script) as? ScriptHandler

        if (!wanted) {
            if (existing != null) {
                try {
                    existing.remove()
                } catch (t: Throwable) {
                    Log.w(TAG, "Belge başı betiği kaldırılamadı: ${t.message}")
                }
                web.setTag(R.id.orbit_cosmetic_script, null)
            }
            return
        }
        if (existing != null) return

        val script = loadScript(context) ?: return
        try {
            val handler = WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
            web.setTag(R.id.orbit_cosmetic_script, handler)
        } catch (t: Throwable) {
            Log.w(TAG, "Belge başı betiği eklenemedi: ${t.message}")
        }
    }

    fun loadScript(context: Context): String? {
        cosmeticScript?.let { return it }
        return try {
            context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
                .also { cosmeticScript = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Kozmetik betik okunamadı", t)
            null
        }
    }

    /** Yeniden yükleme yapmadan masaüstü/mobil kimliğini uygular. */
    fun applyDesktopMode(web: WebView, desktop: Boolean) {
        val s = web.settings
        s.userAgentString = if (desktop) DESKTOP_UA else null
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
    }

    fun setDesktopMode(web: WebView, desktop: Boolean) {
        applyDesktopMode(web, desktop)
        web.reload()
    }

    /**
     * Sekme arka plana alınırken çağrılır; render belleğini serbest bırakır.
     *
     * Eskiden burada `about:blank` yükleniyordu. Bu, hemen ardından
     * yok edilecek bir görünümde tam bir gezinme başlatmak demek — sekme
     * değiştirmede boşa giden onlarca milisaniye. `destroy()` zaten sesi
     * durdurup oluşturucuyu kapatıyor.
     */
    fun destroy(web: WebView) {
        web.stopLoading()
        web.onPause()
        web.removeJavascriptInterface("OrbitCosmetic")
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    private const val SCRIPT_ASSET = "orbit_cosmetic.js"
    private const val TAG = "WebViewFactory"
}
