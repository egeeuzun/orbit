package com.orbit.browser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.orbit.browser.R
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sayfa içi çeviri.
 *
 * Eskiden sayfa `translate.google.com/translate?u=` vekiliyle açılıyordu:
 * sayfayı Google'ın sunucuları çekiyor, Wikipedia gibi siteler bu veri
 * merkezi trafiğini reddediyor, Google da zaman zaman CAPTCHA'ya
 * yönlendiriyordu. Artık sayfa yerinde kalır; `orbit_translate.js` görünen
 * metni parça parça bu köprüye verir, metin Google'ın metin çeviri uç
 * noktasına gider ve sonuç sayfaya geri yazılır. Ek kütüphane ya da model
 * yok; bellek maliyeti sayfanın görünen metni kadar.
 *
 * Köprü her WebView'e kurulurken eklenir (`addJavascriptInterface` ancak bir
 * sonraki yüklemede görünür), ama yalnızca kullanıcı çeviriyi başlattığında
 * ve doğru oturum numarasıyla gelen istekleri kabul eder; sayfa kendi başına
 * çeviri isteyemez. Gezinme oturumu bitirir.
 */
class PageTranslator private constructor(web: WebView) {

    private val target = WeakReference(web)

    private class Session(val token: Int, val source: String, val target: String) {
        /** `<html lang>`'dan çıkarılan sayfa dili; bilinmiyorsa null. */
        @Volatile
        var pageLanguage: String? = null

        /** Gerçekte gönderilecek kaynak dil. */
        val effectiveSource: String get() = if (source == "auto") pageLanguage ?: "auto" else source
    }

    @Volatile
    private var session: Session? = null

    /** Sayfa betiğinin çağırdığı yüzey. Sayfanın betik iş parçacığında çalışır; anında döner. */
    inner class Bridge {
        @JavascriptInterface
        fun setPageLanguage(token: Int, lang: String) {
            val s = session ?: return
            if (token == s.token) s.pageLanguage = normalizeLanguage(lang)
        }

        @JavascriptInterface
        fun request(token: Int, id: Int, payload: String) {
            val s = session ?: return
            if (token != s.token || payload.length > MAX_PAYLOAD) {
                fail(id)
                return
            }
            if (queued.incrementAndGet() > MAX_QUEUED) {
                queued.decrementAndGet()
                fail(id)
                return
            }
            net.execute {
                try {
                    val texts = JSONArray(payload)
                    val result = if (session === s) translate(texts, s) else null
                    if (result == null) fail(id) else push(id, s, result)
                } catch (t: Throwable) {
                    Log.w(TAG, "çeviri başarısız: ${t.message}")
                    fail(id)
                } finally {
                    queued.decrementAndGet()
                }
            }
        }
    }

    private val queued = AtomicInteger(0)

    /** Etkin bir çeviri var mı (çeviri penceresi "Orijinali göster" için sorar). */
    val isActive: Boolean get() = session != null

    fun start(context: Context, source: String, targetLang: String) {
        val web = target.get() ?: return
        val script = loadScript(context) ?: return
        if (userAgent == null) {
            userAgent = try {
                android.webkit.WebSettings.getDefaultUserAgent(context.applicationContext)
            } catch (_: Throwable) {
                null
            }
        }
        val token = tokens.incrementAndGet()
        session = Session(token, source, targetLang)
        web.evaluateJavascript("$script\n;window.__orbitTr&&window.__orbitTr.start($token);", null)
    }

    fun restore() {
        session = null
        target.get()?.evaluateJavascript("window.__orbitTr&&window.__orbitTr.restore();", null)
    }

    /** Yeni belge: eski çevirinin oturumu biter (betik de belgeyle birlikte gitti). */
    fun onNewDocument() {
        session = null
    }

    private fun push(id: Int, s: Session, result: JSONArray) {
        val js = "window.__orbitTr&&window.__orbitTr.done($id,$result);"
        main.post {
            if (session === s) target.get()?.evaluateJavascript(js, null)
        }
    }

    private fun fail(id: Int) {
        main.post {
            target.get()?.evaluateJavascript("window.__orbitTr&&window.__orbitTr.fail($id);", null)
        }
    }

    /**
     * Google'ın anahtarsız metin çeviri uç noktası (tarayıcı eklentilerinin de
     * kullandığı `client=gtx`). `format=html` satır içi işaretleri korur.
     * Kaynak "auto" iken her öğe `[çeviri, dil]`, değilse düz metin döner.
     */
    private fun translate(texts: JSONArray, s: Session): JSONArray? {
        if (texts.length() == 0) return JSONArray()
        val source = s.effectiveSource
        // Sayfa zaten hedef dilde: ağa çıkılmaz, metin olduğu gibi kalır.
        if (source == s.target || source.substringBefore('-') == s.target.substringBefore('-') &&
            !source.startsWith("zh")
        ) {
            return texts
        }
        val url = URL(
            "https://translate.googleapis.com/translate_a/t?client=gtx&format=html" +
                "&sl=" + URLEncoder.encode(source, "UTF-8") +
                "&tl=" + URLEncoder.encode(s.target, "UTF-8")
        )
        val body = StringBuilder()
        for (i in 0 until texts.length()) {
            if (i > 0) body.append('&')
            body.append("q=").append(URLEncoder.encode(texts.getString(i), "UTF-8"))
        }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            // Android'in varsayılan kimliğiyle ("Dalvik/2.1.0 …") gelen istekleri
            // Google CAPTCHA sayfasına yönlendiriyor (302 → 429). İstek, sayfayı
            // gösteren WebView'in kimliğiyle gider.
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            conn.instanceFollowRedirects = false
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "çeviri HTTP ${conn.responseCode}")
                return null
            }
            val raw = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (raw.length() != texts.length()) return null
            val out = JSONArray()
            for (i in 0 until raw.length()) {
                val item = raw.get(i)
                out.put(if (item is JSONArray) item.optString(0) else item.toString())
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "PageTranslator"

        /**
         * `<html lang>` değerini Google'ın dil koduna çevirir ("en-US" -> "en",
         * "zh-Hant" -> "zh-TW", "he" -> "iw"). Anlaşılmazsa null: "otomatik" kalır.
         */
        fun normalizeLanguage(raw: String): String? {
            val lang = raw.trim().lowercase(java.util.Locale.ROOT).replace('_', '-')
            val primary = lang.substringBefore('-')
            if (!primary.matches(Regex("[a-z]{2,3}"))) return null
            return when (primary) {
                "zh" -> if (lang.contains("hant") || lang.contains("tw") || lang.contains("hk")) "zh-TW" else "zh-CN"
                "he" -> "iw"
                "jv" -> "jw"
                else -> primary
            }
        }
        const val JS_NAME = "OrbitTranslate"
        private const val SCRIPT_ASSET = "orbit_translate.js"

        /** Bir istekte en çok bu kadar karakter (betik ~4,5 KB'lık partiler yollar). */
        private const val MAX_PAYLOAD = 64 * 1024

        /** Sırada bekleyen istek sınırı; betik zaten aynı anda en çok iki tane yollar. */
        private const val MAX_QUEUED = 4

        private val tokens = AtomicInteger(0)
        private val main = Handler(Looper.getMainLooper())

        private val net = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "translate-net").apply { isDaemon = true }
        }

        @Volatile
        private var script: String? = null

        /** WebView'in tarayıcı kimliği; ilk çeviride bir kez okunur. */
        @Volatile
        private var userAgent: String? = null

        private fun loadScript(context: Context): String? {
            script?.let { return it }
            return try {
                context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
                    .also { script = it }
            } catch (t: Throwable) {
                Log.e(TAG, "çeviri betiği okunamadı", t)
                null
            }
        }

        /** WebView kurulurken köprüyü ekler. */
        @android.annotation.SuppressLint("JavascriptInterface", "AddJavascriptInterface")
        fun attach(web: WebView) {
            val t = PageTranslator(web)
            web.addJavascriptInterface(t.Bridge(), JS_NAME)
            web.setTag(R.id.orbit_translator, t)
        }

        fun of(web: WebView?): PageTranslator? = web?.getTag(R.id.orbit_translator) as? PageTranslator

        fun detach(web: WebView) {
            of(web)?.session = null
            web.removeJavascriptInterface(JS_NAME)
            web.setTag(R.id.orbit_translator, null)
        }
    }
}
