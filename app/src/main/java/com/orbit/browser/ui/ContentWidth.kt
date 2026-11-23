package com.orbit.browser.ui

import android.view.View

/**
 * Metin ağırlıklı ekranlarda (Ayarlar, Hakkında) içeriği okunabilir bir
 * genişlikte, ortada tutar.
 *
 * Yatay tablette (SM-T320, 2560 px) kartlar ve ayar satırları ekranın tamamına
 * yayılıyor, anahtarlar etiketlerinden 2.500 px uzakta kalıyordu. Kenar
 * boşluğu görünümün gerçek genişliğine göre hesaplanır: dar ekranda (telefon)
 * düzendeki asıl boşluk aynen kalır; döndürme ve bölünmüş ekranda kendiliğinden
 * yeniden hesaplanır.
 */
fun View.limitContentWidth(maxWidthDp: Int = DEFAULT_MAX_CONTENT_DP) {
    val baseStart = paddingStart
    val baseEnd = paddingEnd
    val maxPx = (maxWidthDp * resources.displayMetrics.density).toInt()
    var appliedFor = -1
    addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
        val width = r - l
        if (width == appliedFor) return@addOnLayoutChangeListener
        appliedFor = width
        val extra = ((width - maxPx) / 2).coerceAtLeast(0)
        val start = maxOf(baseStart, extra)
        val end = maxOf(baseEnd, extra)
        if (start != v.paddingStart || end != v.paddingEnd) {
            // Düzen sırasında verilen boşluk o turda uygulanmaz; sonraki karede.
            v.post { v.setPaddingRelative(start, v.paddingTop, end, v.paddingBottom) }
        }
    }
}

/** Material'ın okunabilir metin sütunu önerisine yakın bir genişlik. */
const val DEFAULT_MAX_CONTENT_DP = 720
