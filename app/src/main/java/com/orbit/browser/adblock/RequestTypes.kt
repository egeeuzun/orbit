package com.orbit.browser.adblock

import android.webkit.WebResourceRequest

/**
 * WebView bize `$script` / `$image` gibi kaynak türünü doğrudan vermez,
 * ama Chromium `Sec-Fetch-Dest` başlığını gönderir. Motor kural türlerini
 * (`$script`, `$xmlhttprequest`, ...) buna göre değerlendirir; başlık yoksa
 * `Accept` ve uzantı üzerinden tahmin edilir.
 */
object RequestTypes {

    fun of(request: WebResourceRequest, url: String): String {
        if (request.isForMainFrame) return "document"

        val headers = request.requestHeaders
        headers["Sec-Fetch-Dest"]?.let { dest ->
            fromFetchDest(dest)?.let { return it }
        }
        headers["sec-fetch-dest"]?.let { dest ->
            fromFetchDest(dest)?.let { return it }
        }

        headers["Accept"]?.let { accept ->
            fromAccept(accept)?.let { return it }
        }

        return fromExtension(url)
    }

    /**
     * https://developer.mozilla.org/docs/Web/HTTP/Headers/Sec-Fetch-Dest
     *
     * Değer belirtimde küçük harflidir; önce olduğu gibi denenir, böylece
     * istek başına bir `lowercase()` kopyası çıkmaz.
     */
    private fun fromFetchDest(dest: String): String? =
        matchFetchDest(dest) ?: matchFetchDest(dest.lowercase())

    private fun matchFetchDest(dest: String): String? = when (dest) {
        "script", "worker", "sharedworker", "serviceworker" -> "script"
        "style" -> "stylesheet"
        "image" -> "image"
        "font" -> "font"
        "audio", "video", "track" -> "media"
        "iframe", "frame" -> "sub_frame"
        "document" -> "document"
        "empty" -> "xhr"
        "object", "embed" -> "object"
        "manifest" -> "other"
        "report" -> "csp_report"
        else -> null
    }

    private fun fromAccept(accept: String): String? = when {
        accept.startsWith("text/css") -> "stylesheet"
        accept.startsWith("image/") || accept.contains("image/webp") -> "image"
        accept.contains("text/html") -> "sub_frame"
        accept.startsWith("audio/") || accept.startsWith("video/") -> "media"
        else -> null
    }

    private fun fromExtension(url: String): String {
        val q = url.indexOf('?')
        val path = if (q > 0) url.substring(0, q) else url
        val dot = path.lastIndexOf('.')
        if (dot < 0 || path.length - dot > 6) return "other"
        return when (path.substring(dot + 1).lowercase()) {
            "js", "mjs" -> "script"
            "css" -> "stylesheet"
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif" -> "image"
            "woff", "woff2", "ttf", "otf", "eot" -> "font"
            "mp4", "webm", "m4a", "mp3", "ogg", "m3u8", "ts" -> "media"
            "swf" -> "object"
            "json", "xml" -> "xhr"
            else -> "other"
        }
    }
}
