package com.orbit.browser.ui

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.orbit.browser.R
import com.orbit.browser.adblock.AdblockService
import com.orbit.browser.browser.CosmeticBridge
import com.orbit.browser.browser.DownloadHelper
import com.orbit.browser.browser.OrbitChromeClient
import com.orbit.browser.browser.OrbitWebView
import com.orbit.browser.browser.OrbitWebViewClient
import com.orbit.browser.browser.Tab
import com.orbit.browser.browser.WebViewFactory
import com.orbit.browser.data.Prefs
import com.orbit.browser.util.UrlUtils

/**
 * «Sekme olarak uygulama» modu: masaüstü kısayolundan açılan, arayüzü olmayan
 * tam ekran WebView. Adres çubuğu, paneller ve durum çubuğu yok; sistem
 * çubukları yukarıdan kaydırmayla geçici görünür.
 */
class WebappActivity : ComponentActivity(), OrbitWebViewClient.Callbacks, OrbitChromeClient.Host {

    private lateinit var web: OrbitWebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var adblock: AdblockService
    private var adblockEnabled = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = intent.getStringExtra(EXTRA_URL)
            ?: intent.data?.toString()
        if (target.isNullOrBlank() || !UrlUtils.isHttp(target)) {
            finish()
            return
        }
        val prefs = Prefs(this)
        adblock = AdblockService.get(this)
        adblockEnabled = prefs.adBlockEnabled
        adblock.start()

        val bridge = CosmeticBridge(adblock) { prefs.adBlockEnabled && prefs.cosmeticFiltering }
        web = WebViewFactory.create(this, prefs, bridge, false)
        bridge.attach(web)

        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(fullscreenContainer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(root)

        val tab = Tab(-1L, false).apply { pageUrl = target }
        web.webViewClient = OrbitWebViewClient(this, tab, adblock, prefs, this)
        web.webChromeClient = OrbitChromeClient(tab, this)
        web.setDownloadListener { url, ua, disposition, mime, size ->
            try {
                DownloadHelper.enqueue(this, url, ua, disposition, mime, size)
            } catch (_: Throwable) {
                // İndirme kuyruğu kullanılamıyorsa sayfa akışını bozma.
            }
        }
        WebViewFactory.applyTheme(web, prefs)
        web.loadUrl(target)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    hideCustomView()
                } else if (web.canGoBack()) {
                    web.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.getStringExtra(EXTRA_URL)
            ?: intent.data?.toString()
        if (!target.isNullOrBlank() && UrlUtils.isHttp(target) && ::web.isInitialized) {
            web.loadUrl(target)
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
        hideSystemBars()
    }

    override fun onPause() {
        web.onPause()
        web.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Прячем только статусбар: со скрытой навигационной полосой SystemUI
        // «съедал» первый свайп с края, из-за чего жест «назад» не работал
        // с первого раза. Видимая навигация отдаёт жест сразу.
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // -------------------------------------------------- OrbitWebViewClient.Callbacks

    override fun onPageUrlChanged(tab: Tab, url: String) = Unit
    override fun onPageStarted(tab: Tab) = Unit
    override fun onPageFinished(tab: Tab) = Unit
    override fun onBlockedCountChanged(tab: Tab) = Unit
    override fun onExternalIntent(uri: Uri): Boolean = false

    // -------------------------------------------------------- OrbitChromeClient.Host

    override fun onProgress(tab: Tab, progress: Int) = Unit
    override fun onTitle(tab: Tab, title: String) = Unit
    override fun onIcon(tab: Tab, icon: android.graphics.Bitmap?) = Unit

    override fun onNewWindow(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (adblockEnabled && adblock.engine.blocks(url, web.url.orEmpty(), "popup")) {
            Toast.makeText(this, R.string.popup_blocked, Toast.LENGTH_SHORT).show()
            return true
        }
        if (UrlUtils.isHttp(url)) {
            web.loadUrl(url)
            return true
        }
        return false
    }

    override fun onCreateNewTab(): Tab? = null

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view)
        fullscreenContainer.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
    }

    override fun onHideCustomView() {
        hideCustomView()
    }

    override fun onFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean = false
}
