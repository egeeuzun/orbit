package com.orbit.browser.adblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.orbit.browser.data.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Filtre listelerinin indirilmesi, derlenmesi ve motorun yaşam döngüsü.
 *
 * Soğuk açılışta 3 MB'lık metni yeniden ayrıştırmak zayıf cihazlarda
 * saniyeler sürdüğü için derlenmiş motor diske serileştirilir; sonraki
 * açılışlarda yalnızca bu ikili dosya okunur.
 */
class AdblockService private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = Prefs(app)

    /**
     * İndirme ve derleme. Arka plan önceliğiyle çalışır: soğuk açılışta
     * 4 MB'lık listeyi ayrıştırırken kullanıcı arayüzü iş parçacığıyla eşit
     * öncelikte yarışması, zayıf cihazda ilk karenin gecikmesi demekti.
     */
    private val io = background("adblock", Process.THREAD_PRIORITY_BACKGROUND)

    /**
     * Sayfa çalışırken yapılan motor sorguları (genel kozmetik seçiciler).
     * İndirmeyle aynı sıraya girmemesi için ayrı; tek iş parçacığı olduğu
     * için sorgular kendiliğinden birikmeden sırayla işlenir. Sonucu sayfa
     * beklediğinden indirmeden bir tık öncelikli.
     */
    private val queries = background("adblock-query", Process.THREAD_PRIORITY_DEFAULT + 2)

    private val main = Handler(Looper.getMainLooper())

    /** İstek başına verilen kararların önbelleği; motor değişince temizlenir. */
    val decisions = DecisionCache()

    private val filtersDir = File(app.filesDir, "filters")
    private val engineFile = File(app.filesDir, "engine.bin")
    private val hostsFile = File(app.filesDir, "hosts.bin")

    @Volatile
    var engine: AdblockEngine = AdblockEngine.empty()
        private set

    /**
     * Saf ana bilgisayar kurallarının hızlı yolu. Motordan **önce** kurulur:
     * ~670 KB'lık tablo milisaniyeler içinde okunurken tam motor bu cihaz
     * sınıfında ~900 ms sürüyor. Böylece motor hazırlanırken de engelleme
     * çalışır.
     */
    @Volatile
    var hosts: HostBlocklist = HostBlocklist.EMPTY
        private set

    @Volatile
    var state: State = State.IDLE
        private set

    private val blocked = AtomicLong(prefs.blockedCount)
    private val listeners = ArrayList<(State) -> Unit>()

    enum class State { IDLE, LOADING, READY, FAILED }

    val blockedTotal: Long get() = blocked.get()

    /**
     * Ağ iş parçacığından çağrılır. Sayaç bellekte tutulur; diske yazım
     * seyrekleştirildi ve uygulamadan çıkarken [flushCounters] ile tamamlanır.
     */
    fun onBlocked() {
        val n = blocked.incrementAndGet()
        if (n % 250L == 0L) prefs.blockedCount = n
    }

    /** Uygulama arka plana alınırken sayaç kalıcı hale getirilir. */
    fun flushCounters() {
        prefs.blockedCount = blocked.get()
    }

    /**
     * Sayfa çalışırken yapılan motor sorgusunu arka plana taşır.
     * Sonucu ana iş parçacığına döndürmek çağıranın işi ([postMain]).
     */
    fun query(block: () -> Unit) {
        queries.execute(block)
    }

    fun postMain(block: () -> Unit) {
        main.post(block)
    }

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun publish(s: State) {
        state = s
        main.post { for (l in ArrayList(listeners)) l(s) }
    }

    /** Uygulama açılışında çağrılır; hızlı yol diskteki derlenmiş motordur. */
    fun start() {
        if (state == State.LOADING) return
        publish(State.LOADING)
        io.execute {
            try {
                val stamp = currentStamp()
                // Hızlı yol tam motordan önce kurulur.
                loadHostsCache(stamp)
                if (stamp == prefs.engineStamp && engineFile.isFile) {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val bytes = engineFile.readBytes()
                    val tRead = android.os.SystemClock.elapsedRealtime()
                    val cached = AdblockEngine.fromCache(bytes)
                    if (cached != null) {
                        val tDone = android.os.SystemClock.elapsedRealtime()
                        Log.i(
                            TAG,
                            "motor önbellekten kuruldu: ${bytes.size / 1024} KB, " +
                                "okuma ${tRead - t0} ms, çözme ${tDone - tRead} ms, " +
                                "toplam ${tDone - t0} ms"
                        )
                        install(cached)
                        publish(State.READY)
                        // Motor önbellekten geldiğinde yeniden derleme yolu
                        // hiç çalışmaz; hızlı yol tablosu yoksa (sürüm
                        // yükseltmesi) burada bir kez üretilir.
                        ensureHosts(stamp)
                        maybeAutoUpdate()
                        return@execute
                    }
                    Log.w(TAG, "Önbellek okunamadı, listeler yeniden derleniyor")
                }
                if (!rebuildFromDisk(stamp)) {
                    // Hiç liste yok: ilk çalıştırma. İndirip derle.
                    refreshNow(force = true)
                } else {
                    maybeAutoUpdate()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Motor kurulamadı", t)
                publish(State.FAILED)
            }
        }
    }

    /** Listeleri indirir ve motoru yeniden derler. */
    fun refresh(onDone: ((Boolean) -> Unit)? = null) {
        io.execute {
            val ok = try {
                refreshNow(force = true)
            } catch (t: Throwable) {
                Log.e(TAG, "Güncelleme başarısız", t)
                false
            }
            onDone?.let { cb -> main.post { cb(ok) } }
        }
    }

    /** Ayar değişikliğinden sonra indirmeden yeniden derler. */
    fun rebuild() {
        io.execute {
            try {
                rebuildFromDisk(currentStamp())
            } catch (t: Throwable) {
                Log.e(TAG, "Yeniden derleme başarısız", t)
            }
        }
    }

    // ------------------------------------------------------------------ iç işleyiş

    private fun maybeAutoUpdate() {
        val age = System.currentTimeMillis() - prefs.lastListUpdate
        if (age > UPDATE_INTERVAL_MS) {
            io.execute {
                try {
                    refreshNow(force = false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Otomatik güncelleme atlandı: ${t.message}")
                }
            }
        }
    }

    private fun refreshNow(force: Boolean): Boolean {
        publish(State.LOADING)
        filtersDir.mkdirs()
        var downloaded = 0
        for (id in prefs.enabledLists) {
            val list = FilterLists.byId(id) ?: continue
            val target = File(filtersDir, "$id.txt")
            if (!force && target.isFile &&
                System.currentTimeMillis() - target.lastModified() < UPDATE_INTERVAL_MS
            ) continue
            try {
                val text = fetchWithIncludes(list.url, 0)
                if (text.length > MIN_LIST_BYTES) {
                    target.writeText(text)
                    downloaded++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "${list.id} indirilemedi: ${t.message}")
            }
        }
        for (url in prefs.customFilterUrls) {
            if (url.isBlank()) continue
            val id = "custom_" + url.hashCode().toUInt().toString(16)
            val target = File(filtersDir, "$id.txt")
            if (!force && target.isFile &&
                System.currentTimeMillis() - target.lastModified() < UPDATE_INTERVAL_MS
            ) continue
            try {
                val text = fetchWithIncludes(url, 0)
                if (text.length > MIN_LIST_BYTES) {
                    target.writeText(text)
                    downloaded++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Özel liste $url indirilemedi: ${t.message}")
            }
        }
        if (downloaded > 0) prefs.lastListUpdate = System.currentTimeMillis()
        return rebuildFromDisk(currentStamp())
    }

    /**
     * Motoru diskteki listelerden derler.
     * @return kullanılabilir bir motor kurulduysa true
     */
    private fun rebuildFromDisk(stamp: String): Boolean {
        val (text, used) = readRules()
        // Hızlı yol motordan önce üretilir: derleme uzun sürerken de
        // engelleme çalışsın.
        buildHosts(text)

        val built = AdblockEngine.fromRules(text)
        if (built == null) {
            publish(State.FAILED)
            return false
        }

        // Serileştirme kurulumdan ÖNCE yapılmalı: [install] tutamacın sahipliğini
        // çalışan motora devreder ve [built] geçersiz hale gelir.
        val snapshot = try {
            built.serialize()
        } catch (t: Throwable) {
            Log.w(TAG, "Motor serileştirilemedi: ${t.message}")
            null
        }

        install(built)

        // Derlenmiş motoru sakla: sonraki açılış saniyeler yerine milisaniye sürer.
        // Yazamazsak damga temizlenir; aksi halde açılışta bayat önbellek
        // güncel sanılıp yüklenirdi.
        if (snapshot != null) {
            try {
                engineFile.writeBytes(snapshot)
                prefs.engineStamp = stamp
            } catch (t: Throwable) {
                Log.w(TAG, "Motor önbelleğe yazılamadı: ${t.message}")
                prefs.engineStamp = ""
            }
        } else {
            prefs.engineStamp = ""
        }
        publish(State.READY)
        return used > 0
    }

    /**
     * Etkin listelerin metnini birleştirir.
     * @return metin ve kullanılan liste sayısı (0 ise paket içi liste kullanıldı)
     */
    private fun readRules(): Pair<String, Int> {
        val sb = StringBuilder(4 shl 20)
        var used = 0
        for (id in prefs.enabledLists) {
            val f = File(filtersDir, "$id.txt")
            if (!f.isFile) continue
            sb.append(f.readText()).append('\n')
            used++
        }
        for (url in prefs.customFilterUrls) {
            val id = "custom_" + url.hashCode().toUInt().toString(16)
            val f = File(filtersDir, "$id.txt")
            if (!f.isFile) continue
            sb.append(f.readText()).append('\n')
            used++
        }
        // Paket içindeki başlangıç listesi: ilk açılışta ağ beklenmeden korur.
        if (used == 0) {
            sb.append(app.assets.open(BUNDLED_LIST).bufferedReader().use { it.readText() })
        }
        prefs.customRules.takeIf { it.isNotBlank() }?.let { sb.append('\n').append(it) }
        return sb.toString() to used
    }

    /**
     * Hızlı yol tablosu yoksa listelerden bir kez üretir. Motor zaten
     * kurulmuş olduğu için aceleye gerek yok; sıradaki işlerin arkasına
     * konur.
     */
    private fun ensureHosts(stamp: String) {
        if (hosts.size > 0 || stamp != prefs.engineStamp) return
        io.execute {
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                buildHosts(readRules().first)
                Log.i(
                    TAG,
                    "hızlı yol listelerden üretildi: ${hosts.size} ana bilgisayar, " +
                        "${android.os.SystemClock.elapsedRealtime() - t0} ms"
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Hızlı yol üretilemedi: ${t.message}")
            }
        }
    }

    /**
     * Hızlı yol tablosunu diskten kurar. Damga tutmuyorsa hiçbir şey
     * yapılmaz: tablo birazdan [rebuildFromDisk] içinde yeniden üretilecek.
     */
    private fun loadHostsCache(stamp: String) {
        if (stamp != prefs.engineStamp || !hostsFile.isFile) return
        try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val table = HostBlocklist.fromBytes(hostsFile.readBytes())
            hosts = table
            Log.i(
                TAG,
                "hızlı yol kuruldu: ${table.size} ana bilgisayar, " +
                    "${android.os.SystemClock.elapsedRealtime() - t0} ms"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Hızlı yol okunamadı: ${t.message}")
        }
    }

    /** Hızlı yol tablosunu filtre metninden üretip diske yazar. */
    private fun buildHosts(rules: String) {
        val bytes = try {
            NativeAdblock.nativeHostsFromRules(rules)
        } catch (t: Throwable) {
            Log.w(TAG, "Hızlı yol üretilemedi: ${t.message}")
            null
        } ?: return
        hosts = HostBlocklist.fromBytes(bytes)
        try {
            hostsFile.writeBytes(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "Hızlı yol yazılamadı: ${t.message}")
        }
    }

    private fun install(built: AdblockEngine) {
        val current = engine
        if (current.isValid()) current.swap(built) else engine = built
        // Eski motorun kararları artık geçerli olmayabilir.
        decisions.clear()
    }

    /**
     * `!#include <dosya>` yönergelerini çözer. uBlock Origin'in ana listesi
     * içeriğinin çoğunu bu şekilde parçalara ayırdığı için, çözülmezse
     * listenin neredeyse tamamı kaybolur.
     */
    private fun fetchWithIncludes(url: String, depth: Int): String {
        val body = httpGet(url)
        if (depth >= MAX_INCLUDE_DEPTH || !body.contains("!#include")) return body

        val base = url.substringBeforeLast('/', "")
        val out = StringBuilder(body.length * 2)
        for (line in body.lineSequence()) {
            if (line.startsWith("!#include ")) {
                val rel = line.removePrefix("!#include ").trim()
                if (rel.isEmpty() || rel.contains("..") || base.isEmpty()) continue
                try {
                    out.append(fetchWithIncludes("$base/$rel", depth + 1)).append('\n')
                } catch (t: Throwable) {
                    Log.w(TAG, "include alınamadı: $rel (${t.message})")
                }
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("User-Agent", "Orbit-Browser")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(raw)
            } else raw
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Motorun hangi girdiden derlendiğini özetler; değişirse önbellek geçersizdir. */
    private fun currentStamp(): String {
        val sb = StringBuilder(ENGINE_FORMAT)
        for (id in prefs.enabledLists.sorted()) {
            val f = File(filtersDir, "$id.txt")
            sb.append('|').append(id).append(':').append(f.length()).append('@').append(f.lastModified())
        }
        for (url in prefs.customFilterUrls.sorted()) {
            val id = "custom_" + url.hashCode().toUInt().toString(16)
            val f = File(filtersDir, "$id.txt")
            sb.append('|').append(id).append(':').append(f.length()).append('@').append(f.lastModified())
        }
        sb.append("|customRules:").append(prefs.customRules.hashCode())
        return sb.toString()
    }

    companion object {
        private const val TAG = "Adblock"

        /** Ana iş parçacığından düşük öncelikli, tek iş parçacıklı havuz. */
        private fun background(name: String, priority: Int) =
            Executors.newSingleThreadExecutor { r ->
                Thread({
                    Process.setThreadPriority(priority)
                    r.run()
                }, name).apply { isDaemon = true }
            }

        /** Serileştirme biçimi değişirse artırılır; eski önbellekleri geçersiz kılar. */
        private const val ENGINE_FORMAT = "v1"

        private const val BUNDLED_LIST = "default_filters.txt"
        private const val UPDATE_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000
        private const val MIN_LIST_BYTES = 256

        /** Bir liste kendi içinde başka listeleri çağırabilir; döngüye karşı sınır. */
        private const val MAX_INCLUDE_DEPTH = 3

        @Volatile
        private var instance: AdblockService? = null

        fun get(context: Context): AdblockService =
            instance ?: synchronized(this) {
                instance ?: AdblockService(context).also { instance = it }
            }
    }
}
