package com.orbit.browser.media

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.orbit.browser.browser.WebViewFactory
import java.lang.ref.WeakReference

/**
 * Sayfalarda gerçekten *duyulabilir* ses çalınıp çalınmadığını izler.
 *
 * Eskiden [MediaKeepAliveService] uygulama arka plana her geçtiğinde koşulsuz
 * başlatılıyordu: hiç ses çalmayan bir sekmeden çıkınca bile "Orbit ses
 * çalıyor" bildirimi düşüyordu. Bildirim ancak sayfada duyulabilir bir
 * `<audio>`/`<video>` varsa gösterilsin diye durum sayfadan bildirilir.
 *
 * Her belgeye (iframe'ler dahil) belge başında küçük bir gözcü betik
 * enjekte edilir; betik yalnızca durum *değiştiğinde* `1:<çerçeve>` ya da
 * `0:<çerçeve>` gönderir. Çerçeveden gelen yanıt kanalı saklandığı için
 * bildirimdeki "Durdur" düğmesi [pauseAll] ile sesi gerçekten susturabilir.
 *
 * Gözcü betik desteklenmeyen (eski) WebView sürümlerinde [isAudioPlaying]
 * yalnızca sistemin ses durumuna bakar.
 */
object MediaPlaybackTracker {

    /** Duyulabilir ses durumu değişince ana iş parçacığında çağrılır. */
    fun interface Listener {
        fun onPlaybackChanged(playing: Boolean)
    }

    private class Entry(
        val ref: WeakReference<WebView>,
        val scriptless: Boolean,
        /** Gözcü betik desteklenmiyor: duraklat/sürdür `evaluateJavascript` ile ana çerçeveye gider. */
        val fallback: Boolean
    ) {
        /** Şu an ses veren çerçeveler: kimlik → o çerçeveye yanıt kanalı. */
        val playing = HashMap<String, JavaScriptReplyProxy>()

        /** Bildirimden duraklatılan çerçeveler; "Oynat" bunlara gider. */
        val paused = HashMap<String, JavaScriptReplyProxy>()
    }

    private val entries = ArrayList<Entry>()
    private var listener: Listener? = null

    /** Bildirim hizmetinin dinleyicisi; [listener] ile aynı anda çağrılır. */
    @Volatile
    private var serviceListener: Listener? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Sayfaların bildirdiği güncel durum. */
    val isPlaying: Boolean get() = playing

    /** Sayfalar durum bildirebiliyor mu (gözcü betik destekli WebView)? */
    val reportsState: Boolean get() = supported

    @Volatile
    private var playing = false

    /** Her gezinmede ([reset]) okunur; özellik sorgusu bir kez yapılır. */
    private val supported: Boolean
        get() = WebViewFactory.Features.webMessageListener &&
            WebViewFactory.Features.documentStartScript

    fun setListener(l: Listener?) {
        listener = l
    }

    /** Yalnızca hâlâ kayıtlı olan dinleyiciyi kaldırır (activity yeniden
     *  yaratılırken yenisinin kaydını silmemek için). */
    fun clearListener(l: Listener) {
        if (listener === l) listener = null
    }

    fun setServiceListener(l: Listener?) {
        serviceListener = l
    }

    /** Yeni WebView'e gözcü betiği ve mesaj kanalını bağlar. */
    fun attach(web: WebView) {
        if (!supported) {
            // Eski WebView (mesaj dinleyicisi/belge başı betiği yok): durum
            // raporu alınamaz ama bildirimden duraklat/sürdür yine de
            // çalışsın diye görünüm zayıf referansla tutulur.
            synchronized(entries) {
                prune()
                entries.add(Entry(WeakReference(web), scriptless = true, fallback = true))
            }
            return
        }
        // JavaScript kapalıysa gözcü betik hiç çalışmaz; o görünüm için
        // sayfadan durum beklenemez, karar sistemin ses durumuna kalır.
        val entry = Entry(WeakReference(web), !web.settings.javaScriptEnabled, fallback = false)
        try {
            WebViewCompat.addWebMessageListener(
                web,
                JS_OBJECT,
                setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        onFrameReport(entry, message.data, replyProxy)
                    }
                }
            )
            WebViewCompat.addDocumentStartJavaScript(web, WATCH_SCRIPT, setOf("*"))
        } catch (t: Throwable) {
            Log.w(TAG, "Ses gözcüsü bağlanamadı: ${t.message}", t)
            try {
                WebViewCompat.removeWebMessageListener(web, JS_OBJECT)
            } catch (_: Throwable) {
            }
            return
        }
        synchronized(entries) {
            prune()
            entries.add(entry)
        }
    }

    /** WebView yok edilirken çağrılır; sayaçta iz bırakmaz. */
    fun detach(web: WebView) {
        if (supported) {
            try {
                WebViewCompat.removeWebMessageListener(web, JS_OBJECT)
            } catch (_: Throwable) {
            }
        }
        synchronized(entries) {
            entries.removeAll { it.ref.get() === web || it.ref.get() == null }
        }
        publish()
    }

    /**
     * Ana çerçeve yeni bir belgeye geçti: eski belgenin çerçeveleri artık yok.
     *
     * Çerçeve DOM'dan koparıldığında `pagehide` her zaman gelmediği için
     * sayaç burada da sıfırlanır; yoksa "çalıyor" durumu takılı kalırdı.
     */
    fun reset(web: WebView) {
        if (!supported) return
        synchronized(entries) {
            entries.firstOrNull { it.ref.get() === web }?.let {
                it.playing.clear()
                it.paused.clear()
            }
        }
        publish()
    }

    /**
     * Bildirimin "Duraklat"/"Durdur" düğmesi: ses veren her çerçevedeki
     * medyayı duraklatır. Yalnızca o an ses verdiğini bildiren çerçevelere
     * mesaj gider; bunlar [resumeAll] için akılda tutulur. Gözcü betiği
     * olmayan görünümlerde ana çerçeveye doğrudan betik gönderilir.
     */
    fun pauseAll() {
        val proxies = ArrayList<JavaScriptReplyProxy>()
        val fallbacks = ArrayList<WebView>()
        synchronized(entries) {
            prune()
            for (e in entries) {
                if (e.fallback) {
                    e.ref.get()?.let { fallbacks.add(it) }
                    continue
                }
                for ((frame, proxy) in e.playing) {
                    e.paused[frame] = proxy
                    proxies.add(proxy)
                }
                e.playing.clear()
            }
        }
        for (proxy in proxies) {
            try {
                proxy.postMessage(MSG_PAUSE)
            } catch (_: Throwable) {
            }
        }
        if (fallbacks.isNotEmpty()) main.post { for (w in fallbacks) evalQuietly(w, FALLBACK_PAUSE_JS) }
        publish()
    }

    /**
     * Bildirimin "Oynat" düğmesi: [pauseAll] ile duraklatılan çerçevelerde
     * medyayı sürdürür. Çerçeve sesi gerçekten başlatınca `1:` raporuyla
     * durum kendiliğinden güncellenir.
     */
    fun resumeAll() {
        val proxies = ArrayList<JavaScriptReplyProxy>()
        val fallbacks = ArrayList<WebView>()
        synchronized(entries) {
            prune()
            for (e in entries) {
                if (e.fallback) {
                    e.ref.get()?.let { fallbacks.add(it) }
                    continue
                }
                proxies.addAll(e.paused.values)
                e.paused.clear()
            }
        }
        for (proxy in proxies) {
            try {
                proxy.postMessage(MSG_PLAY)
            } catch (_: Throwable) {
            }
        }
        if (fallbacks.isNotEmpty()) main.post { for (w in fallbacks) evalQuietly(w, FALLBACK_PLAY_JS) }
    }

    private fun evalQuietly(web: WebView, js: String) {
        try {
            web.evaluateJavascript(js, null)
        } catch (_: Throwable) {
        }
    }

    /**
     * Süreci canlı tutmak (ve bildirim göstermek) gerçekten gerekli mi?
     *
     * İki bağımsız kanıt aranır: sayfanın kendi bildirdiği oynatma durumu ve
     * sistemin müzik akışının etkin olması. Sayfadan gelen durum bir çerçeve
     * haber vermeden kaybolursa sistem tarafı, sistem tarafı başka bir
     * uygulamanın sesiyle doluysa sayfa tarafı yanlış bildirimi engeller.
     */
    fun isAudioPlaying(context: Context): Boolean {
        val systemAudible = try {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
        } catch (_: Throwable) {
            false
        }
        // Gözcü betik yoksa (eski WebView ya da JS kapalı) elde yalnızca
        // sistemin ses durumu var.
        val watched = supported && !hasScriptlessView()
        return if (watched) playing && systemAudible else systemAudible
    }

    private fun onFrameReport(entry: Entry, raw: String?, proxy: JavaScriptReplyProxy) {
        val data = raw ?: return
        val sep = data.indexOf(':')
        if (sep != 1 || data.length < 3) return
        val frame = data.substring(2)
        synchronized(entries) {
            // Bağlantı koptuysa (WebView yok edildi) rapor düşürülür.
            if (entry.ref.get() == null) return@synchronized
            when (data[0]) {
                '1' -> entry.playing[frame] = proxy
                '0' -> entry.playing.remove(frame)
                else -> return@synchronized
            }
        }
        publish()
    }

    private fun hasScriptlessView(): Boolean = synchronized(entries) {
        prune()
        entries.any { it.scriptless }
    }

    private fun prune() {
        entries.removeAll { it.ref.get() == null }
    }

    private fun publish() {
        val now = synchronized(entries) {
            prune()
            entries.any { it.playing.isNotEmpty() }
        }
        if (now == playing) return
        playing = now
        listener?.onPlaybackChanged(now)
        serviceListener?.onPlaybackChanged(now)
    }

    private const val TAG = "MediaTracker"
    private const val JS_OBJECT = "OrbitMedia"
    private const val MSG_PAUSE = "pause"
    private const val MSG_PLAY = "play"

    /**
     * Gözcü betiği olmayan (eski) WebView için ana çerçeve yedeği: çalan
     * öğeleri duraklatıp `__orbitPaused` içinde saklar, "Oynat" oradan sürdürür.
     */
    private const val FALLBACK_PAUSE_JS = """
        (function(){try{var p=window.__orbitPaused=window.__orbitPaused||[];
        var els=document.querySelectorAll('video,audio');
        for(var i=0;i<els.length;i++){var el=els[i];
        if(!el.paused&&!el.ended){try{el.pause();if(p.indexOf(el)<0)p.push(el);}catch(e){}}}}catch(e){}})();
    """
    private const val FALLBACK_PLAY_JS = """
        (function(){try{var p=window.__orbitPaused||[];window.__orbitPaused=[];
        for(var i=0;i<p.length;i++){try{var r=p[i].play();if(r&&r.catch)r.catch(function(){});}catch(e){}}}catch(e){}})();
    """

    /**
     * Belge başında her çerçevede çalışan gözcü.
     *
     * DOM'daki medya öğeleri belge üzerinde yakalama (capture) aşamasında
     * dinlenir — medya olayları köpürmez ama yakalama aşaması ata düğümlere
     * uğrar. `new Audio(...)` ile yaratılıp DOM'a hiç eklenmeyen öğeler
     * (birçok radyo oynatıcısı böyle çalışır) olay ağacında görünmediği için
     * ayrıca `play()` çağrısı sarmalanarak izlenir.
     */
    private const val WATCH_SCRIPT = """
        (function () {
          var api = window.OrbitMedia;
          if (!api || window.__orbitMediaWatch) return;
          window.__orbitMediaWatch = 1;

          var id = Math.random().toString(36).slice(2) + Date.now().toString(36);
          var last = 0;
          var extra = [];

          function audible(el) {
            return !el.paused && !el.ended && !el.muted && el.volume > 0;
          }
          function anyAudible() {
            var els = document.querySelectorAll('video,audio');
            for (var i = 0; i < els.length; i++) { if (audible(els[i])) return true; }
            for (var j = 0; j < extra.length; j++) {
              if (audible(extra[j])) return true;
            }
            return false;
          }
          function send(on) {
            if (on === last) return;
            last = on;
            try { api.postMessage((on ? '1:' : '0:') + id); } catch (e) {}
          }
          function scan() { send(anyAudible() ? 1 : 0); }

          var EVENTS = ['play', 'playing', 'pause', 'ended', 'emptied', 'abort', 'volumechange'];
          for (var i = 0; i < EVENTS.length; i++) {
            document.addEventListener(EVENTS[i], scan, true);
          }

          try {
            var proto = HTMLMediaElement.prototype;
            var play = proto.play;
            proto.play = function () {
              try {
                if (!this.isConnected && extra.indexOf(this) < 0) {
                  if (extra.length > 32) extra.shift();
                  extra.push(this);
                  for (var k = 0; k < EVENTS.length; k++) {
                    this.addEventListener(EVENTS[k], scan, true);
                  }
                }
                setTimeout(scan, 0);
              } catch (e) {}
              return play.apply(this, arguments);
            };
          } catch (e) {}

          window.addEventListener('pagehide', function () { send(0); }, true);
          var pausedByOrbit = [];
          function pauseOne(el) {
            if (el.paused || el.ended) return;
            try { el.pause(); if (pausedByOrbit.indexOf(el) < 0) pausedByOrbit.push(el); } catch (e) {}
          }
          api.onmessage = function (ev) {
            if (ev.data === 'pause') {
              var els = document.querySelectorAll('video,audio');
              for (var i = 0; i < els.length; i++) { pauseOne(els[i]); }
              for (var j = 0; j < extra.length; j++) { pauseOne(extra[j]); }
              scan();
            } else if (ev.data === 'play') {
              // Yalnızca bizim duraklattıklarımız sürdürülür; sayfa daha önce
              // etkinleştirildiği için otomatik oynatma kısıtı devreye girmez.
              var list = pausedByOrbit; pausedByOrbit = [];
              for (var k = 0; k < list.length; k++) {
                try { var r = list[k].play(); if (r && r.catch) r.catch(function () {}); } catch (e) {}
              }
              setTimeout(scan, 0);
            }
          };
        })();
    """
}
