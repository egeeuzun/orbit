package com.orbit.browser.util

import android.net.Uri
import android.util.Patterns
import java.util.Locale

object UrlUtils {

    /**
     * Adres çubuğuna yazılanı ya URL'ye ya da arama sorgusuna çevirir.
     * Jelly'deki davranışın aynısı: boşluk içeriyorsa veya geçerli bir alan
     * adına benzemiyorsa arama motoruna gider.
     */
    fun normalizeOrSearch(input: String, searchTemplate: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""

        if (text.startsWith("about:") || text.startsWith("javascript:") ||
            text.startsWith("data:") || text.startsWith("file:") ||
            text.startsWith("content:")
        ) return text

        val hasScheme = text.startsWith("http://", true) ||
            text.startsWith("https://", true) ||
            text.startsWith("ftp://", true)

        if (hasScheme) return text

        val looksLikeHost = !text.contains(' ') &&
            (Patterns.WEB_URL.matcher(text).matches() || text.matches(IP_PORT))

        if (looksLikeHost) return "https://$text"

        return searchTemplate.replace("%s", Uri.encode(text))
    }

    private val IP_PORT = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""")

    fun host(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            Uri.parse(url).host?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** Adres çubuğunda gösterilecek sade biçim: şema ve `www.` atılır. */
    fun forDisplay(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        var s = url
        for (p in arrayOf("https://", "http://")) if (s!!.startsWith(p)) { s = s.substring(p.length); break }
        if (s!!.startsWith("www.")) s = s.substring(4)
        return s.removeSuffix("/")
    }

    fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))

    /** Kullanıcıya gösterilecek sekme başlığı. */
    fun titleOrHost(title: String?, url: String?): String =
        if (!title.isNullOrBlank()) title else forDisplay(url).ifEmpty { "Yeni sekme" }
}
