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
    /** Sayfa şu an çevrilmiş mi; öyleyse "Orijinali göster" görünür. */
    private val translated: Boolean,
    /** Sayfa içi çeviri (varsayılan). */
    private val onTranslate: (source: String, target: String) -> Unit,
    /** Eski yol: sayfayı Google Çeviri vekilinde aç. */
    private val onOpenInGoogle: (translatedUrl: String) -> Unit,
    private val onRestore: () -> Unit
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

        // Varsayılan hedef: sistem dili listede varsa o, yoksa ilk dil.
        // `Configuration.locales` API 24+; Android 5–6'da pencere açılırken
        // NoSuchMethodError ile çöküyordu.
        val systemLang = androidx.core.os.ConfigurationCompat
            .getLocales(context.resources.configuration)[0]?.language
            ?: java.util.Locale.getDefault().language
        // Son seçilen hedef dil hatırlanır; yoksa sistem dili, o da yoksa ilk dil.
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastTarget = store.getString(KEY_LAST_TARGET, null)
        val defaultTargetIndex = languages.indexOfFirst { it.code == lastTarget }
            .takeIf { it >= 0 }
            ?: languages.indexOfFirst { it.code == systemLang }.coerceAtLeast(0)
        binding.spinnerTargetLang.setSelection(defaultTargetIndex)

        fun source() = sourceList[binding.spinnerSourceLang.selectedItemPosition].code
        fun target() = languages[binding.spinnerTargetLang.selectedItemPosition].code

        binding.btnTranslate.setOnClickListener {
            store.edit().putString(KEY_LAST_TARGET, target()).apply()
            dialog.dismiss()
            onTranslate(source(), target())
        }

        binding.btnTranslateGoogle.setOnClickListener {
            val encoded = try {
                URLEncoder.encode(currentUrl, "UTF-8")
            } catch (_: Exception) {
                currentUrl
            }
            dialog.dismiss()
            onOpenInGoogle(
                "https://translate.google.com/translate?sl=${source()}&tl=${target()}&u=$encoded"
            )
        }

        binding.btnShowOriginal.visibility =
            if (translated) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnShowOriginal.setOnClickListener {
            dialog.dismiss()
            onRestore()
        }

        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private companion object {
        const val PREFS = "orbit_translate"
        const val KEY_LAST_TARGET = "last_target"
    }
}
