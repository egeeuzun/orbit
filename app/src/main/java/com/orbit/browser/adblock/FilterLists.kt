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
        // --------------------- Küresel çekirdek (öntanımlı açık) ------------------
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
            "adguard-base", "AdGuard — İngilizce/temel",
            "https://filters.adtidy.org/extension/ublock/filters/2.txt", true
        ),
        FilterList(
            "adguard-tracking", "AdGuard — İzleyici koruması",
            "https://filters.adtidy.org/extension/ublock/filters/3.txt", true
        ),
        FilterList(
            "adguard-mobile", "AdGuard — Mobil reklamlar",
            "https://filters.adtidy.org/extension/ublock/filters/11.txt", true
        ),
        FilterList(
            "adguard-annoyances", "AdGuard — Rahatsız edici öğeler",
            "https://filters.adtidy.org/extension/ublock/filters/14.txt", false
        ),
        FilterList(
            "adguard-social", "AdGuard — Sosyal medya",
            "https://filters.adtidy.org/extension/ublock/filters/4.txt", false
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
        ),
        FilterList(
            "fanboy-annoyance", "Fanboy — genel rahatsızlık",
            "https://secure.fanboy.co.nz/fanboy-annoyance.txt", false
        ),

        // --------------------- Yerel/dile özgü listeler (öntanımlı açık: TR/RU) ---
        FilterList(
            "adguard-turkish", "AdGuard — Türkçe filtreler",
            "https://filters.adtidy.org/extension/ublock/filters/13.txt", true
        ),
        FilterList(
            "adguard-russian", "AdGuard — Rusça filtreler",
            "https://filters.adtidy.org/extension/ublock/filters/1.txt", true
        ),
        FilterList(
            "ruadlist", "RU AdList (EasyList Rusya)",
            "https://easylist-downloads.adblockplus.org/advblock.txt", true
        ),
        FilterList(
            "adguard-german", "AdGuard — Almanca filtreler",
            "https://filters.adtidy.org/extension/ublock/filters/6.txt", false
        ),
        FilterList(
            "adguard-french", "AdGuard — Fransızca filtreler",
            "https://filters.adtidy.org/extension/ublock/filters/16.txt", false
        ),
        FilterList(
            "adguard-spanish-portuguese", "AdGuard — İspanyolca/Portekizce",
            "https://filters.adtidy.org/extension/ublock/filters/9.txt", false
        ),
        FilterList(
            "adguard-japanese", "AdGuard — Japonca filtreler",
            "https://filters.adtidy.org/extension/ublock/filters/7.txt", false
        ),
        FilterList(
            "adguard-dutch", "AdGuard — Felemenkçe filtreler",
            "https://filters.adtidy.org/extension/ublock/filters/8.txt", false
        ),
        FilterList(
            "easylist-italy", "EasyList — İtalyanca",
            "https://easylist-downloads.adblockplus.org/easylistitaly.txt", false
        ),
        FilterList(
            "easylist-china", "EasyList — Çince",
            "https://easylist-downloads.adblockplus.org/easylistchina.txt", false
        ),
        FilterList(
            "easylist-poland", "EasyList — Lehçe",
            "https://easylist-downloads.adblockplus.org/easylistpolish.txt", false
        ),
        FilterList(
            "abpindo", "ABPindo (Endonezyaca)",
            "https://raw.githubusercontent.com/ABPindo/indonesianadblockrules/master/subscriptions/abpindo.txt", false
        ),
        FilterList(
            "koreanlist", "YousList (Korece)",
            "https://raw.githubusercontent.com/yous/YousList/master/youslist.txt", false
        )
    )

    /** uBlock Origin'in scriptlet paketi (`##+js(...)` kuralları bunu gerektirir). */
    const val SCRIPTLETS_URL =
        "https://ublockorigin.github.io/uAssets/resources/scriptlets.js"

    fun byId(id: String): FilterList? = CATALOG.firstOrNull { it.id == id }

    val defaultIds: Set<String> get() = CATALOG.filter { it.defaultOn }.map { it.id }.toSet()
}
