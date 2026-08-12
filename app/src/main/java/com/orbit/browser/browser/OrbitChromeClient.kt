package com.orbit.browser.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Başlık/ikon/ilerleme, tam ekran video, dosya seçici ve yeni pencere
 * (hedefi `_blank` olan bağlantılar) yönetimi.
 */
class OrbitChromeClient(
    private val tab: Tab,
    private val host: Host
) : WebChromeClient() {

    interface Host {
        fun onProgress(tab: Tab, progress: Int)
        fun onTitle(tab: Tab, title: String)
        fun onIcon(tab: Tab, icon: Bitmap?)
        fun onNewWindow(url: String?): Boolean
        fun onCreateNewTab(): Tab?
        fun onShowCustomView(view: View, callback: CustomViewCallback)
        fun onHideCustomView()
        fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        tab.progress = newProgress
        host.onProgress(tab, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        tab.title = title.orEmpty()
        host.onTitle(tab, tab.title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        host.onIcon(tab, icon)
    }

    /**
     * Yeni sekmede açılan bağlantılar hemen yeni sekmeye geçirilir.
     * Adres veya mesaj hedefine göre yeni sekme oluşturulup görünüm anında eşitlenir.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        val result = view.hitTestResult
        val url = result.extra
        if (!url.isNullOrEmpty()) {
            return host.onNewWindow(url)
        }
        if (resultMsg != null) {
            val newTab = host.onCreateNewTab() ?: return false
            val web = newTab.webView ?: return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = web
            resultMsg.sendToTarget()
            return true
        }
        return false
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        host.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        host.onHideCustomView()
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = host.onFileChooser(filePathCallback, fileChooserParams)

    /** Konum izni varsayılan olarak reddedilir. */
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin, false, false)
    }

    /** Kamera/mikrofon istekleri de açıkça reddedilir. */
    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }
}
