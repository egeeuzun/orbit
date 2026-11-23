package com.orbit.browser.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.orbit.browser.R
import com.orbit.browser.databinding.ItemMenuBinding

/**
 * Menü. Adres çubuğu alttayken (telefon) alttan açılan sayfa; üstteyken
 * (tablet, ya da telefonda üst düzen) ⋮ düğmesinin altında açılan menü —
 * Samsung Internet ve masaüstü tarayıcılar gibi. Tablette sayfa ekranın
 * altından açılıyor, ⋮ ise sağ üstte kalıyordu.
 */
class MenuSheet(private val context: Context) {

    data class Item(
        val id: Int,
        val iconRes: Int,
        val label: String,
        val enabled: Boolean = true,
        val active: Boolean = false
    )

    fun show(items: List<Item>, onPick: (Int) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, (16 * density).toInt())
        }

        val dragHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (4 * density).toInt()).apply {
                bottomMargin = (8 * density).toInt()
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_sheet_drag_handle)
        }
        root.addView(dragHandle)

        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
        }
        list.adapter = Adapter(items) { id ->
            dialog.dismiss()
            onPick(id)
        }
        root.addView(list)

        dialog.setContentView(root)
        // Tablet ve yatay ekranlarda `BottomSheetDialog` küçük bir "peek"
        // yüksekliğiyle açılıyor; menüde her satırın görünmesi için gerekli
        // sürükleme, tablette kullanıcıyı yorar. Aşağıdaki iki ayar sayfayı
        // yatay yerleşimlerde tümüyle açılmış olarak (skipCollapsed) gösterir
        // ve peek yüksekliğini fiili içeriğine eşitler.
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    /**
     * [anchor]'ın altında, sağ kenarına hizalı açılır menü. Pencerenin altına
     * sığmazsa liste kaydırılır.
     */
    fun showAsDropdown(anchor: View, items: List<Item>, onPick: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        val width = (MENU_WIDTH_DP * density).toInt()
        val margin = (8 * density).toInt()

        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, margin, 0, margin)
            clipToPadding = false
        }
        lateinit var popup: android.widget.PopupWindow
        list.adapter = Adapter(items) { id ->
            popup.dismiss()
            onPick(id)
        }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        // Düğmenin altından pencerenin görünür altına kadar olan yer.
        val frame = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val available = frame.bottom - (loc[1] + anchor.height) - margin
        val height = minOf(list.measuredHeight, available.coerceAtLeast(margin * 12))

        popup = android.widget.PopupWindow(list, width, height, true).apply {
            // Şekilli zemin hem rengi hem de gölgenin yuvarlak sınırını verir;
            // zemin olmadan eski API'lerde dışarı dokununca kapanmıyor.
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_popup_menu))
            elevation = 8 * density
            isOutsideTouchable = true
        }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    private companion object {
        const val MENU_WIDTH_DP = 280
    }

    private class Adapter(
        val items: List<Item>,
        val onPick: (Int) -> Unit
    ) : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemMenuBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.menuIcon.setImageResource(item.iconRes)
            holder.binding.menuLabel.text = item.label
            holder.itemView.isEnabled = item.enabled
            holder.itemView.alpha = if (item.enabled) 1f else 0.38f

            if (item.active) {
                holder.binding.menuBadge.visibility = View.VISIBLE
                holder.binding.menuBadge.setImageResource(R.drawable.ic_bookmark_filled)
                val tintPrimary = com.google.android.material.color.MaterialColors.getColor(
                    holder.itemView, androidx.appcompat.R.attr.colorPrimary
                )
                holder.binding.menuBadge.setColorFilter(tintPrimary)
            } else {
                holder.binding.menuBadge.visibility = View.GONE
            }

            val tint = com.google.android.material.color.MaterialColors.getColor(
                holder.itemView, com.google.android.material.R.attr.colorOnSurface
            )
            holder.binding.menuIcon.setColorFilter(tint)

            holder.itemView.setOnClickListener(
                if (item.enabled) View.OnClickListener { onPick(item.id) } else null
            )
        }

        override fun getItemCount() = items.size
    }

    private class Holder(val binding: ItemMenuBinding) : RecyclerView.ViewHolder(binding.root)
}
