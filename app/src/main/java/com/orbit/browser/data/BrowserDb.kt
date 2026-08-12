package com.orbit.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors

/**
 * Geçmiş ve yer imleri. Room yerine düz SQLite: hem APK'yı küçük tutar hem
 * de açılışta ek sınıf yükleme maliyeti çıkarmaz.
 */
class BrowserDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "browser-db").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Sorguyu arka planda çalıştırır, sonucu ana iş parçacığında verir. */
    private fun <T> async(work: () -> T, then: (T) -> Unit) {
        io.execute {
            val result = try {
                work()
            } catch (t: Throwable) {
                return@execute
            }
            main.post { then(result) }
        }
    }

    /** Adres çubuğu önerileri — her tuş vuruşunda çağrıldığı için ana iş parçacığından uzak. */
    fun suggestAsync(query: String, then: (List<Entry>) -> Unit) =
        async({ suggest(query) }, then)

    /** Adres çubuğu önerileri: yerel geçmiş + isteğe bağlı seçili arama motoru önerileri. */
    fun suggestWithOnlineAsync(
        query: String,
        includeOnline: Boolean,
        engineUrl: String,
        then: (List<Entry>) -> Unit
    ) = async({
        val local = suggest(query, limit = 5)
        if (!includeOnline) {
            local
        } else {
            val online = SearchSuggestions.fetch(query, engineUrl)
            val combined = ArrayList<Entry>(local.size + online.size)
            combined.addAll(local)
            for (item in online) {
                if (local.none { it.title.equals(item.title, ignoreCase = true) }) {
                    combined.add(item)
                }
            }
            combined.take(7)
        }
    }, then)

    /** Başlangıç ekranı kısayolları. */
    fun topSitesAsync(limit: Int, then: (List<Entry>) -> Unit) =
        async({ topSites(limit) }, then)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT NOT NULL," +
                "title TEXT," +
                "visits INTEGER NOT NULL DEFAULT 1," +
                "last_visit INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX history_url ON history(url)")
        db.execSQL("CREATE INDEX history_time ON history(last_visit DESC)")
        db.execSQL(
            "CREATE TABLE bookmarks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT NOT NULL," +
                "title TEXT," +
                "created INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX bookmarks_url ON bookmarks(url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS history")
        db.execSQL("DROP TABLE IF EXISTS bookmarks")
        onCreate(db)
    }

    // -------------------------------------------------------------------- geçmiş

    fun recordVisit(url: String, title: String?) {
        if (url.isBlank()) return
        io.execute {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val updated = db.compileStatement(
                "UPDATE history SET visits = visits + 1, last_visit = ?, " +
                    "title = COALESCE(NULLIF(?, ''), title) WHERE url = ?"
            ).use { st ->
                st.bindLong(1, now)
                st.bindString(2, title.orEmpty())
                st.bindString(3, url)
                st.executeUpdateDelete()
            }
            if (updated == 0) {
                db.insertWithOnConflict(
                    "history", null,
                    ContentValues().apply {
                        put("url", url)
                        put("title", title.orEmpty())
                        put("last_visit", now)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        }
    }

    /** Adres çubuğu önerileri: sık ve yeni ziyaret edilenler önce. */
    fun suggest(query: String, limit: Int = 6): List<Entry> {
        if (query.isBlank()) return emptyList()
        val like = "%$query%"
        val out = ArrayList<Entry>(limit)
        readableDatabase.rawQuery(
            "SELECT url, title FROM history WHERE url LIKE ? OR title LIKE ? " +
                "ORDER BY visits DESC, last_visit DESC LIMIT ?",
            arrayOf(like, like, limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1).orEmpty()))
        }
        return out
    }

    fun history(limit: Int = 300): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT url, title FROM history ORDER BY last_visit DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1).orEmpty()))
        }
        return out
    }

    /** Başlangıç ekranındaki kısayollar: en çok ziyaret edilen siteler. */
    fun topSites(limit: Int = 12): List<Entry> {
        val out = ArrayList<Entry>(limit)
        val seen = HashSet<String>(limit * 2)
        readableDatabase.rawQuery(
            "SELECT url, title FROM history ORDER BY visits DESC, last_visit DESC LIMIT ?",
            arrayOf((limit * 6).toString())
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val url = c.getString(0)
                // Aynı siteden tek girdi yeter; ızgara alan adı bazlıdır.
                val host = com.orbit.browser.util.UrlUtils.host(url)
                if (host.isEmpty() || !seen.add(host)) continue
                out.add(Entry(url, c.getString(1).orEmpty()))
            }
        }
        return out
    }

    fun clearHistory() {
        io.execute { writableDatabase.delete("history", null, null) }
    }

    // ----------------------------------------------------------------- yer imleri

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)
        ).use { it.moveToFirst() }

    fun addBookmark(url: String, title: String?) {
        io.execute {
            writableDatabase.insertWithOnConflict(
                "bookmarks", null,
                ContentValues().apply {
                    put("url", url)
                    put("title", title.orEmpty())
                    put("created", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    fun removeBookmark(url: String) {
        io.execute { writableDatabase.delete("bookmarks", "url = ?", arrayOf(url)) }
    }

    fun bookmarks(): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT url, title FROM bookmarks ORDER BY created DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1).orEmpty()))
        }
        return out
    }

    data class Entry(val url: String, val title: String)

    companion object {
        private const val NAME = "orbit.db"
        private const val VERSION = 1

        @Volatile
        private var instance: BrowserDb? = null

        fun get(context: Context): BrowserDb =
            instance ?: synchronized(this) {
                instance ?: BrowserDb(context).also { instance = it }
            }
    }
}
