package com.orbit.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.orbit.browser.adblock.FilterLists

/** Tüm ayarlar tek bir SharedPreferences dosyasında; ekstra bağımlılık yok. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("orbit", Context.MODE_PRIVATE)
            .also { Hot.attach(it) }

    // ------------------------------------------------------------- arama / açılış

    var searchEngine: String
        get() = sp.getString(KEY_SEARCH, DEFAULT_SEARCH)!!
        set(v) = sp.edit { putString(KEY_SEARCH, v) }

    var searchSuggestions: Boolean
        get() = sp.getBoolean(KEY_SEARCH_SUGGESTIONS, true)
        set(v) = sp.edit { putBoolean(KEY_SEARCH_SUGGESTIONS, v) }

    var homePage: String
        get() = sp.getString(KEY_HOME, HOME_URL)!!
        set(v) = sp.edit { putString(KEY_HOME, v) }

    // -------------------------------------------------------------------- gizlilik

    var javaScriptEnabled: Boolean
        get() = sp.getBoolean(KEY_JS, true)
        set(v) = sp.edit { putBoolean(KEY_JS, v) }

    var blockThirdPartyCookies: Boolean
        get() = sp.getBoolean(KEY_3P_COOKIES, true)
        set(v) = sp.edit { putBoolean(KEY_3P_COOKIES, v) }

    var doNotTrack: Boolean
        get() = sp.getBoolean(KEY_DNT, true)
        set(v) = sp.edit { putBoolean(KEY_DNT, v) }

    /**
     * Google Safe Browsing. Kapatmak gezinme gecikmesini düşürür (her adres
     * için yapılan denetim kalkar) ama kötü amaçlı site uyarısı da kalkar.
     */
    var safeBrowsing: Boolean
        get() = sp.getBoolean(KEY_SAFE_BROWSING, true)
        set(v) = sp.edit { putBoolean(KEY_SAFE_BROWSING, v) }

    var saveHistory: Boolean
        get() = sp.getBoolean(KEY_HISTORY, true)
        set(v) = sp.edit { putBoolean(KEY_HISTORY, v) }

    var clearOnExit: Boolean
        get() = sp.getBoolean(KEY_CLEAR_EXIT, false)
        set(v) = sp.edit { putBoolean(KEY_CLEAR_EXIT, v) }

    // ------------------------------------------------------------------ engelleme

    // Bu üçü istek başına okunur; değerleri [Hot] içinde bellekte tutulur.

    var adBlockEnabled: Boolean
        get() = Hot.adBlock
        set(v) {
            Hot.adBlock = v
            sp.edit { putBoolean(KEY_ADBLOCK, v) }
        }

    var cosmeticFiltering: Boolean
        get() = Hot.cosmetic
        set(v) {
            Hot.cosmetic = v
            sp.edit { putBoolean(KEY_COSMETIC, v) }
        }

    /** DOM taramasına dayalı genel kozmetik filtreleme (uBO'nun yaptığı gibi). */
    var genericCosmetic: Boolean
        get() = Hot.generic
        set(v) {
            Hot.generic = v
            sp.edit { putBoolean(KEY_GENERIC_COSMETIC, v) }
        }

    var enabledLists: Set<String>
        get() = sp.getStringSet(KEY_LISTS, null) ?: FilterLists.defaultIds
        set(v) = sp.edit { putStringSet(KEY_LISTS, v) }

    var customRules: String
        get() = sp.getString(KEY_CUSTOM_RULES, "")!!
        set(v) = sp.edit { putString(KEY_CUSTOM_RULES, v) }

    var customFilterUrls: Set<String>
        get() = sp.getStringSet(KEY_CUSTOM_URLS, null) ?: emptySet()
        set(v) = sp.edit { putStringSet(KEY_CUSTOM_URLS, v) }

    var lastListUpdate: Long
        get() = sp.getLong(KEY_LAST_UPDATE, 0L)
        set(v) = sp.edit { putLong(KEY_LAST_UPDATE, v) }

    var engineStamp: String
        get() = sp.getString(KEY_STAMP, "")!!
        set(v) = sp.edit { putString(KEY_STAMP, v) }

    var blockedCount: Long
        get() = sp.getLong(KEY_BLOCKED, 0L)
        set(v) = sp.edit { putLong(KEY_BLOCKED, v) }

    // --------------------------------------------------------- site bazlı izin listesi

    fun isAllowlisted(host: String): Boolean =
        host.isNotEmpty() && Hot.allowlist.contains(host)

    fun setAllowlisted(host: String, allowed: Boolean) {
        if (host.isEmpty()) return
        val set = HashSet(Hot.allowlist)
        if (allowed) set.add(host) else set.remove(host)
        Hot.allowlist = set
        sp.edit { putStringSet(KEY_ALLOWLIST, set) }
    }

    // -------------------------------------------------------------------- görünüm

    /** 0 = sistem, 1 = açık, 2 = koyu */
    var theme: Int
        get() = sp.getInt(KEY_THEME, 0)
        set(v) = sp.edit { putInt(KEY_THEME, v) }

    /** Sayfayı WebView'in karanlık moduyla zorla koyult. */
    var forceDarkWeb: Boolean
        get() = sp.getBoolean(KEY_FORCE_DARK, false)
        set(v) = sp.edit { putBoolean(KEY_FORCE_DARK, v) }

    /** 1 GB RAM'de aynı anda canlı tutulacak WebView sayısı. */
    var liveTabLimit: Int
        get() = sp.getInt(KEY_LIVE_TABS, 1)
        set(v) = sp.edit { putInt(KEY_LIVE_TABS, v) }

    var blockImages: Boolean
        get() = sp.getBoolean(KEY_BLOCK_IMAGES, false)
        set(v) = sp.edit { putBoolean(KEY_BLOCK_IMAGES, v) }

    var restoreTabs: Boolean
        get() = sp.getBoolean(KEY_RESTORE_TABS, false)
        set(v) = sp.edit { putBoolean(KEY_RESTORE_TABS, v) }

    var savedSession: String
        get() = sp.getString(KEY_SAVED_SESSION, "")!!
        set(v) = sp.edit { putString(KEY_SAVED_SESSION, v) }

    /**
     * İstek başına okunan ayarların bellek içi kopyası.
     *
     * `SharedPreferences` her `get` çağrısında kendi kilidini alır.
     * `shouldInterceptRequest` bir sayfa yüklenirken saniyede yüzlerce kez,
     * üstelik birkaç ağ iş parçacığından birden çalışıyor; bu kilit orada
     * ölçülebilir bir sıraya dönüşüyordu. Değerler burada `volatile` alanlarda
     * tutulur, dosya değiştikçe dinleyiciyle tazelenir.
     */
    private object Hot {
        @Volatile var adBlock: Boolean = true
        @Volatile var cosmetic: Boolean = true
        @Volatile var generic: Boolean = true
        @Volatile var allowlist: Set<String> = emptySet()

        // Dinleyici güçlü referansla tutulmalı; SharedPreferences zayıf tutar.
        private val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sp, _ -> read(sp) }

        private var attached = false

        @Synchronized
        fun attach(sp: SharedPreferences) {
            if (attached) return
            attached = true
            read(sp)
            sp.registerOnSharedPreferenceChangeListener(listener)
        }

        private fun read(sp: SharedPreferences) {
            adBlock = sp.getBoolean(KEY_ADBLOCK, true)
            cosmetic = sp.getBoolean(KEY_COSMETIC, true)
            generic = sp.getBoolean(KEY_GENERIC_COSMETIC, true)
            // Kopyalanır: SharedPreferences kendi kümesini geri verir ve o küme
            // sonraki yazımlarda değişebilir.
            allowlist = sp.getStringSet(KEY_ALLOWLIST, null)?.let { HashSet(it) } ?: emptySet()
        }
    }

    fun getSearchEngineName(): String =
        SEARCH_ENGINES.entries.firstOrNull { it.value == searchEngine }?.key ?: "Google"

    companion object {
        const val DEFAULT_SEARCH = "https://www.google.com/search?q=%s"

        /** Yerel başlangıç ekranını temsil eden sözde adres. */
        const val HOME_URL = "orbit://home"

        val SEARCH_ENGINES = linkedMapOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
            "Google" to "https://www.google.com/search?q=%s",
            "Bing" to "https://www.bing.com/search?q=%s",
            "Brave" to "https://search.brave.com/search?q=%s",
            "Startpage" to "https://www.startpage.com/sp/search?query=%s",
            "Yandex" to "https://yandex.com.tr/search/?text=%s"
        )

        private const val KEY_SEARCH = "search_engine"
        private const val KEY_SEARCH_SUGGESTIONS = "search_suggestions"
        private const val KEY_HOME = "home_page"
        private const val KEY_JS = "javascript"
        private const val KEY_3P_COOKIES = "block_3p_cookies"
        private const val KEY_DNT = "do_not_track"
        private const val KEY_SAFE_BROWSING = "safe_browsing"
        private const val KEY_HISTORY = "save_history"
        private const val KEY_CLEAR_EXIT = "clear_on_exit"
        private const val KEY_ADBLOCK = "adblock"
        private const val KEY_COSMETIC = "cosmetic"
        private const val KEY_GENERIC_COSMETIC = "generic_cosmetic"
        private const val KEY_LISTS = "lists"
        private const val KEY_CUSTOM_RULES = "custom_rules"
        private const val KEY_CUSTOM_URLS = "custom_urls"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_STAMP = "engine_stamp"
        private const val KEY_BLOCKED = "blocked_count"
        private const val KEY_ALLOWLIST = "allowlist"
        private const val KEY_THEME = "theme"
        private const val KEY_FORCE_DARK = "force_dark"
        private const val KEY_LIVE_TABS = "live_tabs"
        private const val KEY_BLOCK_IMAGES = "block_images"
        private const val KEY_RESTORE_TABS = "restore_tabs"
        private const val KEY_SAVED_SESSION = "saved_session"
    }
}
