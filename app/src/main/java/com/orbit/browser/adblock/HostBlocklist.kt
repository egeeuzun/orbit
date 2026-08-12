package com.orbit.browser.adblock

/**
 * Saf `||host^` kurallarının hızlı yolu.
 *
 * Orbit'in listelerindeki ağ kurallarının yaklaşık dörtte üçü tek bir ana
 * bilgisayar adından ibaret. Bunlar için tam motora gitmek — JNI sınırını
 * geçmek, iki adresi ayrıştırmak, genel sonek listesine bakmak, motorun
 * global kilidini almak — gereksiz. Burada karar sıralı bir `LongArray`
 * üzerinde ikili aramayla veriliyor: tahsis yok, kilit yok, JNI yok.
 *
 * Tablo Rust tarafında üretilir (`hosts.rs`) ve `hostcheck` örneğiyle tam
 * motora karşı doğrulanır: 343.196 istek üzerinde sıfır uyuşmazlık. `@@`
 * istisnalarında ve `$badfilter` karşı-kurallarında adı geçen ana
 * bilgisayarlar tabloya hiç alınmadığı için hızlı yol bir istisnayı ezemez.
 *
 * İkinci işlevi: tablo diskten milisaniyeler içinde okunur, tam motor ise
 * bu cihaz sınıfında ~900 ms sürer. Bu yüzden motor kurulurken de engelleme
 * çalışır.
 */
class HostBlocklist private constructor(private val table: LongArray) {

    val size: Int get() = table.size

    /**
     * Ana bilgisayar ya da üst alan adlarından biri tabloda mı?
     *
     * `||example.com^` kuralı `a.b.example.com` isteğini de engeller; bu
     * yüzden ad soldan kırpılarak aranır. Kırpma en fazla etiket sayısı
     * kadar döner ve tahsis yapmaz.
     */
    fun blocks(host: String): Boolean {
        if (host.isEmpty() || table.isEmpty()) return false
        var start = 0
        while (true) {
            if (find(hash(host, start)) ) return true
            val dot = host.indexOf('.', start)
            // Son iki etiket kaldıysa dur: tek etiketli ad aranmaz.
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) return false
            start = dot + 1
        }
    }

    private fun find(h: Long): Boolean {
        var lo = 0
        var hi = table.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = table[mid]
            when {
                v < h -> lo = mid + 1
                v > h -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        /** Boş tablo: hiçbir şeyi engellemez. */
        val EMPTY = HostBlocklist(LongArray(0))

        /**
         * `nativeHostsFromRules` çıktısını okur. Bayt dizisi küçük sonlu
         * 64 bitlik sıralı özetlerden oluşur.
         */
        fun fromBytes(bytes: ByteArray): HostBlocklist {
            val n = bytes.size / 8
            if (n == 0) return EMPTY
            val t = LongArray(n)
            var p = 0
            for (i in 0 until n) {
                var v = 0L
                for (b in 0 until 8) {
                    v = v or ((bytes[p + b].toLong() and 0xFF) shl (8 * b))
                }
                t[i] = v
                p += 8
            }
            return HostBlocklist(t)
        }

        /**
         * FNV-1a 64 — `hosts.rs` içindeki `hash` ile birebir aynı olmalı.
         * `from` konumundan sonuna kadar, ASCII küçük harfe çevirerek.
         */
        internal fun hash(host: String, from: Int): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (i in from until host.length) {
                var c = host[i].code
                if (c in 65..90) c += 32
                h = h xor (c.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
