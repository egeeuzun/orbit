package com.orbit.browser.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.orbit.browser.R
import com.orbit.browser.databinding.DialogTranslateBinding
import com.orbit.browser.util.UrlUtils
import java.net.URLEncoder

/**
 * Sayfa Çeviri Hizmeti alt sayfası.
 */
class TranslateSheet(
    private val context: Context,
    private val currentUrl: String,
    private val onTranslate: (translatedUrl: String) -> Unit
) {

    data class Lang(val code: String, val label: String)

    private val languages = listOf(
        Lang("tr", "Türkçe"),
        Lang("en", "English"),
        Lang("de", "Deutsch"),
        Lang("fr", "Français"),
        Lang("es", "Español"),
        Lang("it", "Italiano"),
        Lang("pt", "Português"),
        Lang("ru", "Русский"),
        Lang("ar", "العربية"),
        Lang("ja", "日本語"),
        Lang("zh-CN", "中文 (Simplified)"),
        Lang("ko", "한국어"),
        Lang("hi", "Hindi"),
        Lang("nl", "Nederlands"),
        Lang("pl", "Polski"),
        Lang("sv", "Svenska"),
        Lang("uk", "Українська"),
        Lang("el", "Ελληνικά"),
        Lang("cs", "Čeština"),
        Lang("ro", "Română"),
        Lang("hu", "Magyar"),
        Lang("id", "Bahasa Indonesia"),
        Lang("vi", "Tiếng Việt")
    )

    fun show() {
        val dialog = BottomSheetDialog(context)
        val binding = DialogTranslateBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        val host = UrlUtils.host(currentUrl)
        binding.translatePageHost.text = host.ifEmpty { currentUrl }

        val autoLabel = context.getString(R.string.translate_auto_detect)
        val sourceList = listOf(Lang("auto", autoLabel)) + languages
        val sourceLabels = sourceList.map { it.label }
        val targetLabels = languages.map { it.label }

        val sourceAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sourceLabels)
        val targetAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, targetLabels)

        binding.spinnerSourceLang.adapter = sourceAdapter
        binding.spinnerTargetLang.adapter = targetAdapter

        // Varsayılan kaynak: Otomatik Algıla (index 0)
        binding.spinnerSourceLang.setSelection(0)

        // Varsayılan hedef: Sistem Türkçe ise Türkçe, aksi halde İngilizce
        val systemLang = context.resources.configuration.locales[0].language
        val defaultTargetIndex = languages.indexOfFirst { it.code == systemLang }.coerceAtLeast(0)
        binding.spinnerTargetLang.setSelection(defaultTargetIndex)

        binding.btnTranslate.setOnClickListener {
            val source = sourceList[binding.spinnerSourceLang.selectedItemPosition].code
            val target = languages[binding.spinnerTargetLang.selectedItemPosition].code

            val encoded = try {
                URLEncoder.encode(currentUrl, "UTF-8")
            } catch (_: Exception) {
                currentUrl
            }

            val translateUrl = "https://translate.google.com/translate?sl=$source&tl=$target&u=$encoded"
            dialog.dismiss()
            onTranslate(translateUrl)
        }

        dialog.show()
    }
}
