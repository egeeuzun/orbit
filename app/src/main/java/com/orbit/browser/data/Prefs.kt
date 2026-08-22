package com.orbit.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.orbit.browser.adblock.FilterLists
import java.util.HashSet

/** Tüm ayarlar tek bir SharedPreferences dosyasında; ekstra bağımlılık yok. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("orbit", Context.MODE_PRIVATE)
            .also { Hot.attach(it) }

    /** Сессии открытых вкладок — отдельный файл вне облачного бэкапа. */
    private val spSession: SharedPreferences =
        context.applicationContext.getSharedPreferences("orbit_session", Context.MODE_PRIVATE)

    init {
        // Новые defaultOn-списки каталога на уже установленной версии
        // не включены: набор сохранён в SharedPreferences. Одноразово
        // объединяем его с дефолтным набором, чтобы пользователь не
        // потерял свой выбор при обновлении.
        if (!sp.getBoolean(KEY_LISTS_MIGRATED_V2, false)) {
            val merged = HashSet(enabledLists)
            merged.addAll(FilterLists.defaultIds)
            sp.edit {
                putStringSet(KEY_LISTS, merged)
                putBoolean(KEY_LISTS_MIGRATED_V2, true)
            }
        }
        // Старые турбо-исключения → пер-сайт профили (cv/anim выключены,
        // как раньше делало исключение); иначе дефолт — chatgpt.com.
        if (!sp.getBoolean(KEY_SITE_OVERRIDES_V2, false)) {
            val old = sp.getStringSet(KEY_TURBO_EXCLUDED, null)
            val profiles = mutableMapOf<String, Map<String, Boolean>>()
            if (old != null) {
                for (h in old) profiles[h] = mapOf(FEATURE_CV to false, FEATURE_ANIM to false)
            }
            if (profiles.isEmpty()) {
                profiles["chatgpt.com"] = mapOf(FEATURE_CV to false, FEATURE_ANIM to false)
            }
            sp.edit {
                putStringSet(
                    KEY_SITE_OVERRIDES,
                    profiles.map { (h, f) ->
                        "$h=${f.map { (k, b) -> "$k:${if (b) 1 else 0}" }.joinToString(",")}"
                    }.toSet()
                )
                putBoolean(KEY_SITE_OVERRIDES_V2, true)
                remove(KEY_TURBO_EXCLUDED)
            }
        }
    }

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

    /** Базовый пакет оптимизации: lazy-картинки, async-декод, приоритет рендерера. */
    var optimizations: Boolean
        get() = sp.getBoolean(KEY_OPTIMIZATIONS, true)
        set(v) = sp.edit { putBoolean(KEY_OPTIMIZATIONS, v) }

    /** Турбо-пакет: агрессивные оптимизации, могут ломать отдельные функции сайтов. */
    var turboPack: Boolean
        get() = sp.getBoolean(KEY_TURBO_PACK, false)
        set(v) = sp.edit { putBoolean(KEY_TURBO_PACK, v) }

    /** Турбо: не рендерить секции вне экрана (content-visibility). */
    var turboContentVis: Boolean
        get() = sp.getBoolean(KEY_TURBO_CONTENT_VIS, false)
        set(v) = sp.edit { putBoolean(KEY_TURBO_CONTENT_VIS, v) }

    /** Турбо: отключить CSS-анимации и переходы. */
    var turboNoAnim: Boolean
        get() = sp.getBoolean(KEY_TURBO_NO_ANIM, false)
        set(v) = sp.edit { putBoolean(KEY_TURBO_NO_ANIM, v) }

    /** Турбо: отдавать популярные CDN-библиотеки из локального кэша. */
    var turboLibCache: Boolean
        get() = sp.getBoolean(KEY_TURBO_LIB_CACHE, false)
        set(v) = sp.edit { putBoolean(KEY_TURBO_LIB_CACHE, v) }

    /** GPU-облегчение (тени/blur/fixed-фоны выключены) — глобально, вне турбо. */
    var gpuEase: Boolean
        get() = sp.getBoolean(KEY_GPU_EASE, false)
        set(v) = sp.edit { putBoolean(KEY_GPU_EASE, v) }

    /**
     * Пер-сайт профили оптимизации: host → опция→значение (только отличия от
     * глобальных). Опции: base, cv, anim, gpu, lib.
     */
    var siteOverrides: Map<String, Map<String, Boolean>>
        get() {
            val raw = sp.getStringSet(KEY_SITE_OVERRIDES, emptySet())!!
            val out = mutableMapOf<String, Map<String, Boolean>>()
            for (line in raw) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val host = line.substring(0, eq)
                val flags = line.substring(eq + 1).split(',')
                    .mapNotNull { p ->
                        val c = p.indexOf(':')
                        if (c <= 0) null else p.substring(0, c) to (p.substring(c + 1) == "1")
                    }
                    .toMap()
                if (host.isNotEmpty()) out[host] = flags
            }
            return out
        }
        set(v) = sp.edit {
            putStringSet(
                KEY_SITE_OVERRIDES,
                v.map { (h, f) ->
                    "$h=${f.map { (k, b) -> "$k:${if (b) 1 else 0}" }.joinToString(",")}"
                }.toSet()
            )
        }

    /** Эффективное значение опции для хоста: профиль сайта ?: глобальное. */
    fun effectiveFeature(host: String, feature: String, global: Boolean): Boolean {
        return siteOverrides[host]?.get(feature) ?: global
    }

    var restoreTabs: Boolean
        get() = sp.getBoolean(KEY_RESTORE_TABS, false)
        set(v) = sp.edit { putBoolean(KEY_RESTORE_TABS, v) }

    var savedSession: String
        // URI открытых вкладок — приватность: отдельный SharedPreferences,
        // который не попадает в облачный бэкап и перенос (см. backup_rules).
        get() = spSession.getString(KEY_SAVED_SESSION, "")!!
        set(v) = spSession.edit { putString(KEY_SAVED_SESSION, v) }

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
        private const val KEY_LISTS_MIGRATED_V2 = "lists_migrated_v2"
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
        private const val KEY_OPTIMIZATIONS = "optimizations"
        private const val KEY_TURBO_PACK = "turbo_pack"
        private const val KEY_TURBO_CONTENT_VIS = "turbo_content_vis"
        private const val KEY_TURBO_NO_ANIM = "turbo_no_anim"
        private const val KEY_TURBO_LIB_CACHE = "turbo_lib_cache"
        private const val KEY_GPU_EASE = "gpu_ease"
        private const val KEY_SITE_OVERRIDES = "site_overrides"
        private const val KEY_SITE_OVERRIDES_V2 = "site_overrides_v2"
        private const val KEY_TURBO_EXCLUDED = "turbo_excluded"

        const val FEATURE_BASE = "base"
        const val FEATURE_CV = "cv"
        const val FEATURE_ANIM = "anim"
        const val FEATURE_GPU = "gpu"
        const val FEATURE_LIB = "lib"
        private const val KEY_RESTORE_TABS = "restore_tabs"
        private const val KEY_SAVED_SESSION = "saved_session"
    }
}
