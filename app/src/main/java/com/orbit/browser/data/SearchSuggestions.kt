package com.orbit.browser.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Seçili arama motorunun OpenSearch API'sinden canlı arama önerileri çeker.
 */
object SearchSuggestions {

    fun fetch(query: String, searchEngineUrl: String): List<BrowserDb.Entry> {
        if (query.isBlank()) return emptyList()
        val encoded = try {
            URLEncoder.encode(query.trim(), "UTF-8")
        } catch (_: Exception) {
            return emptyList()
        }

        val suggestUrl = when {
            searchEngineUrl.contains("google") ->
                "https://suggestqueries.google.com/complete/search?client=chrome&q=$encoded"
            searchEngineUrl.contains("bing") ->
                "https://api.bing.com/osjson.aspx?query=$encoded"
            searchEngineUrl.contains("duckduckgo") ->
                "https://duckduckgo.com/ac/?q=$encoded&type=list"
            searchEngineUrl.contains("brave") ->
                "https://search.brave.com/api/suggest?q=$encoded"
            searchEngineUrl.contains("startpage") ->
                "https://www.startpage.com/do/complete?query=$encoded"
            searchEngineUrl.contains("yandex") ->
                "https://suggest.yandex.com/suggest-ff.html?part=$encoded"
            else ->
                "https://suggestqueries.google.com/complete/search?client=chrome&q=$encoded"
        }

        return try {
            val conn = URL(suggestUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) return emptyList()

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonStr)
            if (jsonArray.length() < 2) return emptyList()

            val suggestionsArray = jsonArray.getJSONArray(1)
            val list = ArrayList<BrowserDb.Entry>()
            val maxCount = suggestionsArray.length().coerceAtMost(5)

            for (i in 0 until maxCount) {
                val suggestion = suggestionsArray.getString(i)
                if (suggestion.isNotBlank()) {
                    val encodedSugg = URLEncoder.encode(suggestion, "UTF-8")
                    val targetUrl = String.format(searchEngineUrl, encodedSugg)
                    list.add(BrowserDb.Entry(url = targetUrl, title = suggestion))
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
