package com.orbit.browser.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil

/** Системный DownloadManager: общий путь скачивания для браузера и webapp-режима. */
object DownloadHelper {

    private var lastUrl: String? = null
    private var lastTs: Long = 0

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        size: Long
    ) {
        // Перехват в shouldInterceptRequest + системный DownloadListener могут
        // сработать на один URL подряд — запускаем одну реальную загрузку.
        val now = System.currentTimeMillis()
        if (url == lastUrl && now - lastTs < 1500) return
        lastUrl = url
        lastTs = now

        val guess = URLUtil.guessFileName(url, contentDisposition, mimeType)
        // guessFileName без content-disposition роняет имена с точками
        // (AuroraStore-4.8.4.apk -> AuroraStore-4.bin): вернём нормальное
        // имя из пути, если сервер не прислал заголовок.
        val name = if (guess.endsWith(".bin", ignoreCase = true)) {
            val seg = url.substringBefore('?').substringAfterLast('/')
            if (seg.contains('.') && !seg.contains("://") && seg.length > 4) seg else guess
        } else guess
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            setTitle(name)
            userAgent?.let { addRequestHeader("User-Agent", it) }
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                name
            )
        }
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (_: Throwable) {
        }
    }
}
