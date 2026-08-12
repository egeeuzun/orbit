package com.orbit.browser.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.orbit.browser.R
import com.orbit.browser.adblock.AdblockService
import com.orbit.browser.adblock.FilterLists
import com.orbit.browser.data.BrowsingData
import com.orbit.browser.data.Prefs
import com.orbit.browser.databinding.ActivitySettingsBinding
import com.orbit.browser.databinding.RowSettingBinding

/**
 * Ayarlar. `androidx.preference` yerine düz görünümler kullanıldı: bir
 * tarayıcıya kütüphane ve açılış maliyeti eklemeye değmiyor.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var adblock: AdblockService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        prefs = Prefs(this)
        adblock = AdblockService.get(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        build()
    }

    private fun setupSystemBars() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
    }

    private fun build() {
        val c = binding.settingsContainer
        c.removeAllViews()

        header(R.string.settings_general)
        action(getString(R.string.search_engine), currentSearchName()) { chooseSearchEngine() }
        switch(
            getString(R.string.pref_search_suggestions),
            getString(R.string.pref_search_suggestions_sum),
            prefs.searchSuggestions
        ) { prefs.searchSuggestions = it }
        action(getString(R.string.home_page), prefs.homePage) { editHomePage() }
        switch(
            getString(R.string.pref_restore_tabs),
            getString(R.string.pref_restore_tabs_sum),
            prefs.restoreTabs
        ) {
            prefs.restoreTabs = it
            if (!it) prefs.savedSession = ""
        }

        header(R.string.settings_blocking)
        switch(
            getString(R.string.pref_adblock), getString(R.string.pref_adblock_sum),
            prefs.adBlockEnabled
        ) { prefs.adBlockEnabled = it }
        switch(
            getString(R.string.pref_cosmetic), getString(R.string.pref_cosmetic_sum),
            prefs.cosmeticFiltering
        ) { prefs.cosmeticFiltering = it }
        switch(
            getString(R.string.pref_generic_cosmetic),
            getString(R.string.pref_generic_cosmetic_sum),
            prefs.genericCosmetic
        ) { prefs.genericCosmetic = it }
        action(getString(R.string.pref_filter_lists), listSummary()) { chooseLists() }
        action(getString(R.string.pref_update_lists), updateSummary()) { updateLists() }
        action(
            getString(R.string.pref_custom_rules),
            getString(R.string.pref_custom_rules_sum)
        ) { editCustomRules() }
        action(
            getString(R.string.pref_custom_urls),
            customUrlsSummary()
        ) { editCustomFilterUrls() }

        header(R.string.settings_privacy)
        switch(getString(R.string.pref_js), null, prefs.javaScriptEnabled) {
            prefs.javaScriptEnabled = it
        }
        switch(getString(R.string.pref_3p_cookies), null, prefs.blockThirdPartyCookies) {
            prefs.blockThirdPartyCookies = it
        }
        switch(getString(R.string.pref_dnt), null, prefs.doNotTrack) { prefs.doNotTrack = it }
        switch(
            getString(R.string.pref_safe_browsing), getString(R.string.pref_safe_browsing_sum),
            prefs.safeBrowsing
        ) { prefs.safeBrowsing = it }
        switch(getString(R.string.pref_history), null, prefs.saveHistory) { prefs.saveHistory = it }
        switch(getString(R.string.pref_clear_exit), null, prefs.clearOnExit) {
            prefs.clearOnExit = it
        }
        action(getString(R.string.pref_clear_now), null) {
            BrowsingData.clear(this)
            toast(getString(R.string.data_cleared))
        }

        header(R.string.settings_performance)
        action(getString(R.string.pref_live_tabs), getString(R.string.pref_live_tabs_sum)) {
            chooseLiveTabs()
        }
        switch(
            getString(R.string.pref_block_images), getString(R.string.pref_block_images_sum),
            prefs.blockImages
        ) { prefs.blockImages = it }

        header(R.string.settings_appearance)
        action(getString(R.string.pref_theme), themeName()) { chooseTheme() }
        switch(getString(R.string.pref_force_dark), null, prefs.forceDarkWeb) {
            prefs.forceDarkWeb = it
        }

        header(R.string.settings_about)
        action(getString(R.string.pref_about_orbit), getString(R.string.pref_about_orbit_sum)) {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }

        // Motor durumu, en altta bilgi satırı olarak.
        action(getString(R.string.app_name), engineSummary(), null)
    }

    // ------------------------------------------------------------------ satırlar

    private fun header(textRes: Int) {
        val tv = LayoutInflater.from(this)
            .inflate(R.layout.row_header, binding.settingsContainer, false) as android.widget.TextView
        tv.setText(textRes)
        binding.settingsContainer.addView(tv)
    }

    private fun row(): RowSettingBinding =
        RowSettingBinding.inflate(layoutInflater, binding.settingsContainer, false)

    private fun action(title: String, summary: String?, onClick: (() -> Unit)?) {
        val r = row()
        r.rowTitle.text = title
        bindSummary(r, summary)
        if (onClick != null) {
            r.root.setOnClickListener { onClick() }
        } else {
            r.root.isClickable = false
        }
        binding.settingsContainer.addView(r.root)
    }

    private fun switch(title: String, summary: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val r = row()
        r.rowTitle.text = title
        bindSummary(r, summary)
        r.rowSwitch.visibility = View.VISIBLE
        r.rowSwitch.isChecked = value
        r.root.setOnClickListener {
            val next = !r.rowSwitch.isChecked
            r.rowSwitch.isChecked = next
            onChange(next)
            onSettingChanged(title)
        }
        binding.settingsContainer.addView(r.root)
    }

    private fun bindSummary(r: RowSettingBinding, summary: String?) {
        if (summary.isNullOrEmpty()) {
            r.rowSummary.visibility = View.GONE
        } else {
            r.rowSummary.visibility = View.VISIBLE
            r.rowSummary.text = summary
        }
    }

    /** Engellemeyi etkileyen ayarlarda motorun yeniden derlenmesi gerekir. */
    private fun onSettingChanged(title: String) {
        if (title == getString(R.string.pref_adblock)) build()
    }

    // ------------------------------------------------------------------ eylemler

    private fun currentSearchName(): String =
        Prefs.SEARCH_ENGINES.entries.firstOrNull { it.value == prefs.searchEngine }?.key
            ?: prefs.searchEngine

    private fun chooseSearchEngine() {
        val names = Prefs.SEARCH_ENGINES.keys.toTypedArray()
        val values = Prefs.SEARCH_ENGINES.values.toList()
        val checked = values.indexOf(prefs.searchEngine)
        AlertDialog.Builder(this)
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                prefs.searchEngine = values[which]
                dialog.dismiss()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editHomePage() {
        val input = EditText(this).apply {
            setText(prefs.homePage)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.home_page)
            .setView(pad(input))
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.homePage = input.text.toString().trim()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun listSummary(): String {
        val on = prefs.enabledLists.size
        return "$on / ${FilterLists.CATALOG.size}"
    }

    private fun chooseLists() {
        val catalog = FilterLists.CATALOG
        val titles = catalog.map { it.title }.toTypedArray()
        val enabled = prefs.enabledLists
        val checked = BooleanArray(catalog.size) { enabled.contains(catalog[it].id) }

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_filter_lists)
            .setMultiChoiceItems(titles, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val next = HashSet<String>()
                catalog.forEachIndexed { i, list -> if (checked[i]) next.add(list.id) }
                prefs.enabledLists = next
                build()
                updateLists()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSummary(): String {
        val last = prefs.lastListUpdate
        if (last == 0L) return getString(R.string.engine_loading)
        val days = (System.currentTimeMillis() - last) / (24 * 60 * 60 * 1000)
        return when {
            days <= 0L -> getString(R.string.updated_today)
            days == 1L -> getString(R.string.updated_yesterday)
            else -> getString(R.string.updated_days_ago, days)
        }
    }

    private fun updateLists() {
        toast(getString(R.string.updating_lists))
        adblock.refresh { ok ->
            toast(getString(if (ok) R.string.lists_updated else R.string.lists_update_failed))
            build()
        }
    }

    private fun editCustomRules() {
        val input = EditText(this).apply {
            setText(prefs.customRules)
            hint = "||reklam.example.com^\nexample.com##.banner"
            minLines = 5
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_custom_rules)
            .setView(pad(input))
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.customRules = input.text.toString()
                adblock.rebuild()
                toast(getString(R.string.lists_updated))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun customUrlsSummary(): String {
        val count = prefs.customFilterUrls.size
        return if (count == 0) getString(R.string.pref_custom_urls_sum)
        else getString(R.string.custom_urls_added, count)
    }

    private fun editCustomFilterUrls() {
        val currentText = prefs.customFilterUrls.joinToString("\n")
        val input = EditText(this).apply {
            setText(currentText)
            hint = "https://example.com/custom_filters.txt\nhttps://example.com/privacy_list.txt"
            minLines = 5
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_custom_urls)
            .setView(pad(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val urls = input.text.toString()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .toSet()
                prefs.customFilterUrls = urls
                build()
                updateLists()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseLiveTabs() {
        val options = arrayOf("1", "2", "3")
        val checked = options.indexOf(prefs.liveTabLimit.toString()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_live_tabs)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                prefs.liveTabLimit = options[which].toInt()
                dialog.dismiss()
                build()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun themeName(): String = when (prefs.theme) {
        1 -> getString(R.string.theme_light)
        2 -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_system)
    }

    private fun chooseTheme() {
        val names = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.pref_theme)
            .setSingleChoiceItems(names, prefs.theme) { dialog, which ->
                prefs.theme = which
                AppCompatDelegate.setDefaultNightMode(
                    when (which) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun engineSummary(): String = buildString {
        append(
            when (adblock.state) {
                AdblockService.State.READY -> getString(R.string.engine_ready)
                AdblockService.State.LOADING -> getString(R.string.engine_loading)
                AdblockService.State.FAILED -> getString(R.string.engine_failed)
                AdblockService.State.IDLE -> "—"
            }
        )
        append(" · ")
        append(getString(R.string.blocked_total, adblock.blockedTotal.toString()))
    }

    private fun pad(view: View): View {
        val container = LinearLayout(this)
        val p = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(p, p / 2, p, 0)
        container.addView(view)
        return container
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
