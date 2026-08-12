package com.orbit.browser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.orbit.browser.R
import com.orbit.browser.databinding.DialogShieldBinding

/**
 * Brave Browser tarzı Kalkan &amp; Koruma alt sayfası.
 */
class ShieldSheet(
    private val context: Context,
    private val host: String,
    private val blockedOnPage: Int,
    private val blockedTotal: Long,
    private val isProtectionActive: Boolean,
    private val onToggleProtection: (active: Boolean) -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val binding = DialogShieldBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.shieldHost.text = host
        binding.shieldBlockedPageCount.text = blockedOnPage.toString()
        binding.shieldBlockedTotalCount.text = String.format("%,d", blockedTotal)

        fun applyState(active: Boolean) {
            binding.shieldSwitch.isChecked = active
            if (active) {
                binding.shieldStatusBadge.setText(R.string.shield_protection_active)
                binding.shieldStatusBadge.setTextColor(Color.parseColor("#16A34A")) // Green
                binding.shieldToggleSub.setText(R.string.shield_toggle_sub_active)
                binding.shieldIcon.setImageResource(R.drawable.ic_shield)
                binding.shieldIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#2563EB")) // Blue
            } else {
                binding.shieldStatusBadge.setText(R.string.shield_protection_disabled)
                binding.shieldStatusBadge.setTextColor(Color.parseColor("#EA580C")) // Orange
                binding.shieldToggleSub.setText(R.string.shield_toggle_sub_disabled)
                binding.shieldIcon.setImageResource(R.drawable.ic_shield_off)
                binding.shieldIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EA580C")) // Orange
            }
        }

        applyState(isProtectionActive)

        binding.shieldSwitch.setOnCheckedChangeListener { _, isChecked ->
            applyState(isChecked)
            dialog.dismiss()
            onToggleProtection(isChecked)
        }

        dialog.show()
    }
}
