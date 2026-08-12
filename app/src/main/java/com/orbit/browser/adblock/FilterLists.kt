package com.orbit.browser.adblock

/**
 * Varsayılan abonelikler. uBlock Origin'in "built-in" listelerinin aynısı;
 * motor da aynı sözdizimini okuduğu için kural davranışı birebir aynı.
 */
data class FilterList(
    val id: String,
    val title: String,
    val url: String,
    val defaultOn: Boolean
)

object FilterLists {

    val CATALOG: List<FilterList> = listOf(
        FilterList(
            "ubo-filters", "uBlock Origin — temel",
            "https://ublockorigin.github.io/uAssets/filters/filters.txt", true
        ),
        FilterList(
            "ubo-badware", "uBlock Origin — zararlı siteler",
            "https://ublockorigin.github.io/uAssets/filters/badware.txt", true
        ),
        FilterList(
            "ubo-privacy", "uBlock Origin — gizlilik",
            "https://ublockorigin.github.io/uAssets/filters/privacy.txt", true
        ),
        FilterList(
            "ubo-quick-fixes", "uBlock Origin — hızlı düzeltmeler",
            "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt", true
        ),
        FilterList(
            "ubo-unbreak", "uBlock Origin — bozulma onarımı",
            "https://ublockorigin.github.io/uAssets/filters/unbreak.txt", true
        ),
        FilterList(
            "easylist", "EasyList",
            "https://easylist.to/easylist/easylist.txt", true
        ),
        FilterList(
            "easyprivacy", "EasyPrivacy",
            "https://easylist.to/easylist/easyprivacy.txt", true
        ),
        FilterList(
            "peter-lowe", "Peter Lowe — reklam/izleyici sunucuları",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&mimetype=plaintext", true
        ),
        FilterList(
            "adguard-turkish", "AdGuard — Türkçe filtreler",
            "https://filters.adtidy.org/extension/ublock/filters/13.txt", true
        ),
        FilterList(
            "easylist-cookie", "EasyList Cookie (çerez uyarıları)",
            "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", false
        ),
        FilterList(
            "ubo-annoyances", "uBlock Origin — rahatsız edici öğeler",
            "https://ublockorigin.github.io/uAssets/filters/annoyances.txt", false
        ),
        FilterList(
            "fanboy-social", "Fanboy — sosyal medya butonları",
            "https://easylist.to/easylist/fanboy-social.txt", false
        )
    )

    /** uBlock Origin'in scriptlet paketi (`##+js(...)` kuralları bunu gerektirir). */
    const val SCRIPTLETS_URL =
        "https://ublockorigin.github.io/uAssets/resources/scriptlets.js"

    fun byId(id: String): FilterList? = CATALOG.firstOrNull { it.id == id }

    val defaultIds: Set<String> get() = CATALOG.filter { it.defaultOn }.map { it.id }.toSet()
}
