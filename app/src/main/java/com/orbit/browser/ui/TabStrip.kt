package com.orbit.browser.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.orbit.browser.R
import com.orbit.browser.browser.FaviconStore
import com.orbit.browser.browser.Tab
import com.orbit.browser.databinding.ItemTabStripBinding
import com.orbit.browser.util.UrlUtils

/**
 * Tablet sekme şeridi: adres çubuğunun üstünde, Samsung Internet ve masaüstü
 * tarayıcılardaki gibi yatay sekme listesi.
 *
 * Bellek açısından ucuz tutulur — 1 GB'lık tabletler de hedef:
 *  - Sekme başına bir WebView ya da ekran görüntüsü tutulmaz; yalnızca başlık
 *    ve favicon çizilir. Canlı WebView sayısı [com.orbit.browser.browser.TabManager]'ın
 *    sınırında kalır, şerit onu hiç uyandırmaz.
 *  - Faviconlar başlangıç ekranıyla aynı [FaviconStore] önbelleğinden ve
 *    aynı hedef boyutta istenir: ikon bir kez çözülür, iki yerde kullanılır.
 *  - [update] her arayüz tazelemesinde çağrılır; yalnızca gerçekten değişen
 *    sekmeler yeniden bağlanır.
 */
class TabStrip(
    /** Şeridin tamamı: sekme listesi ve hemen arkasındaki "+" düğmesi. */
    private val strip: View,
    private val list: RecyclerView,
    private val newButton: View,
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit
) {

    private val context = list.context
    private val res = context.resources
    private val minWidth = res.getDimensionPixelSize(R.dimen.tab_strip_item_min_width)
    private val maxWidth = res.getDimensionPixelSize(R.dimen.tab_strip_item_max_width)

    /** Başlangıç ekranı ızgarasıyla aynı boyut: önbellek paylaşılsın. */
    private val iconTargetPx = (HomeView.ICON_DP * res.displayMetrics.density).toInt()

    private val colorActive = MaterialColors.getColor(list, com.google.android.material.R.attr.colorOnSurface)
    private val colorInactive = MaterialColors.getColor(list, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val inactiveTint = android.content.res.ColorStateList.valueOf(colorInactive)

    /** Seçili olmayan sekmenin zemini; kimlik bir kez çözülür. */
    private val rippleRes = android.util.TypedValue().let {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        it.resourceId
    }

    private val adapter = Adapter()
    private val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

    /** Son bağlanan sekmeler ve görünür içerikleri; fark yoksa dokunulmaz. */
    private var items: List<Tab> = emptyList()
    private var keys: List<String> = emptyList()
    private var current: Tab? = null
    private var itemWidth = maxWidth

    init {
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        // Şerit genişliği (döndürme, bölünmüş ekran) değişince sekme
        // genişlikleri yeniden hesaplanır ve seçili sekme görünür alana
        // getirilir. Düzen bittikten sonra yapılır: düzen sırasında verilen
        // bağdaştırıcı bildirimi uygulanmıyordu (SM-T320'de dikeye dönünce
        // sekmeler yatay genişlikte kalıyor, seçili sekme ekran dışında
        // kalıyordu).
        strip.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) list.post { relayout() }
        }
    }

    fun update(tabs: List<Tab>, selected: Tab?) {
        val newKeys = tabs.map(::keyOf)
        val structural = tabs.size != items.size || tabs.indices.any { tabs[it] !== items[it] }
        val oldCurrent = current
        items = ArrayList(tabs)
        current = selected

        if (structural) {
            keys = newKeys
            computeWidth()
            adapter.notifyDataSetChanged()
        } else {
            for (i in newKeys.indices) {
                val tab = items[i]
                val selectionChanged = tab === selected || tab === oldCurrent
                if (newKeys[i] != keys[i] || (selected !== oldCurrent && selectionChanged)) {
                    adapter.notifyItemChanged(i)
                }
            }
            keys = newKeys
        }

        if (selected !== oldCurrent || structural) {
            val index = items.indexOf(selected)
            if (index >= 0) list.post { revealIndex(index) }
        }
    }

    /**
     * Sayfa ikonu geldiğinde ilgili sekmeye anında konur. [FaviconStore]
     * dosyayı arka planda yazdığı için önbellekten okumak bir tur gecikirdi.
     */
    fun onIcon(tab: Tab, icon: Bitmap) {
        if (tab.incognito || tab.isHome) return
        val index = items.indexOf(tab)
        if (index < 0) return
        val holder = list.findViewHolderForAdapterPosition(index) as? Holder ?: return
        if (holder.tab === tab) holder.binding.stripIcon.setFavicon(icon)
    }

    private fun revealIndex(index: Int) {
        val first = layoutManager.findFirstCompletelyVisibleItemPosition()
        val last = layoutManager.findLastCompletelyVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || index < first || index > last) {
            list.smoothScrollToPosition(index)
        }
    }

    private fun keyOf(tab: Tab): String =
        "${tab.incognito}|${tab.isHome}|${tab.title}|${tab.displayUrl()}"

    private fun relayout() {
        val before = itemWidth
        computeWidth()
        if (before != itemWidth) adapter.notifyDataSetChanged()
        val index = items.indexOf(current)
        if (index >= 0) list.post { revealIndex(index) }
    }

    /**
     * Sekmeler sığdıkça şeridi doldurur, sığmazsa en küçük genişlikte kayar.
     *
     * Liste yalnızca sekmelerin kapladığı kadar geniş tutulur; böylece "+"
     * düğmesi son sekmenin hemen yanında durur (Chrome / Samsung Internet).
     * Sekmeler sığmayınca liste "+"ya yer bırakacak kadar daralıp kayar ve
     * düğme sağ kenarda görünür kalır.
     */
    private fun computeWidth() {
        val buttonLp = newButton.layoutParams as? ViewGroup.MarginLayoutParams
        val buttonSpace = newButton.width.takeIf { it > 0 }
            ?: newButton.layoutParams.width.coerceAtLeast(0)
        val available = strip.width - strip.paddingStart - strip.paddingEnd - buttonSpace -
            (buttonLp?.marginStart ?: 0) - (buttonLp?.marginEnd ?: 0)
        itemWidth = if (available <= 0 || items.isEmpty()) {
            maxWidth
        } else {
            (available / items.size).coerceIn(minWidth, maxWidth)
        }
        val listWidth = if (available <= 0) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            minOf(itemWidth * items.size, available)
        }
        val lp = list.layoutParams
        if (lp.width != listWidth) {
            lp.width = listWidth
            list.layoutParams = lp
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(ItemTabStripBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.itemView.setOnClickListener {
                holder.tab?.let(onSelect)
            }
            holder.binding.stripClose.setOnClickListener {
                holder.tab?.let(onClose)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tab = items[position]
            holder.tab = tab
            val b = holder.binding

            val lp = holder.itemView.layoutParams
            if (lp.width != itemWidth) {
                lp.width = itemWidth
                holder.itemView.layoutParams = lp
            }

            val active = tab === current
            val title = if (tab.isHome) {
                context.getString(R.string.new_tab)
            } else {
                UrlUtils.titleOrHost(tab.title, tab.displayUrl())
            }
            b.stripTitle.text = title
            b.stripTitle.setTextColor(if (active) colorActive else colorInactive)
            b.stripTitle.paint.isFakeBoldText = active
            holder.itemView.contentDescription = title
            holder.itemView.isSelected = active
            if (active) {
                holder.itemView.setBackgroundResource(R.drawable.bg_tab_strip_active)
            } else {
                // Seçili olmayan sekme şerit renginde kalır; dokunma geri
                // bildirimi için yalnızca dalga efekti.
                holder.itemView.setBackgroundResource(rippleRes)
            }

            bindIcon(holder, tab)
        }

        private fun bindIcon(holder: Holder, tab: Tab) {
            val icon = holder.binding.stripIcon
            val host = UrlUtils.host(tab.displayUrl())
            holder.iconHost = host
            when {
                tab.incognito -> icon.setPlaceholder(R.drawable.ic_incognito)
                tab.isHome || host.isEmpty() -> icon.setPlaceholder(
                    if (tab.isHome) R.drawable.ic_home else R.drawable.ic_globe
                )
                else -> {
                    val cached = FaviconStore.cached(host)
                    if (cached != null) {
                        icon.setFavicon(cached)
                    } else {
                        icon.setPlaceholder(R.drawable.ic_globe)
                        FaviconStore.iconAsync(context, host, iconTargetPx) { bitmap ->
                            // Görünüm bu arada başka bir sekmeye bağlandıysa atılır.
                            if (bitmap != null && holder.iconHost == host) icon.setFavicon(bitmap)
                        }
                    }
                }
            }
        }
    }

    private fun android.widget.ImageView.setPlaceholder(res: Int) {
        // Yer tutucu vektörler tema rengine boyanır; favicon renkli kalmalı.
        ImageViewCompat.setImageTintList(this, inactiveTint)
        setImageResource(res)
    }

    private fun android.widget.ImageView.setFavicon(bitmap: Bitmap) {
        ImageViewCompat.setImageTintList(this, null)
        setImageBitmap(bitmap)
    }

    private class Holder(val binding: ItemTabStripBinding) : RecyclerView.ViewHolder(binding.root) {
        var tab: Tab? = null
        var iconHost: String = ""
    }
}
