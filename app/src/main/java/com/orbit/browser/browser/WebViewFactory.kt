package com.orbit.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
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
        applyPerfRuntime(context, web, prefs)

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

    /**
     * Приоритет WebView-рендерера (API 29+): активное окно реже вытесняется
     * системой на слабых устройствах.
     */
    private fun applyRendererPriority(web: WebView, prefs: Prefs) {
        if (!prefs.optimizations) return
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Оптимизация производительности, применяемая к новым документам через
     * document-start-скрипт: базовый пакет (lazy-картинки + async-декод) и
     * турбо-пакет (content-visibility, отключение анимаций, GPU-облегчение).
     * Турбо-части пропускаются на хостах из исключений.
     */
    fun applyPerfRuntime(context: Context, web: WebView, prefs: Prefs) {
        applyRendererPriority(web, prefs)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val wanted = prefs.optimizations || prefs.gpuEase ||
            (prefs.turboPack && (prefs.turboContentVis || prefs.turboNoAnim || prefs.turboLibCache)) ||
            prefs.siteOverrides.isNotEmpty()
        val existing = web.getTag(R.id.orbit_perf_script) as? ScriptHandler

        if (!wanted) {
            if (existing != null) {
                try {
                    existing.remove()
                } catch (t: Throwable) {
                    Log.w(TAG, "Бетік оптимизации не удалось снять: ${t.message}")
                }
                web.setTag(R.id.orbit_perf_script, null)
            }
            return
        }
        // Скрипт всегда пересоздаётся: состав частей и профили могли измениться.
        try {
            existing?.remove()
        } catch (_: Throwable) {
        }
        web.setTag(R.id.orbit_perf_script, null)

        val script = buildPerfScript(prefs)
        if (script.isEmpty()) return
        try {
            val handler = WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
            web.setTag(R.id.orbit_perf_script, handler)
        } catch (t: Throwable) {
            Log.w(TAG, "Бетік оптимизации не удалось добавить: ${t.message}")
        }
        Log.i(
            TAG,
            "perf: глобально{base=${prefs.optimizations},cv=${prefs.turboPack && prefs.turboContentVis}," +
                "anim=${prefs.turboPack && prefs.turboNoAnim},gpu=${prefs.gpuEase}," +
                "lib=${prefs.turboPack && prefs.turboLibCache}} профили=${prefs.siteOverrides}"
        )
    }

    private fun buildPerfScript(prefs: Prefs): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("var G={base:").append(prefs.optimizations)
            .append(",cv:").append(prefs.turboPack && prefs.turboContentVis)
            .append(",anim:").append(prefs.turboPack && prefs.turboNoAnim)
            .append(",gpu:").append(prefs.gpuEase)
            .append(",lib:").append(prefs.turboPack && prefs.turboLibCache).append("};")
        sb.append("var O=[")
        prefs.siteOverrides.forEach { (h, f) ->
            sb.append("{h:\"").append(h).append("\",f:{")
            sb.append(f.map { (k, b) -> "\"$k\":" + b }.joinToString(","))
            sb.append("}},")
        }
        sb.append("];")
        sb.append("var hn=location.hostname;var ov=null;")
        sb.append("for(var i=0;i<O.length;i++){if(O[i].h===hn){ov=O[i].f;break;}}")
        sb.append("function get(k){return ov&&(k in ov)?ov[k]:G[k];}")
        sb.append("if(get('base')){")
        sb.append(LAZY_SCRIPT)
        sb.append(VIEWPORT_SCRIPT)
        sb.append("}")
        sb.append("if(get('cv')){")
        sb.append(CONTENT_VIS_SCRIPT)
        sb.append("}")
        sb.append("if(get('anim')){")
        sb.append(NO_ANIM_SCRIPT)
        sb.append("}")
        sb.append("if(get('gpu')){")
        sb.append(GPU_SCRIPT)
        sb.append("}")
        sb.append("})();")
        return sb.toString()
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

    /** Базовый пакет: lazy-загрузка картинок + async-декод (и на новые узлы). */
    private const val LAZY_SCRIPT = """
        (function () {
          function scan(root) {
            var imgs = (root.getElementsByTagName ? root.getElementsByTagName('img') : []);
            for (var i = 0; i < imgs.length; i++) {
              var img = imgs[i];
              if (!img.getAttribute('loading')) { img.loading = 'lazy'; }
              img.decoding = 'async';
            }
          }
          scan(document);
          new MutationObserver(function (ms) {
            for (var m = 0; m < ms.length; m++) {
              for (var n = 0; n < ms[m].addedNodes.length; n++) {
                if (ms[m].addedNodes[n].nodeType === 1) { scan(ms[m].addedNodes[n]); }
              }
            }
          }).observe(document.documentElement, { childList: true, subtree: true });
        })();
    """

    /** Турбо: рендерить только видимую область (content-visibility). */
    private const val CONTENT_VIS_SCRIPT = """
        (function () {
          if (CSS.supports('content-visibility', 'auto')) {
            var s = document.createElement('style');
            s.textContent = 'main,article,section,[class*="feed"],[class*="timeline"]{content-visibility:auto;contain-intrinsic-size:auto 800px;}';
            document.documentElement.appendChild(s);
          }
        })();
    """

    /**
     * Базовый пакет: сторожевик viewport — если SPA удалил/не отдал
     * viewport-meta (сайт начинает рендериться в широком desktop-вьюпорте),
     * возвращаем его. Существующий meta не трогаем.
     */
    private const val VIEWPORT_SCRIPT = """
        (function () {
          var ensure = function () {
            if (document.querySelector('meta[name=viewport]')) return;
            var m = document.createElement('meta');
            m.setAttribute('name', 'viewport');
            m.setAttribute('content', 'width=device-width,initial-scale=1');
            document.head.appendChild(m);
          };
          ensure();
          if (document.head) {
            new MutationObserver(function () {
              if (!document.querySelector('meta[name=viewport]')) { ensure(); }
            }).observe(document.head, { childList: true, subtree: true });
          }
        })();
    """

    /** Турбо: отключить CSS-анимации и переходы. */
    private const val NO_ANIM_SCRIPT = """
        (function () {
          var s = document.createElement('style');
          s.textContent = '*,*::before,*::after{animation-duration:0.01ms!important;animation-iteration-count:1!important;transition-duration:0.01ms!important;}';
          document.documentElement.appendChild(s);
        })();
    """

    /** Турбо: GPU-облегчение — тени/blur/fixed-фоны выключены (дешевле скролл). */
    private const val GPU_SCRIPT = """
        (function () {
          var s = document.createElement('style');
          s.textContent = '*,*::before,*::after{box-shadow:none!important;filter:none!important;backdrop-filter:none!important;}*{background-attachment:scroll!important;}';
          document.documentElement.appendChild(s);
        })();
    """
}
