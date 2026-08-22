package com.orbit.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Favicon deposu: `files/favicons/<host>.png`. Kaydedilirken merkez kırpılır
 * ve ölçeklenir — başlatıcı ikonları kare olmak zorundadır.
 */
object FaviconStore {

    const val ICON_SIZE_PX = 192

    private fun dir(context: Context): File =
        File(context.filesDir, "favicons").apply { if (!exists()) mkdirs() }

    fun path(context: Context, host: String): File =
        File(dir(context), "$host.png")

    fun save(context: Context, host: String, icon: Bitmap) {
        if (host.isBlank()) return
        val square = centerCropSquare(icon, ICON_SIZE_PX)
        FileOutputStream(path(context, host)).use { out ->
            square.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    fun load(context: Context, host: String): Bitmap? {
        val f = path(context, host)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun centerCropSquare(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return if (side == size) cropped
        else Bitmap.createScaledBitmap(cropped, size, size, true)
    }
}
