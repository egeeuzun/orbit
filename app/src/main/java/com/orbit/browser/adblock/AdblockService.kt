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
                    // Dosya yerel tarafta okunur: 12 MB'lık bayt dizisi
                    // her soğuk açılışta Java yığınından geçmez.
                    val cached = AdblockEngine.fromCacheFile(engineFile)
                    if (cached != null) {
                        val tDone = android.os.SystemClock.elapsedRealtime()
                        Log.i(
                            TAG,
                            "motor önbellekten kuruldu: ${engineFile.length() / 1024} KB, " +
                                "toplam ${tDone - t0} ms"
                        )
                        install(cached)
                        releaseFreeMemory()
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
                // Aynı girdiyle başlayan son derleme bitmeden süreç öldü
                // (çoğunlukla OOM). Açılışta yeniden denemek her açılışta
                // aynı çöküş demekti. Hızlı yol tablosu yine engeller; liste
                // seçimi ya da içeriği değişince (damga değişir) ya da
                // kullanıcı elle güncelleyince derleme yeniden denenir.
                if (prefs.pendingBuildStamp == stamp) {
                    Log.w(TAG, "Önceki derleme tamamlanmadı; otomatik yeniden deneme atlandı")
                    buildHosts(ruleSources())
                    publish(State.FAILED)
                    return@execute
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
                if (download(list.url, target)) downloaded++
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
                if (download(url, target)) downloaded++
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
        val sources = ruleSources()
        // Hızlı yol motordan önce üretilir: derleme uzun sürerken de
        // engelleme çalışsın.
        buildHosts(sources)

        // Süreç derleme sırasında ölürse işaret kalır; [start] onu görüp
        // aynı girdiyle yeniden denemez. Süreç yaşadığı sürece (istisna
        // dahil) işaret kaldırılır.
        prefs.pendingBuildStamp = stamp
        try {
            val built = AdblockEngine.fromFiles(sources.paths, sources.extra)
            if (built == null) {
                publish(State.FAILED)
                return false
            }

            // Serileştirme kurulumdan ÖNCE yapılmalı: [install] tutamacın sahipliğini
            // çalışan motora devreder ve [built] geçersiz hale gelir.
            // Derlenmiş motoru sakla: sonraki açılış saniyeler yerine milisaniye sürer.
            // Yazamazsak damga temizlenir; aksi halde açılışta bayat önbellek
            // güncel sanılıp yüklenirdi.
            val saved = try {
                built.serializeTo(engineFile)
            } catch (t: Throwable) {
                Log.w(TAG, "Motor önbelleğe yazılamadı: ${t.message}")
                false
            }
            prefs.engineStamp = if (saved) stamp else ""

            // Önbellek yazıldıysa motor ondan, eşlenmiş dosyadan yeniden
            // kurulur ve derlenen kopya bırakılır. Yoksa derlemenin yapıldığı
            // oturum boyunca motor 12 MB'lık kendi kopyasını ve derlemeden
            // kalan parçalanmayı taşıyordu (SM-T320: native heap 56 MB;
            // yeniden açılışta 15 MB). Liste güncellemesi 3 günde bir ve her
            // liste ayarı değişikliğinde bu yoldan geçiliyor.
            val mapped = if (saved) AdblockEngine.fromCacheFile(engineFile) else null
            if (mapped != null) {
                built.close()
                install(mapped)
            } else {
                install(built)
            }
            publish(State.READY)
            return sources.used > 0
        } finally {
            prefs.pendingBuildStamp = ""
            // Derlemenin geçici yapıları ve (varsa) eski motor serbest
            // bırakıldı; ayırıcı o sayfaları kendiliğinden geri vermiyor.
            releaseFreeMemory()
        }
    }

    /**
     * Yerel ayırıcının elindeki boş sayfaları sisteme iade eder. Derleme ve
     * önbellekten kurulum sonrasında, bir de sistem bellek istediğinde çağrılır.
     */
    fun releaseFreeMemory() {
        if (!NativeAdblock.available) return
        try {
            NativeAdblock.nativeReleaseFreeMemory()
        } catch (_: Throwable) {
            // Eski .so: sembol yok, iade atlanır.
        }
    }

    /**
     * Motorun girdisi: diskteki liste dosyalarının yolları ve küçük ek metin
     * (paket içi başlangıç listesi, kullanıcının elle yazdığı kurallar).
     *
     * Liste metinleri burada okunmaz; yerel taraf dosyaları kendisi okur.
     * Eskiden ~8 MB'lık metin Java yığınında birkaç kez çoğalıyordu.
     */
    private class RuleSources(val paths: Array<String>, val extra: String, val used: Int)

    private fun ruleSources(): RuleSources {
        val paths = ArrayList<String>()
        for (id in prefs.enabledLists) {
            val f = File(filtersDir, "$id.txt")
            if (f.isFile) paths.add(f.absolutePath)
        }
        for (url in prefs.customFilterUrls) {
            val id = "custom_" + url.hashCode().toUInt().toString(16)
            val f = File(filtersDir, "$id.txt")
            if (f.isFile) paths.add(f.absolutePath)
        }
        val extra = StringBuilder()
        // Paket içindeki başlangıç listesi (2,5 KB): ilk açılışta ağ beklenmeden korur.
        if (paths.isEmpty()) {
            extra.append(app.assets.open(BUNDLED_LIST).bufferedReader().use { it.readText() })
        }
        prefs.customRules.takeIf { it.isNotBlank() }?.let { extra.append('\n').append(it) }
        return RuleSources(paths.toTypedArray(), extra.toString(), paths.size)
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
                buildHosts(ruleSources())
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

    /** Hızlı yol tablosunu listelerden üretip diske yazar, sonra diskten kurar. */
    private fun buildHosts(sources: RuleSources) {
        val ok = try {
            NativeAdblock.available &&
                NativeAdblock.nativeHostsToFile(sources.paths, sources.extra, hostsFile.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "Hızlı yol üretilemedi: ${t.message}")
            false
        }
        if (!ok) return
        try {
            // ~700 KB; arama tablosu zaten Java tarafında tutuluyor.
            hosts = HostBlocklist.fromBytes(hostsFile.readBytes())
        } catch (t: Throwable) {
            Log.w(TAG, "Hızlı yol okunamadı: ${t.message}")
        }
    }

    private fun install(built: AdblockEngine) {
        val current = engine
        if (current.isValid()) current.swap(built) else engine = built
        // Eski motorun kararları artık geçerli olmayabilir.
        decisions.clear()
    }

    /**
     * Listeyi indirip [target]'a yazar. Gövde satır satır doğrudan dosyaya
     * akar; eskiden önce tamamı String'e okunuyor, `!#include` varsa bir de
     * iki kat kapasiteli `StringBuilder`'a kopyalanıyordu (en büyük liste
     * için Java yığınında ~10 MB). Önce geçici dosyaya yazılır; indirme
     * yarıda kalır ya da liste anlamsız derecede kısaysa eski dosya korunur.
     *
     * @return yeni liste yerine konduysa true
     */
    private fun download(url: String, target: File): Boolean {
        val tmp = File(target.path + ".part")
        try {
            tmp.bufferedWriter().use { out -> fetchInto(url, out, 0) }
            if (tmp.length() <= MIN_LIST_BYTES) return false
            return tmp.renameTo(target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * `!#include <dosya>` yönergelerini çözerek listeyi [out]'a yazar.
     * uBlock Origin'in ana listesi içeriğinin çoğunu bu şekilde parçalara
     * ayırdığı için, çözülmezse listenin neredeyse tamamı kaybolur.
     */
    private fun fetchInto(url: String, out: java.io.Writer, depth: Int) {
        val base = url.substringBeforeLast('/', "")
        httpGet(url) { reader ->
            reader.lineSequence().forEach { line ->
                if (line.startsWith("!#include ") && depth < MAX_INCLUDE_DEPTH) {
                    val rel = line.removePrefix("!#include ").trim()
                    if (rel.isEmpty() || rel.contains("..") || base.isEmpty()) return@forEach
                    try {
                        fetchInto("$base/$rel", out, depth + 1)
                    } catch (t: Throwable) {
                        Log.w(TAG, "include alınamadı: $rel (${t.message})")
                    }
                } else {
                    out.write(line)
                    out.write('\n'.code)
                }
            }
        }
    }

    private fun httpGet(url: String, body: (java.io.BufferedReader) -> Unit) {
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
            stream.bufferedReader().use(body)
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

        /**
         * Serileştirme biçimi değişirse artırılır; eski önbellekleri geçersiz kılar.
         * v2: adblock 0.13 (FlatBuffers); 0.10'un `engine.bin`'i okunamaz.
         * v3: adblock 0.13.6 — veri biçimi 8 → 9 (`$to=` kovaları). Eski
         *     dosya zaten reddedilirdi; damga değişince 11 MB boşuna okunup
         *     çözülmeye çalışılmaz, motor doğrudan bir kez yeniden derlenir.
         * v4: dosyanın başında 3 bayt dolgu; motor dosyayı kopyalamadan
         *     eşleyerek kullanır (bkz. lib.rs `nativeFromCacheFile`).
         */
        private const val ENGINE_FORMAT = "v4"

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
