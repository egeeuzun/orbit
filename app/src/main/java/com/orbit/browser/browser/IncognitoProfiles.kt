package com.orbit.browser.browser

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Gizli sekmelerin ayrı WebView profili.
 *
 * Eskiden gizli sekme normal sekmelerle aynı (varsayılan) profili
 * kullanıyordu: çerezler, IndexedDB, service worker'lar ve site izinleri
 * ortaktı. Gizli sekmede açılan oturum normal sekmede de açık görünüyor,
 * gizli sekme kapanınca da veriler kalıyordu. Yalnızca DOM storage
 * kapatılıyordu, bu da pek çok siteyi bozuyordu.
 *
 * Artık her gizli oturum (ilk gizli sekmenin açılmasından son gizli sekmenin
 * kapanmasına kadar) benzersiz adlı yeni bir profil alır:
 *  - Normal sekmelerle hiçbir site verisi paylaşılmaz.
 *  - Oturum bitince profilin çerezleri ve depolaması hemen silinir; sonraki
 *    gizli sekme yepyeni, boş bir profille başlar.
 *  - `ProfileStore.deleteProfile`, bu süreçte bir kez yüklenmiş profili
 *    silemiyor. Bu yüzden eski profillerin dizinleri bir sonraki açılışta,
 *    henüz hiçbiri yüklenmemişken [purgeStale] ile tamamen silinir.
 *
 * `MULTI_PROFILE`, WebView 123'ten eski sağlayıcılarda yok. Orada gizli sekme
 * eski davranışta kalır ve kullanıcıya çerezlerin ortak olduğu söylenir.
 */
object IncognitoProfiles {

    private const val PREFIX = "orbit-incognito-"
    private const val TAG = "IncognitoProfiles"

    /** Sonuç süreç boyunca değişmez; sağlayıcının özellik listesi bir kez taranır. */
    val supported: Boolean by lazy {
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (_: Throwable) {
            false
        }
    }

    /** Açık gizli oturumun profil adı; gizli sekme yoksa null. */
    private var sessionName: String? = null

    /**
     * Yeni gizli WebView'i oturumun profiline bağlar ve o profilin çerez
     * yöneticisini döndürür. Desteklenmiyor ya da bağlanamadıysa null döner
     * (WebView varsayılan profilde kalır).
     *
     * WebView oluşturulur oluşturulmaz, ayarlardan, betiklerden ve ilk
     * gezinmeden önce çağrılmalıdır.
     */
    fun attach(web: WebView): CookieManager? {
        if (!supported) return null
        return try {
            val name = sessionName ?: (PREFIX + System.currentTimeMillis()).also { sessionName = it }
            WebViewCompat.setProfile(web, name)
            ProfileStore.getInstance().getOrCreateProfile(name).cookieManager
        } catch (t: Throwable) {
            // Sekmeyi çökertmek yerine eski kısıtlı moda düşülür (DOM
            // storage kapalı, önbellek yok); çağıran null'a göre davranır.
            Log.w(TAG, "Gizli profil bağlanamadı", t)
            null
        }
    }

    /**
     * Son gizli sekme kapandı: profilin verisi hemen silinir ve sonraki gizli
     * sekme için yeni bir profil açılır. Profilin kendisi bu süreçte
     * silinemediği için diskteki boş kabuğu bir sonraki açılışta gider.
     */
    fun endSession() {
        val name = sessionName ?: return
        sessionName = null
        if (!supported) return
        try {
            val profile = ProfileStore.getInstance().getProfile(name) ?: return
            profile.cookieManager.apply {
                removeAllCookies(null)
                flush()
            }
            profile.webStorage.deleteAllData()
            profile.geolocationPermissions.clearAll()
        } catch (t: Throwable) {
            Log.w(TAG, "Gizli profil temizlenemedi", t)
        }
    }

    /**
     * Önceki çalıştırmalardan (ya da çökmeden) kalan gizli profilleri siler.
     * Bu süreçte henüz yüklenmemiş olmaları gerektiği için açılışta, ilk
     * gizli sekmeden bağımsız olarak çağrılır. Açık oturumun profiline
     * dokunulmaz.
     */
    fun purgeStale() {
        if (!supported) return
        try {
            val store = ProfileStore.getInstance()
            for (name in store.allProfileNames) {
                if (!name.startsWith(PREFIX) || name == sessionName) continue
                try {
                    store.deleteProfile(name)
                } catch (t: Throwable) {
                    Log.w(TAG, "Eski gizli profil silinemedi: $name", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Gizli profiller listelenemedi", t)
        }
    }
}
