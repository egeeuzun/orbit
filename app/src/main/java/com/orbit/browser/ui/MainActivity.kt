package com.orbit.browser.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import kotlin.math.max
import com.orbit.browser.R
import com.orbit.browser.adblock.AdblockService
import com.orbit.browser.browser.OrbitChromeClient
import com.orbit.browser.browser.DownloadHelper
import com.orbit.browser.browser.FaviconStore
import com.orbit.browser.browser.OrbitWebViewClient
import com.orbit.browser.browser.Tab
import com.orbit.browser.browser.TabManager
import com.orbit.browser.browser.WebViewFactory
import com.orbit.browser.data.BrowserDb
import com.orbit.browser.data.BrowsingData
import com.orbit.browser.data.Prefs
import com.orbit.browser.databinding.ActivityMainBinding
import com.orbit.browser.util.UrlUtils

class MainActivity : AppCompatActivity(),
    OrbitWebViewClient.Callbacks,
    OrbitChromeClient.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adblock: AdblockService
    private lateinit var db: BrowserDb
    private lateinit var tabs: TabManager
    private lateinit var suggestions: EntryAdapter

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /** Tam ekrana girilirken kaydedilen sayfa kaydırma konumu. */
    private var fullscreenScrollY = 0

    /** Bir kare içinde birden çok tazeleme isteği tek çağrıya iner. */
    private var chromePending = false

    /** Başlangıç ekranı görünürken kısayolların yeniden sorgulanmasını engeller. */
    private var homeShown = false

    /** Adres çubuğu önerileri eşzamansız gelir; eskimiş sonuçlar atılır. */
    private var suggestToken = 0

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        callback.onReceiveValue(
            if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else null
        )
    }

    private val pickUrl = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(ListActivity.EXTRA_URL)?.let { load(it) }
        }
    }

    private var appliedNightMode = -1

    /** Стартовый экран показывает «Loading filters…», пока движок не собран;
     * listener поднимает перерисовку, когда состояние меняется (READY → «Total…»). */
    private val engineListener: (AdblockService.State) -> Unit = { refreshChrome() }

    // ------------------------------------------------------------------ yaşam döngüsü

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        // Pencere arka planı çizimini kaldır: üstteki düzenler tüm ekranı
        // kapladığından 1 GB RAM/zayıf GPU'lu cihazlarda overdraw engellenir.
        window.setBackgroundDrawable(null)

        prefs = Prefs(this)
        adblock = AdblockService.get(this)
        adblock.addListener(engineListener)
        db = BrowserDb.get(this)

        tabs = TabManager(
            context = this,
            container = binding.webContainer,
            prefs = prefs,
            adblock = adblock,
            clientCallbacks = this,
            chromeHost = this,
            onDownload = ::startDownload
        )

        setupUrlBar()
        setupNavBar()
        setupHome()
        setupBackHandling()

        val initialUrl = startUrl(intent)
        if (initialUrl != null) {
            tabs.newTab(initialUrl)
        } else if (!tabs.restoreSession()) {
            tabs.newTab(prefs.homePage)
        }
        refreshChrome()
        warmUpWebView()
    }

    /**
     * WebView sağlayıcısını ilk kareden sonra, boştayken yükler.
     *
     * Başlangıç ekranında artık WebView kurulmuyor; bu, uygulamanın açılışını
     * hızlandırıyor ama sağlayıcının yüklenme maliyetini ilk gezinmeye
     * erteliyor olurdu. `CookieManager.getInstance()` görünüm ve oluşturucu
     * kurmadan yalnızca sağlayıcıyı ayağa kaldırır — pahalı olan kısım budur.
     */
    private fun warmUpWebView() {
        binding.root.postDelayed({
            if (isFinishing || tabs.current?.isLive == true) return@postDelayed
            try {
                CookieManager.getInstance()
            } catch (_: Throwable) {
                // WebView yoksa ilk gezinmede zaten hata verilecek.
            }
        }, WARM_UP_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startUrl(intent)?.let { url ->
            tabs.newTab(url)
            refreshChrome()
        }
    }

    /** Paylaşılan metin, açılan bağlantı veya arama sorgusu. */
    private fun startUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
                ?.let { UrlUtils.normalizeOrSearch(it, prefs.searchEngine) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { UrlUtils.normalizeOrSearch(it, prefs.searchEngine) }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    override fun onResume() {
        super.onResume()
        val currentNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (appliedNightMode != -1 && appliedNightMode != currentNightMode) {
            appliedNightMode = currentNightMode
            recreate()
            return
        }
        setupSystemBars()
        // Tam ekran video sırasında uygulamadan çıkıp dönüldüyse sistem
        // çubukları geri gelmiş olabilir; yeniden gizle.
        if (customView != null) hideSystemBars()
        tabs.onResume()
        tabs.updateTheme()
        // Ayarlardan dönülmüş olabilir: kozmetik ve performans çalışma zamanı
        // canlı görünüme eklenir ya da kaldırılır.
        tabs.tabs.forEach { tab ->
            tab.webView?.let {
                WebViewFactory.applyCosmeticRuntime(this, it, prefs)
                WebViewFactory.applyPerfRuntime(this, it, prefs)
            }
        }
        if (tabs.current?.isHome == true) binding.homeView.refresh(db)
        refreshChromeNow()
    }

    override fun onPause() {
        super.onPause()
        tabs.onPause()
        tabs.saveSession()
        adblock.flushCounters()
    }

    override fun onDestroy() {
        adblock.removeListener(engineListener)
        if (prefs.clearOnExit && isFinishing) BrowsingData.clear(this)
        tabs.closeAll()
        super.onDestroy()
    }

    private fun setupSystemBars() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight

        // The page area sits below the status bar / camera cutout; the bottom
        // omnibox bar sits above the navigation bar and the keyboard.
        // Tam ekran (video) sırasında sistem çubuklarının gizlenmesi insetleri
        // sıfırlar; o an layout'u değiştirirsek WebView yeniden düzenlenir ve
        // sayfa en üste atlar — bu yüzden tam ekranda padding dondurulur.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            if (customView == null) {
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val top = max(systemBars.top, cutout.top)
                binding.browserRoot.setPadding(0, top, 0, 0)
                binding.progressBar.setPadding(0, top, 0, 0)
                binding.bottomContainer.updatePadding(
                    left = systemBars.left,
                    right = systemBars.right,
                    bottom = max(systemBars.bottom, max(cutout.bottom, ime.bottom))
                )
            }
            insets
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sistem bellek istediğinde arka plan sekmeleri hemen uyutulur.
        if (level >= TRIM_MEMORY_RUNNING_LOW) tabs.trimMemory()
    }

    // ---------------------------------------------------------------------- arayüz

    private var isProgrammaticTextChange = false

    private fun setupUrlBar() {
        suggestions = EntryAdapter { entry ->
            load(entry.url)
            hideKeyboard()
            hideSuggestions()
        }
        binding.suggestions.layoutManager = LinearLayoutManager(this)
        binding.suggestions.adapter = suggestions

        binding.urlBar.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                load(UrlUtils.normalizeOrSearch(v.text.toString(), prefs.searchEngine))
                hideKeyboard()
                hideSuggestions()
                true
            } else false
        }

        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                isProgrammaticTextChange = true
                val focusedTab = tabs.current
                // Başlangıç ekranında eski sekmenin URL'si asla doldurulmaz;
                // odak yarışı kapanan sekmeyi okusa bile metin boş kalır.
                binding.urlBar.setText(
                    if (focusedTab != null && !focusedTab.isHome) focusedTab.pageUrl else ""
                )
                binding.urlBar.selectAll()
                isProgrammaticTextChange = false
            } else {
                hideSuggestions()
                refreshChrome()
            }
        }

        binding.urlBar.doAfterTextChanged { text ->
            if (!binding.urlBar.hasFocus() || isProgrammaticTextChange) return@doAfterTextChanged
            val query = text?.toString().orEmpty()
            val currentUrl = tabs.current?.pageUrl.orEmpty()
            if (query.length < 2 || query == currentUrl) {
                hideSuggestions()
            } else {
                val token = ++suggestToken
                val showOnline = prefs.searchSuggestions
                val engineUrl = prefs.searchEngine
                db.suggestWithOnlineAsync(query, showOnline, engineUrl) { items ->
                    if (token != suggestToken || !binding.urlBar.hasFocus()) return@suggestWithOnlineAsync
                    suggestions.submit(items)
                    binding.suggestions.visibility =
                        if (items.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        binding.reloadButton.setOnClickListener {
            val web = tabs.current?.webView ?: return@setOnClickListener
            // Yüklenirken düğme durdur işlevi görür; asılı kalan yüklemeler
            // böylece iptal edilebilir.
            if (binding.progressBar.visibility == View.VISIBLE) {
                web.stopLoading()
                binding.progressBar.visibility = View.GONE
                refreshChromeNow()
            } else {
                web.reload()
            }
        }
    }

    private fun setupNavBar() {
        binding.navHome.setOnClickListener { load(Prefs.HOME_URL) }
        binding.navTabs.setOnClickListener { showTabs() }
        binding.navMenu.setOnClickListener { showMenu() }
    }

    private fun setupHome() {
        binding.homeView.onShortcutClick = { url -> load(url) }
        binding.homeView.onSearchBoxClick = {
            binding.urlBar.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.urlBar, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> onHideCustomView()
                    binding.suggestions.visibility == View.VISIBLE -> {
                        hideSuggestions()
                        binding.urlBar.clearFocus()
                    }
                    // Asılı kalan bir yükleme varsa "geri" önce yüklemeyi
                    // durdurur; ikinci "geri" gezinir.
                    binding.progressBar.visibility == View.VISIBLE -> {
                        tabs.current?.webView?.stopLoading()
                        binding.progressBar.visibility = View.GONE
                        refreshChromeNow()
                    }
                    tabs.current?.webView?.canGoBack() == true -> goBack()
                    tabs.size > 1 -> {
                        tabs.current?.let { tabs.close(it) }
                        refreshChromeNow()
                    }
                    else -> finish()
                }
            }
        })
    }

    private fun goBack() {
        val web = tabs.current?.webView ?: return
        if (web.canGoBack()) web.goBack() else load(Prefs.HOME_URL)
    }

    private fun hideSuggestions() {
        suggestToken++
        binding.suggestions.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
        binding.urlBar.clearFocus()
    }

    fun load(url: String) {
        if (url.isBlank()) return
        hideSuggestions()
        hideKeyboard()
        binding.urlBar.clearFocus()
        tabs.loadInCurrent(url)
        refreshChromeNow()
    }

    /**
     * Arayüz tazelemesini bir kareye toplar.
     */
    private fun refreshChrome() {
        if (chromePending) return
        chromePending = true
        binding.root.post {
            chromePending = false
            if (!isFinishing) refreshChromeNow()
        }
    }

    /** Adres satırı, gezinme düğmeleri ve başlangıç ekranını eşitler. */
    private fun refreshChromeNow() {
        val tab = tabs.current
        val home = tab?.isHome == true
        updateSearchHint()

        if (home) {
            if (binding.homeView.visibility != View.VISIBLE) {
                binding.homeView.visibility = View.VISIBLE
                // Sekme kapanınca odak urlBar'a kaçmasın; adres çubuğu boş
                // ve odaksız kalır.
                binding.homeView.requestFocus()
            }
            if (!homeShown) {
                homeShown = true
                binding.homeView.refresh(db)
            }
            binding.homeView.setStatus(engineStatusLine())
            if (!binding.urlBar.hasFocus() && binding.urlBar.text.isNotEmpty()) {
                isProgrammaticTextChange = true
                binding.urlBar.setText("")
                isProgrammaticTextChange = false
            }
        } else {
            homeShown = false
            if (binding.homeView.visibility != View.GONE) {
                binding.homeView.visibility = View.GONE
            }
            if (!binding.urlBar.hasFocus()) {
                val displayUrl = UrlUtils.forDisplay(tab?.displayUrl())
                if (binding.urlBar.text.toString() != displayUrl) {
                    isProgrammaticTextChange = true
                    binding.urlBar.setText(displayUrl)
                    isProgrammaticTextChange = false
                }
            }
        }

        val countStr = tabs.size.toString()
        if (binding.tabCount.text != countStr) {
            binding.tabCount.text = countStr
        }

        val reloadVis = if (home) View.GONE else View.VISIBLE
        if (binding.reloadButton.visibility != reloadVis) {
            binding.reloadButton.visibility = reloadVis
        }
        updateReloadIcon()
    }

    /** Yükleme sırasında düğme "durdur" (X), boştayken "yenile" ikonu gösterir. */
    private fun updateReloadIcon() {
        val loading = binding.progressBar.visibility == View.VISIBLE
        binding.reloadButton.setImageResource(
            if (loading) R.drawable.ic_close else R.drawable.ic_refresh
        )
    }

    private fun updateSearchHint() {
        val engineName = prefs.getSearchEngineName()
        val hint = getString(R.string.search_or_type_url, engineName)
        if (binding.urlBar.hint != hint) {
            binding.urlBar.hint = hint
        }
        binding.homeView.setSearchHint(hint)
    }

    private fun engineStatusLine(): String = when (adblock.state) {
        AdblockService.State.READY ->
            getString(R.string.blocked_total, adblock.blockedTotal.toString())
        AdblockService.State.LOADING -> getString(R.string.engine_loading)
        AdblockService.State.FAILED -> getString(R.string.engine_failed)
        AdblockService.State.IDLE -> ""
    }

    // ------------------------------------------------------------------ kalkan

    private fun showShieldDialog() {
        val tab = tabs.current ?: return
        val host = UrlUtils.host(tab.displayUrl())
        val displayHost = host.ifEmpty { getString(R.string.app_name) }
        val isAllowlisted = if (host.isNotEmpty()) prefs.isAllowlisted(host) else false

        ShieldSheet(
            context = this,
            host = displayHost,
            blockedOnPage = tab.blockedOnPage,
            blockedTotal = adblock.blockedTotal,
            isProtectionActive = !isAllowlisted,
            onToggleProtection = { active ->
                if (host.isNotEmpty()) {
                    prefs.setAllowlisted(host, !active)
                    tab.allowlisted = !active
                    refreshChrome()
                    tab.webView?.reload()
                }
            }
        ).show()
    }

    // -------------------------------------------------------------------- menü

    private fun showMenu() {
        val tab = tabs.current
        val url = tab?.pageUrl.orEmpty()
        val web = tab?.webView
        val hasPage = url.isNotEmpty() && tab?.isHome != true
        val bookmarked = hasPage && db.isBookmarked(url)

        val shieldHost = UrlUtils.host(tab?.displayUrl())
        val shieldAllowlisted = shieldHost.isNotEmpty() && prefs.isAllowlisted(shieldHost)
        val shieldBlocking = prefs.adBlockEnabled && !shieldAllowlisted

        val items = listOf(
            MenuSheet.Item(
                ID_SHIELD,
                if (shieldBlocking) R.drawable.ic_shield else R.drawable.ic_shield_off,
                getString(R.string.shield_title)
            ),
            MenuSheet.Item(ID_NEW_TAB, R.drawable.ic_add, getString(R.string.new_tab)),
            MenuSheet.Item(
                ID_NEW_INCOGNITO, R.drawable.ic_incognito,
                getString(R.string.new_incognito_tab)
            ),
            MenuSheet.Item(
                ID_BOOKMARK,
                if (bookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark,
                getString(if (bookmarked) R.string.remove_bookmark else R.string.add_bookmark),
                enabled = hasPage,
                active = bookmarked
            ),
            MenuSheet.Item(ID_BOOKMARKS, R.drawable.ic_bookmark, getString(R.string.bookmarks)),
            MenuSheet.Item(ID_HISTORY, R.drawable.ic_history, getString(R.string.history)),
            MenuSheet.Item(
                ID_FIND, R.drawable.ic_find, getString(R.string.find_in_page),
                enabled = hasPage
            ),
            MenuSheet.Item(ID_TRANSLATE, R.drawable.ic_translate, getString(R.string.translate_page),
                enabled = hasPage
            ),
            MenuSheet.Item(
                ID_ADD_HOME, R.drawable.ic_add, getString(R.string.add_to_home),
                enabled = hasPage
            ),
            MenuSheet.Item(
                ID_DESKTOP, R.drawable.ic_desktop, getString(R.string.desktop_site),
                enabled = web != null && hasPage,
                active = tab?.desktopMode == true
            ),
            MenuSheet.Item(
                ID_SHARE, R.drawable.ic_share, getString(R.string.share),
                enabled = hasPage
            ),
            MenuSheet.Item(ID_SETTINGS, R.drawable.ic_settings, getString(R.string.settings)),
            MenuSheet.Item(ID_EXIT, R.drawable.ic_exit, getString(R.string.exit))
        )

        MenuSheet(this).show(items) { id ->
            when (id) {
                ID_SHIELD -> showShieldDialog()
                ID_NEW_TAB -> { tabs.newTab(Prefs.HOME_URL); refreshChrome() }
                ID_NEW_INCOGNITO -> {
                    tabs.newTab(Prefs.HOME_URL, incognito = true)
                    refreshChrome()
                    toast(getString(R.string.incognito_tab) + "\n" + getString(R.string.incognito_cookies_note))
                }
                ID_BOOKMARK -> {
                    if (bookmarked) {
                        db.removeBookmark(url)
                        toast(getString(R.string.bookmark_removed))
                    } else {
                        db.addBookmark(url, tab?.title)
                        toast(getString(R.string.bookmark_added))
                    }
                }
                ID_BOOKMARKS -> openList(ListActivity.MODE_BOOKMARKS)
                ID_HISTORY -> openList(ListActivity.MODE_HISTORY)
                ID_FIND -> showFindDialog()
                ID_TRANSLATE -> showTranslateSheet()
                ID_ADD_HOME -> addToHomeScreen()
                ID_DESKTOP -> tab?.let {
                    it.desktopMode = !it.desktopMode
                    it.webView?.let { w -> WebViewFactory.setDesktopMode(w, it.desktopMode) }
                    toast(getString(if (it.desktopMode) R.string.desktop_on else R.string.desktop_off))
                }
                ID_SHARE -> shareUrl(url, tab?.title)
                ID_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
                ID_EXIT -> finish()
            }
        }
    }

    private fun showTranslateSheet() {
        val tab = tabs.current ?: return
        val url = tab.displayUrl()
        if (url.isEmpty() || tab.isHome) return

        TranslateSheet(this, url) { translateUrl ->
            load(translateUrl)
        }.show()
    }

    /**
     * Masaüstü kısayolu: kimlik = host, ikon = favicon (yoksa uygulama ikonu).
     * Aynı host için tekrar eklenirse mevcut kısayol güncellenir.
     */
    private fun addToHomeScreen() {
        val tab = tabs.current ?: return
        val pageUrl = tab.pageUrl
        val host = UrlUtils.host(pageUrl)
        if (host.isEmpty() || !UrlUtils.isHttp(pageUrl)) return

        val icon = FaviconStore.load(this, host)
            ?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher)

        val label = tab.title.ifBlank { host }
        val shortcut = ShortcutInfoCompat.Builder(this, host)
            .setShortLabel(label)
            .setIcon(icon)
            .setIntent(
                Intent(this, WebappActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(android.net.Uri.parse(pageUrl))
                    .putExtra(WebappActivity.EXTRA_URL, pageUrl)
            )
            .build()

        // Всегда системный диалог подтверждения: так «Add» гарантированно
        // даёт иконку, а состояние кеша не может разойтись со столом
        // (например, после ручного удаления ярлыка). Повторное «Add»
        // снова спрашивает — подтвердил дважды = две иконки.
        val ok = ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        toast(if (ok) getString(R.string.shortcut_added) else getString(R.string.shortcut_failed))
    }

    private fun openList(mode: String) {
        pickUrl.launch(
            Intent(this, ListActivity::class.java).putExtra(ListActivity.EXTRA_MODE, mode)
        )
    }

    private fun shareUrl(url: String, title: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title.orEmpty())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun showFindDialog() {
        val web = tabs.current?.webView ?: return
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.find_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.find_in_page)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ -> web.findAllAsync(input.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> web.clearMatches() }
            .show()
    }

    // ------------------------------------------------------------------ sekmeler

    private fun showTabs() {
        TabsSheet(
            context = this,
            tabs = tabs.tabs,
            current = tabs.current,
            onSelect = { tab ->
                tabs.select(tab)
                refreshChrome()
            },
            onClose = { tab ->
                // Sekme kapandığında adres çubuğu anında (bir sonraki kareyi
                // beklemeden) yeni geçerli sekmeye eşitlenir; eski URL
                // kalmaz. TabManager.close önce geçerli sekmeyi değiştirip
                // sonra WebView'i yok eder — odak yarışı kalmaz.
                tabs.close(tab)
                refreshChromeNow()
            },
            onNew = { incognito ->
                tabs.newTab(Prefs.HOME_URL, incognito)
                refreshChrome()
            }
        ).show()
    }

    // ------------------------------------------------- WebViewClient geri çağrıları

    override fun onPageUrlChanged(tab: Tab, url: String) {
        if (tab !== tabs.current) return
        runOnUiThread { refreshChrome() }
    }

    override fun onPageStarted(tab: Tab) {
        if (tab !== tabs.current) return
        binding.progressBar.visibility = View.VISIBLE
        refreshChrome()
    }

    override fun onPageFinished(tab: Tab) {
        if (prefs.saveHistory && !tab.incognito && UrlUtils.isHttp(tab.pageUrl)) {
            db.recordVisit(tab.pageUrl, tab.title)
        }
        if (tab !== tabs.current) return
        binding.progressBar.visibility = View.GONE
        refreshChrome()
    }

    override fun onBlockedCountChanged(tab: Tab) {
        // Arka plan iş parçacığından gelir; sayaç kalkan penceresinde okunur.
    }

    override fun onExternalIntent(uri: Uri): Boolean = openExternally(uri)

    private fun openExternally(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        val allowed = setOf(
            "tel", "mailto", "sms", "mms", "geo", "market",
            "whatsapp", "tg", "t.me", "viber", "vk", "vksms", "ok",
            "skype", "zoommtg", "intent", "maps"
        )
        if (scheme !in allowed) {
            toast(getString(R.string.no_app_for_link))
            return true
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.no_app_for_link))
            true
        }
    }

    // ------------------------------------------------- WebChromeClient geri çağrıları

    override fun onProgress(tab: Tab, progress: Int) {
        if (tab !== tabs.current || tab.isHome) return
        binding.progressBar.progress = progress
        binding.progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
        updateReloadIcon()
    }

    override fun onTitle(tab: Tab, title: String) {
        if (tab === tabs.current) refreshChrome()
    }

    override fun onIcon(tab: Tab, icon: Bitmap?) {
        if (icon == null) return
        val host = UrlUtils.host(tab.pageUrl)
        if (host.isNotEmpty()) FaviconStore.save(this, host, icon)
    }

    override fun onNewWindow(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val src = tabs.current?.pageUrl.orEmpty()
        val page = if (UrlUtils.isHttp(src)) src else "https://local.invalid/"
        if (prefs.adBlockEnabled && adblock.engine.blocks(url, page, "popup")) {
            Toast.makeText(this, R.string.popup_blocked, Toast.LENGTH_SHORT).show()
            return true
        }
        tabs.newTab(url, tabs.current?.incognito == true, select = true)
        refreshChromeNow()
        return true
    }

    override fun onCreateNewTab(): Tab? {
        // Сразу с готовым WebView: transport из window.open должен получить
        // живой view, иначе открывается пустой таб с главной.
        val newTab = tabs.newWebTab()
        if (newTab != null) refreshChromeNow()
        return newTab
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        // WebView ana düzende bağlı kalır; tam ekran kabı opaktır ve üzerini
        // örter. Kaydırma konumu, sayfa olası bir yeniden düzenlenmeye karşı
        // sürekli izlenen lastScrollY'den alınır.
        fullscreenScrollY = tabs.current?.webView?.lastScrollY
            ?: tabs.current?.webView?.scrollY ?: 0
        binding.fullscreenContainer.addView(view)
        binding.fullscreenContainer.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSystemBars()
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        // Sayfa olası geç yeniden düzenlenmelere karşı konumu birkaç kez geri
        // koyar; araya kullanıcı kaydırması girmesin diye yalnızca konum
        // hâlâ beklenenden farklıysa uygulanır.
        val savedY = fullscreenScrollY
        val web = tabs.current?.webView
        if (savedY > 0 && web != null) {
            for (delay in longArrayOf(60, 250, 500)) {
                binding.webContainer.postDelayed({
                    if (web.scrollY != savedY && fullscreenScrollY == savedY) {
                        web.scrollTo(0, savedY)
                    }
                }, delay)
            }
        }
    }

    override fun onFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            filePicker.launch(params.createIntent())
            true
        } catch (_: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    // ------------------------------------------------------------------- indirme

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        size: Long
    ) {
        try {
            DownloadHelper.enqueue(this, url, userAgent, contentDisposition, mimeType, size)
            toast(getString(R.string.download_started))
        } catch (t: Throwable) {
            toast(t.message ?: "İndirme başarısız")
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** İlk kare çizildikten sonra WebView sağlayıcısının önden yüklenmesi. */
        const val WARM_UP_DELAY_MS = 400L

        const val ID_NEW_TAB = 1
        const val ID_NEW_INCOGNITO = 2
        const val ID_BOOKMARK = 3
        const val ID_BOOKMARKS = 4
        const val ID_HISTORY = 5
        const val ID_FIND = 6
        const val ID_DESKTOP = 7
        const val ID_SHARE = 8
        const val ID_SETTINGS = 9
        const val ID_EXIT = 10
        const val ID_TRANSLATE = 11
        const val ID_SHIELD = 12
        const val ID_ADD_HOME = 13
    }
}
