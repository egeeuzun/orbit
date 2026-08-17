package com.orbit.browser.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil

/** Системный DownloadManager: общий путь скачивания для браузера и webapp-режима. */
object DownloadHelper {

    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        size: Long
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            userAgent?.let { addRequestHeader("User-Agent", it) }
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            )
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }
}
