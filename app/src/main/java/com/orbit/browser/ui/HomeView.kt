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
import com.orbit.browser.data.BrowserDb
import com.orbit.browser.databinding.ItemShortcutBinding
import com.orbit.browser.databinding.ViewHomeBinding
import com.orbit.browser.util.UrlUtils

/**
 * Yeni sekmede görünen başlangıç ekranı: uygulama adı, koruma durumu ve
 * geçmişten türetilen kısayol ızgarası. Bir web sayfası değil, düz görünüm —
 * boş sekme için WebView oluşturma maliyeti doğmaz.
 */
class HomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding: ViewHomeBinding
    private val adapter = ShortcutAdapter()

    var onShortcutClick: ((String) -> Unit)? = null
    var onSearchBoxClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(
            com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorSurface
            )
        )
        binding = ViewHomeBinding.inflate(LayoutInflater.from(context), this)
        binding.shortcutGrid.layoutManager = GridLayoutManager(context, COLUMNS)
        binding.shortcutGrid.adapter = adapter
        binding.shortcutGrid.setHasFixedSize(true)
        binding.homeSearchBox.setOnClickListener { onSearchBoxClick?.invoke() }
    }

    /** Motor durumu satırı; boşsa yerine "geçmiş yok" metni gösterilir. */
    private var status: String = ""

    fun setStatus(text: String) {
        status = text
        if (text.isNotEmpty()) binding.homeStatus.text = text
    }

    fun setSearchHint(hint: String) {
        binding.homeSearchHint.text = hint
    }

    /**
     * Kısayolları tazeler. Sorgu arka planda: başlangıç ekranı yeni sekmede
     * hemen görünmeli, geçmiş büyüdükçe uzayan bir SQLite okumasını
     * beklememeli.
     */
    fun refresh(db: BrowserDb) {
        db.topSitesAsync(COLUMNS * 3) { sites ->
            adapter.submit(sites)
            binding.homeStatus.visibility = View.VISIBLE
            if (sites.isEmpty() && status.isEmpty()) {
                binding.homeStatus.text = context.getString(R.string.home_empty)
            }
        }
    }

    private inner class ShortcutAdapter : RecyclerView.Adapter<Holder>() {
        private val items = ArrayList<BrowserDb.Entry>()

        fun submit(newItems: List<BrowserDb.Entry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val host = UrlUtils.forDisplay(entry.url).substringBefore('/')
            holder.binding.shortcutInitial.text = host.take(1).uppercase()
            holder.binding.shortcutLabel.text = host
            holder.itemView.setOnClickListener { onShortcutClick?.invoke(entry.url) }
        }

        override fun getItemCount() = items.size
    }

    private class Holder(val binding: ItemShortcutBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val COLUMNS = 4
    }
}
