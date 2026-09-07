package com.orbit.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.orbit.browser.R
import com.orbit.browser.ui.MainActivity

/**
 * Arka planda ses (radyo, video sesi) çalarken sürecin öldürülmemesi için
 * bekletici (foreground) hizmet — ve o sesin uzaktan kumandası.
 *
 * Kendi başına ses çalmıyor; sistemin `com.orbit.browser` sürecine bir
 * "foreground" kotası sağlar, böylece WebView'in ses hattı kesilmez. Bunun
 * yanında bir [MediaSession] açar: bildirim `MediaStyle` ile kilit
 * ekranında ve bildirim panelinde Duraklat/Oynat/Durdur düğmeleriyle
 * görünür, kulaklık ve Bluetooth düğmeleri de aynı oturuma düşer.
 *
 * Platformun kendi `android.media.session` API'si kullanılır: API 21'den
 * beri var, ek kütüphane yok. API 26 altında oturumun medya düğmelerini
 * alması için (o sürümlerde zorunlu olan) bayraklar da verilir.
 *
 * Hizmet **yalnızca gerçekten duyulabilir ses varken** başlatılır; kararı
 * [MediaPlaybackTracker.isAudioPlaying] verir. Duraklat/Oynat sayfadaki
 * medyaya [MediaPlaybackTracker] üzerinden iletilir.
 */
class MediaKeepAliveService : Service() {

    private var session: MediaSession? = null
    private var title: String = ""
    private var subtitle: String = ""
    private var playing = true
    private val main = Handler(Looper.getMainLooper())

    /** Sayfa gerçekten çalmaya başladı/durdu: düğmeler ve oturum durumu eşitlenir. */
    private val trackerListener = MediaPlaybackTracker.Listener { now ->
        main.post {
            if (now) userPaused = false
            if (session != null) render(now)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = try {
            MediaSession(this, "Orbit").apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    // API 26 öncesinde bu bayraklar olmadan oturum ne kulaklık
                    // düğmelerini ne de kilit ekranı denetimlerini alır.
                    @Suppress("DEPRECATION")
                    setFlags(
                        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                    )
                }
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = resume()
                    override fun onPause() = pause()
                    override fun onStop() = stopPlayback()
                })
                isActive = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaSession açılamadı: ${t.message}")
            null
        }
        MediaPlaybackTracker.setServiceListener(trackerListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pause()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> {
                resume()
                return START_NOT_STICKY
            }
        }
        intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
        intent?.getStringExtra(EXTRA_SUBTITLE)?.let { subtitle = it }
        ensureChannel()
        // Sayfa durum bildirebiliyorsa ona güvenilir; bildiremiyorsa (eski
        // WebView) hizmet zaten yalnız ses çalarken başlatıldığı için "çalıyor".
        playing = !userPaused
        render(playing)
        // START_STICKY değil: süreç öldükten sonra sistem hizmeti boş bir
        // niyetle yeniden başlatıyor ve hiçbir ses çalmadığı hâlde
        // "Orbit ses çalıyor" bildirimi geri geliyordu.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaPlaybackTracker.setServiceListener(null)
        userPaused = false
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Throwable) {
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ denetim

    private fun pause() {
        userPaused = true
        MediaPlaybackTracker.pauseAll()
        render(false)
    }

    private fun resume() {
        userPaused = false
        MediaPlaybackTracker.resumeAll()
        render(true)
        // Sayfa durum bildirebiliyor ama kısa sürede "çalıyor" demediyse
        // (öğe kaldırılmış, oynatma reddedilmiş) düğme "Oynat"ta kalır.
        if (MediaPlaybackTracker.reportsState) {
            main.postDelayed({
                if (session != null && !MediaPlaybackTracker.isPlaying) {
                    userPaused = true
                    render(false)
                }
            }, RESUME_GRACE_MS)
        }
    }

    /** "Durdur": ses gerçekten susturulur, sonra bildirim ve oturum kapanır. */
    private fun stopPlayback() {
        MediaPlaybackTracker.pauseAll()
        userPaused = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ---------------------------------------------------------------- görünüm

    /** Oturum durumunu ve bildirimi verilen oynatma durumuna göre kurar. */
    private fun render(isPlaying: Boolean) {
        playing = isPlaying
        updateSession(isPlaying)
        val notif = buildNotification(isPlaying)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ tipli foreground; ses aktivitesi olarak bildirilir.
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Bildirim güncellenemedi: ${t.message}")
        }
    }

    private fun updateSession(isPlaying: Boolean) {
        val s = session ?: return
        try {
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { getString(R.string.app_name) })
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                    .build()
            )
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
                    )
                    .setState(
                        if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (isPlaying) 1f else 0f
                    )
                    .build()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Oturum durumu yazılamadı: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(isPlaying: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            ),
            piFlags()
        )
        val toggle = if (isPlaying) {
            Notification.Action.Builder(
                R.drawable.ic_pause, getString(R.string.media_keepalive_pause), servicePi(1, ACTION_PAUSE)
            ).build()
        } else {
            Notification.Action.Builder(
                R.drawable.ic_play, getString(R.string.media_keepalive_play), servicePi(2, ACTION_PLAY)
            ).build()
        }
        val stop = Notification.Action.Builder(
            R.drawable.ic_stop, getString(R.string.media_keepalive_stop), servicePi(3, ACTION_STOP)
        ).build()

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        b.setContentTitle(title.ifBlank { getString(R.string.media_keepalive_title) })
            .setContentText(
                if (isPlaying) subtitle.ifBlank { getString(R.string.media_keepalive_text) }
                else getString(R.string.media_keepalive_paused)
            )
            .setSmallIcon(R.drawable.ic_orbit_logo)
            .setContentIntent(openApp)
            .setDeleteIntent(servicePi(3, ACTION_STOP))
            .addAction(toggle)
            .addAction(stop)
            // Sürerken kaydırılarak kapatılamaz; duraklatılmışsa kaydırmak "Durdur" demektir.
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Kilit ekranında düğmeleriyle görünsün.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
        return b.build()
    }

    private fun servicePi(code: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            Intent(this, MediaKeepAliveService::class.java).setAction(action),
            piFlags()
        )

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.media_keepalive_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.media_keepalive_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "MediaKeepAlive"
        private const val CHANNEL_ID = "orbit_media_keepalive"
        private const val NOTIFICATION_ID = 4211
        private const val ACTION_STOP = "com.orbit.browser.media.STOP"
        private const val ACTION_PAUSE = "com.orbit.browser.media.PAUSE"
        private const val ACTION_PLAY = "com.orbit.browser.media.PLAY"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val RESUME_GRACE_MS = 2500L

        /**
         * Kullanıcı bildirimden "Duraklat"a bastı. Sayfa susunca gelen
         * "çalmıyor" raporu bu durumda hizmeti kapatmamalı: bildirim "Oynat"
         * düğmesiyle kalır.
         */
        @Volatile
        var userPaused: Boolean = false
            private set

        fun start(context: Context, title: String? = null, subtitle: String? = null) {
            val intent = Intent(context, MediaKeepAliveService::class.java)
                .putExtra(EXTRA_TITLE, title.orEmpty())
                .putExtra(EXTRA_SUBTITLE, subtitle.orEmpty())
            // Android 12+ uygulama tamamen arka plandayken ön plan hizmeti
            // başlatmayı reddedebilir; bu bir çökme sebebi olmamalı.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Medya hizmeti başlatılamadı: " + t.message)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaKeepAliveService::class.java))
        }
    }
}
