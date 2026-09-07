package com.orbit.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.orbit.browser.R
import com.orbit.browser.browser.FaviconStore
import com.orbit.browser.data.BrowserDb
import com.orbit.browser.data.Prefs
import com.orbit.browser.databinding.ItemShortcutBinding
import com.orbit.browser.databinding.ViewHomeBinding
import com.orbit.browser.util.UrlUtils

/**
 * Yeni sekmede görünen başlangıç ekranı: uygulama adı, koruma durumu ve
 * kısayol ızgarası. Bir web sayfası değil, düz görünüm — boş sekme için
 * WebView oluşturma maliyeti doğmaz.
 *
 * Izgara iki kaynağı birleştirir: önce kullanıcının elle eklediği sabit
 * kısayollar ([Prefs.pinnedShortcuts]), sonra geçmişten türeyen "en çok
 * ziyaret edilenler" — kullanıcının kaldırdıkları ([Prefs.hiddenShortcutHosts])
 * ve zaten sabitlenmiş olanlar çıkarılarak. Sonda yeni kısayol eklemek için
 * bir "+" kutucuğu durur.
 */
class HomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding: ViewHomeBinding
    private val adapter = ShortcutAdapter()
    private val prefs = Prefs(context)

    var onShortcutClick: ((String) -> Unit)? = null

    /** Kısayola uzun basıldı; ikinci parametre kısayolun elle eklenmiş olduğunu söyler. */
    var onShortcutLongClick: ((BrowserDb.Entry, Boolean) -> Unit)? = null

    /** Izgaranın sonundaki "+" kutucuğuna dokunuldu. */
    var onAddShortcut: (() -> Unit)? = null

    var onSearchBoxClick: (() -> Unit)? = null

    /** Ekran genişliğine göre değişen kısayol sütunu sayısı (telefon 4,
     *  tablet 6, büyük tablet 8). */
    private val columns: Int = context.resources.getInteger(R.integer.home_shortcut_columns)

    /** Kutucuk ikonunun piksel boyu; favicon dosyası buna göre örneklenerek çözülür. */
    private val iconTargetPx: Int = (ICON_DP * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorSurface
            )
        )
        binding = ViewHomeBinding.inflate(LayoutInflater.from(context), this)
        binding.shortcutGrid.layoutManager = GridLayoutManager(context, columns)
        binding.shortcutGrid.adapter = adapter
        binding.shortcutGrid.setHasFixedSize(true)
        binding.homeSearchBox.setOnClickListener { onSearchBoxClick?.invoke() }
    }

    /** Motor durumu satırı; boşsa yerine "geçmiş yok" metni gösterilir. */
    private var status: String = ""

    fun setStatus(text: String) {
        status = text
        // Aynı metni yeniden atamak da TextView'i ölçüp çizdirir; sık
        // çağrılan arayüz tazelemesinde değişmemişse dokunulmaz.
        if (text.isNotEmpty() && binding.homeStatus.text.toString() != text) {
            binding.homeStatus.text = text
        }
    }

    fun setSearchHint(hint: String) {
        if (binding.homeSearchHint.text.toString() != hint) {
            binding.homeSearchHint.text = hint
        }
    }

    /**
     * Kısayolları tazeler. Sorgu arka planda: başlangıç ekranı yeni sekmede
     * hemen görünmeli, geçmiş büyüdükçe uzayan bir SQLite okumasını
     * beklememeli.
     */
    fun refresh(db: BrowserDb) {
        val pinned = prefs.pinnedShortcuts
        val hidden = prefs.hiddenShortcutHosts
        val pinnedHosts = HashSet<String>(pinned.size * 2)
        for (entry in pinned) pinnedHosts.add(UrlUtils.host(entry.url))
        db.topSitesAsync(columns * 3) { sites ->
            // Kaldırılan ve zaten sabitlenmiş siteler geçmiş listesinden düşer.
            val auto = ArrayList<BrowserDb.Entry>(sites.size)
            for (site in sites) {
                val host = UrlUtils.host(site.url)
                if (host in hidden || host in pinnedHosts) continue
                auto.add(site)
            }
            adapter.submit(pinned, auto)
            binding.homeStatus.visibility = View.VISIBLE
            if (pinned.isEmpty() && auto.isEmpty() && status.isEmpty()) {
                binding.homeStatus.text = context.getString(R.string.home_empty)
            }
        }
    }

    private inner class ShortcutAdapter : RecyclerView.Adapter<Holder>() {
        private val items = ArrayList<BrowserDb.Entry>()

        /** Listenin başındaki kaç kayıt elle eklenmiş kısayol; gerisi geçmişten. */
        private var pinnedCount = 0

        fun submit(pinned: List<BrowserDb.Entry>, auto: List<BrowserDb.Entry>) {
            items.clear()
            items.addAll(pinned)
            items.addAll(auto)
            pinnedCount = pinned.size
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position == items.size) {
                bindAddTile(holder)
                return
            }
            val entry = items[position]
            val pinned = position < pinnedCount
            // Elle eklenen kısayolda kullanıcının verdiği ad, geçmişten
            // türeyende alan adı gösterilir.
            val label = if (pinned && entry.title.isNotBlank()) {
                entry.title
            } else {
                UrlUtils.forDisplay(entry.url).substringBefore('/')
            }
            holder.binding.shortcutInitial.text = label.take(1).uppercase()
            holder.binding.shortcutLabel.text = label
            bindIcon(holder, UrlUtils.host(entry.url))
            holder.itemView.isLongClickable = true
            holder.itemView.setOnClickListener { onShortcutClick?.invoke(entry.url) }
            holder.itemView.setOnLongClickListener {
                onShortcutLongClick?.invoke(entry, pinned)
                true
            }
        }

        /**
         * Kutucuğun faviconu. Önbellekteyse aynı karede oturur; değilse
         * arka planda okunur ve bu sırada harf kutucuğu görünür kalır.
         * Görünüm geri dönüştürülüp başka bir siteye bağlanmış olabileceği
         * için sonuç [Holder.host] ile doğrulanır.
         */
        private fun bindIcon(holder: Holder, host: String) {
            holder.host = host
            val cached = FaviconStore.cached(host)
            showIcon(holder, cached)
            if (cached != null || host.isEmpty()) return
            FaviconStore.iconAsync(context, host, iconTargetPx) { bitmap ->
                if (bitmap != null && holder.host == host) showIcon(holder, bitmap)
            }
        }

        private fun showIcon(holder: Holder, bitmap: android.graphics.Bitmap?) {
            if (bitmap == null) {
                holder.binding.shortcutIcon.setImageDrawable(null)
                holder.binding.shortcutIcon.visibility = View.GONE
                holder.binding.shortcutInitial.visibility = View.VISIBLE
            } else {
                holder.binding.shortcutIcon.setImageBitmap(bitmap)
                holder.binding.shortcutIcon.visibility = View.VISIBLE
                holder.binding.shortcutInitial.visibility = View.INVISIBLE
            }
        }

        private fun bindAddTile(holder: Holder) {
            holder.host = null
            showIcon(holder, null)
            holder.binding.shortcutInitial.text = "+"
            holder.binding.shortcutLabel.text = context.getString(R.string.shortcut_add)
            holder.itemView.setOnClickListener { onAddShortcut?.invoke() }
            // Görünüm geri dönüştürüldüğünde eski uzun basma dinleyicisi kalmasın.
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
        }

        /** Son kutucuk her zaman "yeni kısayol ekle"dir. */
        override fun getItemCount() = items.size + 1
    }

    private class Holder(val binding: ItemShortcutBinding) : RecyclerView.ViewHolder(binding.root) {
        /** Bu görünüm şu an hangi siteye bağlı; geciken ikon okumaları bununla doğrulanır. */
        var host: String? = null
    }

    private companion object {
        /** `item_shortcut.xml` içindeki kutucuk boyu. */
        const val ICON_DP = 48
    }
}
