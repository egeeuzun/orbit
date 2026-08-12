package com.orbit.browser

import android.app.Application
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import com.orbit.browser.adblock.AdblockService
import com.orbit.browser.data.BrowserDb
import com.orbit.browser.data.Prefs

class OrbitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)

        AppCompatDelegate.setDefaultNightMode(
            when (prefs.theme) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        // Gizli sekmelerin ayrı bir veri dizini kullanabilmesi için süreç adı ayrımı.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val process = getProcessName()
            if (packageName != process) WebView.setDataDirectorySuffix(process)
        }

        // Açılış hızını katlamak için WebView sağlayıcısını, SharedPreferences önbelleğini
        // ve SQLite veritabanı bağlantısını uygulama açıldığı an arka planda paralel ısıt.
        // MainActivity açıldığında 0 ms I/O gecikmesi oluşur.
        Thread {
            try {
                CookieManager.getInstance()
                BrowserDb.get(this@OrbitApp)
            } catch (_: Throwable) {}
        }.start()

        if (prefs.adBlockEnabled) AdblockService.get(this).start()
    }
}
