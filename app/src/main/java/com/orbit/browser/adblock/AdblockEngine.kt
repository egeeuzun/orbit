package com.orbit.browser.adblock

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Yerel motor tutamacının güvenli sarmalayıcısı.
 *
 * `shouldInterceptRequest` birden çok iş parçacığından çağrılırken listeler
 * arka planda yenilenebiliyor; okuma kilidi, sorgu sürerken eski tutamacın
 * serbest bırakılmasını (use-after-free) engeller.
 */
class AdblockEngine private constructor(@Volatile private var handle: Long) {

    private val lock = ReentrantReadWriteLock()

    fun isValid(): Boolean = lock.read { handle != 0L }

    /**
     * @return [NativeAdblock] bit maskesi; motor yoksa 0.
     */
    fun match(url: String, sourceUrl: String, requestType: String): Int = lock.read {
        val h = handle
        if (h == 0L) 0 else NativeAdblock.nativeMatch(h, url, sourceUrl, requestType)
    }

    fun blocks(url: String, sourceUrl: String, requestType: String): Boolean {
        val r = match(url, sourceUrl, requestType)
        return r and NativeAdblock.MATCHED != 0 && r and NativeAdblock.EXCEPTION == 0
    }

    fun cosmetic(url: String): CosmeticResult? = lock.read {
        val h = handle
        if (h == 0L) return@read null
        val a = NativeAdblock.nativeCosmetic(h, url) ?: return@read null
        CosmeticResult(
            css = a.getOrElse(NativeAdblock.COS_CSS) { "" },
            script = a.getOrElse(NativeAdblock.COS_SCRIPT) { "" },
            proceduralJson = a.getOrElse(NativeAdblock.COS_PROCEDURAL) { "[]" },
            genericHide = a.getOrElse(NativeAdblock.COS_GENERICHIDE) { "0" } == "1"
        )
    }

    /** DOM'da gerçekten bulunan sınıf/kimliklere karşılık gelen genel seçiciler. */
    fun classIdCss(
        classes: Array<String>,
        ids: Array<String>,
        exceptions: Array<String>
    ): String = lock.read {
        val h = handle
        if (h == 0L) return@read ""
        NativeAdblock.nativeClassIdCss(h, classes, ids, exceptions)?.firstOrNull() ?: ""
    }

    fun serialize(): ByteArray? = lock.read {
        val h = handle
        if (h == 0L) null else NativeAdblock.nativeSerialize(h)
    }

    /**
     * Yeni derlenmiş motoru devreye alır ve eskisini serbest bırakır.
     *
     * [other] tutamacını devralır; çağrıdan sonra [other] geçersizdir. Bu
     * yüzden [serialize] gibi işlemler swap'tan önce yapılmalıdır.
     */
    fun swap(other: AdblockEngine) {
        val incoming = other.detach()
        if (incoming == 0L) return
        lock.write {
            val old = handle
            handle = incoming
            if (old != 0L) NativeAdblock.nativeFree(old)
        }
    }

    private fun detach(): Long = lock.write {
        val h = handle
        handle = 0L
        h
    }

    fun close() {
        lock.write {
            if (handle != 0L) {
                NativeAdblock.nativeFree(handle)
                handle = 0L
            }
        }
    }

    /** uBlock Origin scriptlet paketini yükler; motor kurulurken bir kez çağrılır. */
    fun loadResources(json: String): Boolean = lock.write {
        val h = handle
        h != 0L && NativeAdblock.nativeLoadResources(h, json)
    }

    data class CosmeticResult(
        val css: String,
        val script: String,
        val proceduralJson: String,
        val genericHide: Boolean
    )

    companion object {
        /** Motorsuz (her şeye izin veren) örnek. */
        fun empty() = AdblockEngine(0L)

        fun fromRules(rules: String): AdblockEngine? {
            if (!NativeAdblock.available) return null
            val h = NativeAdblock.nativeNew(rules, true)
            return if (h == 0L) null else AdblockEngine(h)
        }

        fun fromCache(data: ByteArray): AdblockEngine? {
            if (!NativeAdblock.available) return null
            val h = NativeAdblock.nativeFromCache(data)
            return if (h == 0L) null else AdblockEngine(h)
        }
    }
}
