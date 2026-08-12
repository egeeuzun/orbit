package com.orbit.browser.adblock

/**
 * `libadblock_jni.so` içindeki Brave adblock motoruna açılan JNI yüzeyi.
 * Buradaki imzalar `rust/adblock-jni/src/lib.rs` ile birebir eşleşmek zorunda.
 */
internal object NativeAdblock {

    /** Kütüphane yüklenemezse uygulama çökmemeli; engelleme devre dışı kalır. */
    val available: Boolean = try {
        System.loadLibrary("adblock_jni")
        true
    } catch (t: Throwable) {
        false
    }

    // nativeMatch bit maskesi — lib.rs'teki sabitlerle aynı.
    const val MATCHED = 1
    const val EXCEPTION = 1 shl 1
    const val IMPORTANT = 1 shl 2
    const val REDIRECT = 1 shl 3

    // nativeCosmetic dizi indeksleri
    const val COS_CSS = 0
    const val COS_SCRIPT = 1
    const val COS_PROCEDURAL = 2
    const val COS_GENERICHIDE = 3

    external fun nativeNew(rules: String, optimize: Boolean): Long
    external fun nativeFromCache(data: ByteArray): Long
    external fun nativeSerialize(handle: Long): ByteArray?
    external fun nativeFree(handle: Long)

    external fun nativeMatch(handle: Long, url: String, sourceUrl: String, requestType: String): Int

    external fun nativeCosmetic(handle: Long, url: String): Array<String>?
    external fun nativeClassIdCss(
        handle: Long,
        classes: Array<String>,
        ids: Array<String>,
        exceptions: Array<String>
    ): Array<String>?

    external fun nativeLoadResources(handle: Long, json: String): Boolean

    /**
     * Filtre metnindeki saf `||host^` kurallarının sıralı 64 bit özet
     * tablosu (küçük sonlu). Ayrıntı: `rust/adblock-jni/src/hosts.rs`.
     */
    external fun nativeHostsFromRules(rules: String): ByteArray?
}
