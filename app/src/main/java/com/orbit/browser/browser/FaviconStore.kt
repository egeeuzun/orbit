package com.orbit.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Favicon deposu: `files/favicons/<host>.png`. Kaydedilirken merkez kırpılır
 * ve ölçeklenir — başlatıcı ikonları kare olmak zorundadır.
 *
 * Kaydetme arka planda yapılır: eskiden `onReceivedIcon` içinde, yani ana
 * iş parçacığında ölçekleme + PNG sıkıştırma çalışıyordu ve zayıf bir
 * çekirdekte her sayfa açılışına onlarca milisaniyelik takılma ekliyordu.
 * Aynı ana bilgisayar için taze bir dosya varsa hiç iş yapılmaz.
 */
object FaviconStore {

    const val ICON_SIZE_PX = 192

    /** Bu süreden yeni bir ikon dosyası yeniden yazılmaz. */
    private const val FRESH_MS = 24L * 60 * 60 * 1000

    /**
     * Çözülmüş kutucuk ikonları için bellek bütçesi: yığının 1/64'ü,
     * 1–4 MB arasına sıkıştırılmış. 1 GB'lık bir cihazda (hdpi, 12 kutucuk,
     * ikon başına 96x96 = 36 KB) tüm ızgara ~0,4 MB tutar; yüksek yoğunluklu
     * bir tablette ikonlar 192 pikselde kaldığından bütçe kendiliğinden
     * büyür ve kaydırırken yeniden çözme olmaz.
     */
    private val CACHE_BYTES: Int =
        (Runtime.getRuntime().maxMemory() / 64).coerceIn(1L * 1024 * 1024, 4L * 1024 * 1024).toInt()

    private val main = Handler(Looper.getMainLooper())

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "favicon-io").apply { isDaemon = true }
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "favicons").apply { if (!exists()) mkdirs() }

    fun path(context: Context, host: String): File =
        File(dir(context), "$host.png")

    /** Ana iş parçacığından çağrılır; iş arka plana devredilir. */
    fun save(context: Context, host: String, icon: Bitmap) {
        if (host.isBlank()) return
        val app = context.applicationContext
        io.execute {
            try {
                val target = path(app, host)
                if (target.isFile && System.currentTimeMillis() - target.lastModified() < FRESH_MS) {
                    return@execute
                }
                if (icon.isRecycled) return@execute
                val square = centerCropSquare(icon, ICON_SIZE_PX)
                FileOutputStream(target).use { out ->
                    square.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (square !== icon) square.recycle()
                // Site ilk kez ikon verdi: "ikonu yok" işareti ve eski
                // kopya düşer, başlangıç ekranı bir sonraki tazelemede
                // gerçek ikonu gösterir.
                missing.remove(host)
                cache.remove(host)
            } catch (_: Throwable) {
                // İkon kaydedilemezse kısayol uygulama ikonuyla açılır.
            }
        }
    }

    fun load(context: Context, host: String): Bitmap? {
        val f = path(context, host)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    // ------------------------------------------------- başlangıç ekranı ikonları

    /**
     * Kutucuk ikonları için küçük önbellek. Dosyalar 192 piksel kaydedilir
     * ama ızgarada 48dp (hdpi'de 72 piksel) çizilirler; tam boy çözmek
     * ikon başına 147 KB tutardı. Örnekleyerek çözülen 96 piksellik kopya
     * 36 KB — 1 MB'lık bütçeye ~28 site sığar ve aynı ikon bir daha
     * diskten okunmaz.
     */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * İkonu olmadığı görülen ana bilgisayarlar. Başlangıç ekranı her sekme
     * değişiminde tazelendiği için bu işaret olmasa aynı 12 kutucuk her
     * seferinde boşuna disk yoklardı. Site ziyaret edilip ikonu kaydedilince
     * [save] işareti kaldırır.
     */
    private val missing: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Önbellekteki ikon; yoksa null. Ana iş parçacığından ucuzdur. */
    fun cached(host: String): Bitmap? = cache.get(host)

    /**
     * İkonu arka planda okur ve [then]'i ana iş parçacığında çağırır.
     * İkon yoksa `null` gelir — çağıran taraf harf kutucuğunda kalır.
     */
    fun iconAsync(context: Context, host: String, targetPx: Int, then: (Bitmap?) -> Unit) {
        if (host.isBlank() || host in missing) {
            then(null)
            return
        }
        cache.get(host)?.let {
            then(it)
            return
        }
        val app = context.applicationContext
        io.execute {
            val bitmap = try {
                decodeSampled(path(app, host), targetPx)
            } catch (_: Throwable) {
                null
            }
            if (bitmap == null) missing.add(host) else cache.put(host, bitmap)
            main.post { then(bitmap) }
        }
    }

    /**
     * Dosyayı hedef boyutun altına düşmeyecek en büyük 2'nin kuvveti kadar
     * örnekleyerek çözer. Önce yalnızca başlık okunur (`inJustDecodeBounds`),
     * piksel verisi hiç açılmaz.
     */
    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || targetPx <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun centerCropSquare(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        if (side == size) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }
}
