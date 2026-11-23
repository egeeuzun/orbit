package com.orbit.browser.ui

import android.content.Intent
import android.net.Uri
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
        binding.aboutContent.limitContentWidth()

        binding.versionPill.text = getString(R.string.version_label, com.orbit.browser.BuildConfig.VERSION_NAME)

        // Depo Orbit'in kendisinde, yeni sekmede açılır. MainActivity
        // `singleTask` olduğu için Ayarlar ve bu ekran üstünden kapanır.
        binding.githubLink.setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                    .setClass(this, MainActivity::class.java)
            )
        }
    }

    private fun setupSystemBars() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
    }

    private companion object {
        const val GITHUB_URL = "https://github.com/egeeuzun/orbit"
    }
}
