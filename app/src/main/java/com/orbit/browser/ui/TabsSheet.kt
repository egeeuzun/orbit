package com.orbit.browser.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.orbit.browser.R
import com.orbit.browser.browser.Tab
import com.orbit.browser.databinding.ItemTabBinding
import com.orbit.browser.util.UrlUtils

/**
 * Sekme listesi: Google Chrome tarzı alt sayfa sekmeler menüsü.
 */
class TabsSheet(
    private val context: Context,
    private val tabs: List<Tab>,
    private val current: Tab?,
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    private val onNew: (incognito: Boolean) -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        }

        val dragHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (4 * density).toInt()).apply {
                bottomMargin = (8 * density).toInt()
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_sheet_drag_handle)
        }
        root.addView(dragHandle)

        val header = TextView(context).apply {
            text = context.getString(R.string.open_tabs_count, tabs.size)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0))
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(header)

        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val adapter = Adapter(
            items = ArrayList(tabs),
            current = current,
            onSelect = { tab ->
                onSelect(tab)
                dialog.dismiss()
            },
            onClose = { tab, position, self ->
                onClose(tab)
                self.items.removeAt(position)
                self.notifyItemRemoved(position)
                if (self.items.isEmpty()) dialog.dismiss()
            }
        )
        list.adapter = adapter
        root.addView(list)

        val actionsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), 0)
        }

        actionsLayout.addView(actionButton(context, R.string.new_tab, false) {
            onNew(false)
            dialog.dismiss()
        })
        actionsLayout.addView(actionButton(context, R.string.new_incognito_tab, true) {
            onNew(true)
            dialog.dismiss()
        })
        root.addView(actionsLayout)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun actionButton(context: Context, textRes: Int, isIncognito: Boolean, onClick: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        val tv = TextView(context).apply {
            text = (if (isIncognito) "🕶 " else "+ ") + context.getString(textRes)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_google_status_pill)
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (!isIncognito) (8 * density).toInt() else 0
            }
            setOnClickListener { onClick() }
        }
        return tv
    }

    private class Adapter(
        val items: ArrayList<Tab>,
        val current: Tab?,
        val onSelect: (Tab) -> Unit,
        val onClose: (Tab, Int, Adapter) -> Unit
    ) : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tab = items[position]
            val prefix = if (tab.incognito) "🕶 " else ""
            holder.binding.tabTitle.text =
                prefix + UrlUtils.titleOrHost(tab.title, tab.displayUrl())
            holder.binding.tabUrl.text = UrlUtils.forDisplay(tab.displayUrl())

            val isActive = tab === current
            holder.binding.tabContainer.setBackgroundResource(
                if (isActive) R.drawable.bg_tab_item_active else R.drawable.bg_tab_item_normal
            )
            holder.itemView.alpha = if (isActive) 1f else 0.85f

            holder.itemView.setOnClickListener { onSelect(tab) }
            holder.binding.tabClose.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClose(items[index], index, this)
            }
        }

        override fun getItemCount() = items.size
    }

    private class Holder(val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root)
}
