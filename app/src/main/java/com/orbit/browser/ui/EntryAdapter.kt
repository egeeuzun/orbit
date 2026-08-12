package com.orbit.browser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.orbit.browser.data.BrowserDb
import com.orbit.browser.databinding.ItemEntryBinding
import com.orbit.browser.util.UrlUtils

/** Geçmiş, yer imleri ve adres çubuğu önerileri için ortak liste. */
class EntryAdapter(
    private val onClick: (BrowserDb.Entry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.Holder>() {

    private val items = ArrayList<BrowserDb.Entry>()

    fun submit(newItems: List<BrowserDb.Entry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.binding.entryTitle.text = UrlUtils.titleOrHost(entry.title, entry.url)
        holder.binding.entryUrl.text = UrlUtils.forDisplay(entry.url)
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val binding: ItemEntryBinding) : RecyclerView.ViewHolder(binding.root)
}
