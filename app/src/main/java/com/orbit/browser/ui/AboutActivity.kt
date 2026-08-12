package com.orbit.browser.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.orbit.browser.R
import com.orbit.browser.databinding.ActivityAboutBinding

/**
 * Orbit tam ekran Hakkında ekranı.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.versionPill.text = getString(R.string.version_label, com.orbit.browser.BuildConfig.VERSION_NAME)
    }

    private fun setupSystemBars() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
    }
}
