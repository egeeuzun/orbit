package com.orbit.browser.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.orbit.browser.adblock.AdblockService
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Sayfadaki betikle motor arasındaki tek köprü.
 *
 * İçerik betiği DOM'da gerçekten bulunan class/id değerlerini gönderir,
 * karşılığında yalnızca onlarla eşleşen genel kozmetik seçicilerin CSS'ini
 * alır. Bu yüzey bilinçli olarak tek yönlü ve salt hesaplama: sayfaya
 * tarayıcıya ait hiçbir yetenek açılmaz.
 *
 * Köprü **eşzamansızdır**. `@JavascriptInterface` metodu sayfanın betik iş
 * parçacığında çalışır; eskiden burada JSON ayrıştırma, kilit alma ve JNI
 * sorgusu yapılıp sonuç döndürülüyordu — yani sorgu bitene kadar sayfanın
 * betikleri ve dolayısıyla çizimi duruyordu. Bu, DOM değiştikçe saniyede
 * birkaç kez tekrarlandığı için kaydırma ve etkileşimde takılma olarak
 * hissediliyordu. Artık istek anında geri döner, iş arka planda yapılır ve
 * CSS sayfaya `window.__orbit.css()` ile itilir.
 */
class CosmeticBridge(
    private val adblock: AdblockService,
    private val isEnabled: () -> Boolean
) {

    /**
     * CSS'in itileceği görünüm. WebView yok edildikten sonra sırada bekleyen
     * bir sorgunun onu canlı tutmaması için zayıf referans.
     */
    @Volatile
    private var target: WeakReference<WebView>? = null

    fun attach(web: WebView) {
        target = WeakReference(web)
    }

    fun detach() {
        target = null
    }

    @JavascriptInterface
    fun requestGenericCss(classesJson: String, idsJson: String) {
        if (!isEnabled()) return
        adblock.query {
            val engine = adblock.engine
            if (!engine.isValid()) return@query
            val classes = parse(classesJson)
            val ids = parse(idsJson)
            if (classes.isEmpty() && ids.isEmpty()) return@query
            val css = engine.classIdCss(classes, ids, EMPTY)
            if (css.isEmpty()) return@query

            val js = "(function(){var o=window.__orbit;if(o)o.css(" +
                JSONObject.quote(css) + ");})();"
            adblock.postMain {
                target?.get()?.evaluateJavascript(js, null)
            }
        }
    }

    private fun parse(json: String): Array<String> = try {
        val arr = JSONArray(json)
        Array(arr.length()) { arr.optString(it) }
    } catch (_: Exception) {
        EMPTY
    }

    private companion object {
        val EMPTY = emptyArray<String>()
    }
}
