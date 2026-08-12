package com.orbit.browser.adblock

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Engelleme kararlarının doğrudan eşlemeli (direct-mapped) önbelleği.
 *
 * Bir sayfa aynı adresi çoğu kez birden fazla ister: aynı ölçüm pikseli,
 * aynı yazı tipi, sonsuz kaydırmada tekrar tekrar çağrılan aynı API. Motor
 * sorgusu ucuz olsa da bu tekrarların tamamı ağ iş parçacığında sıraya
 * giriyor ve isteğin başlamasını geciktiriyordu.
 *
 * Kilit yok: her yuva tek bir referans olarak atomik yazılıp okunur. En kötü
 * durumda iki iş parçacığı aynı yuvayı çakıştırır ve biri ıskalar — sonuç
 * yine motordan hesaplanacağı için yanlış karar üretilmez.
 */
class DecisionCache(capacityPow2: Int = 512) {

    private val mask = capacityPow2 - 1
    private val slots = AtomicReferenceArray<Entry>(capacityPow2)

    private class Entry(
        val url: String,
        val source: String,
        val type: String,
        val blocked: Boolean
    )

    /** @return karar, ya da önbellekte yoksa `null`. */
    fun get(url: String, source: String, type: String): Boolean? {
        val e = slots.get(index(url, source, type)) ?: return null
        // Aynı sayfada `source` genellikle aynı String nesnesidir; karşılaştırma
        // referans denetiminde biter.
        if (e.type !== type && e.type != type) return null
        if (e.source !== source && e.source != source) return null
        if (e.url != url) return null
        return e.blocked
    }

    fun put(url: String, source: String, type: String, blocked: Boolean) {
        slots.set(index(url, source, type), Entry(url, source, type, blocked))
    }

    /** Motor değiştiğinde veya engelleme kapatılıp açıldığında çağrılır. */
    fun clear() {
        for (i in 0..mask) slots.set(i, null)
    }

    private fun index(url: String, source: String, type: String): Int {
        // String.hashCode() ilk çağrıdan sonra nesnede saklanır; burada
        // yeniden hesaplanmaz.
        var h = url.hashCode()
        h = h * 31 + source.hashCode()
        h = h * 31 + type.hashCode()
        h = h xor (h ushr 16)
        return h and mask
    }
}
