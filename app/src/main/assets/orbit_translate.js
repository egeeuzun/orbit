/*
 * Orbit sayfa içi çeviri.
 *
 * Sayfa her zamanki gibi kullanıcının cihazından yüklenir; yalnızca metin
 * `OrbitTranslate` köprüsüyle uygulamaya gider, uygulama çeviriyi
 * `__orbitTr.done(...)` ile geri iter. Aracı sunucu (translate.goog) yok:
 * Wikipedia gibi veri merkezi trafiğini reddeden siteler de çevrilir ve
 * sayfanın CSP'si köprüyü engelleyemez.
 *
 * Birim = doğrudan metin içeren blok öğe (p, li, td, h1...). Bağlantı gibi
 * satır içi öğeler `<span data-o=N>` işaretiyle gönderilir; çeviride sıraları
 * değişebildiği için ("developed by X" -> "X tarafından geliştirildi") blok,
 * çevrilmiş sıraya göre yeniden kurulur. Satır içi öğeler AYNI nesneler olarak
 * taşınır (olay dinleyicileri korunur); çeviriden gelen başka hiçbir etiket
 * sayfaya girmez, yalnızca metni alınır. "Orijinali göster" her şeyi geri koyar.
 *
 * Yalnızca ekranda ve yakınında olan birimler çevrilir; kaydırdıkça devamı
 * gelir. Bellek ve istek sayısı sayfanın görünen kısmıyla sınırlı kalır.
 *
 * Android 5'in WebView'i (Chromium 37) için ES5: ok fonksiyonu, let/const,
 * Element.closest, WeakMap/WeakSet, IntersectionObserver kullanılmaz.
 */
(function () {
  if (window.__orbitTr) return;

  var SKIP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1,
    CODE: 1, PRE: 1, KBD: 1, SAMP: 1, VAR: 1, SVG: 1, MATH: 1, CANVAS: 1,
    IFRAME: 1, OBJECT: 1, TEMPLATE: 1, HEAD: 1
  };
  var INLINE = {
    A: 1, ABBR: 1, B: 1, BDI: 1, BDO: 1, CITE: 1, DFN: 1, EM: 1, FONT: 1, I: 1,
    LABEL: 1, MARK: 1, Q: 1, S: 1, SMALL: 1, SPAN: 1, STRONG: 1, SUB: 1, SUP: 1,
    TIME: 1, U: 1, BIG: 1, TT: 1, DEL: 1, INS: 1, DATA: 1
  };
  var MAX_CHARS = 4500;
  var MAX_UNITS = 30;
  var MAX_UNIT_CHARS = 12000;
  var MAX_INFLIGHT = 2;

  var token = 0;
  var active = false;
  var units = [];
  var pending = {};
  var nextId = 1;
  var inflight = 0;
  var touched = [];
  var added = [];
  var scanTimer = 0;
  var pumpTimer = 0;
  var observer = null;
  var wrappers = [];

  function name(n) { return (n.nodeName || '').toUpperCase(); }

  function skipped(el) {
    for (var e = el; e && e.nodeType === 1; e = e.parentNode) {
      if (SKIP[name(e)]) return true;
      if (e.getAttribute('translate') === 'no') return true;
      var cls = e.className;
      if (typeof cls === 'string' && (' ' + cls + ' ').indexOf(' notranslate ') >= 0) return true;
      if (e.isContentEditable) return true;
    }
    return false;
  }

  function blockOf(node) {
    var e = node.parentNode;
    while (e && e.nodeType === 1 && INLINE[name(e)]) e = e.parentNode;
    return e && e.nodeType === 1 ? e : null;
  }

  /** [root] altındaki metin düğümlerinden yeni birimler toplar. */
  function collect(root) {
    if (!root) return;
    if (root.nodeType === 3) { consider(root); return; }
    if (root.nodeType !== 1 || SKIP[name(root)]) return;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    var t;
    while ((t = w.nextNode())) consider(t);
  }

  /**
   * Doğrudan <body> içindeki metin (eski sayfalar: paragrafsız metin). Body'yi
   * birim yapmak bütün sayfayı yeniden kurmak demek; bunun yerine metnin
   * çevresindeki satır içi düğüm dizisi geçici bir <span>'e sarılır.
   * "Orijinali göster" sarmalayıcıyı da kaldırır.
   */
  function wrapRun(t) {
    var parent = t.parentNode;
    var first = t;
    var last = t;
    while (first.previousSibling && inlineNode(first.previousSibling)) first = first.previousSibling;
    while (last.nextSibling && inlineNode(last.nextSibling)) last = last.nextSibling;
    var w = document.createElement('span');
    parent.insertBefore(w, first);
    var n = first;
    while (n) {
      var next = n === last ? null : n.nextSibling;
      w.appendChild(n);
      n = next;
    }
    wrappers.push(w);
    return w;
  }

  function inlineNode(n) {
    return n.nodeType === 3 || (n.nodeType === 1 && INLINE[name(n)] && !n.__orbitW);
  }

  function consider(t) {
    if (!/\S/.test(t.nodeValue)) return;
    var b = blockOf(t);
    if (!b || b === document.documentElement) return;
    if (b === document.body) {
      if (t.parentNode !== document.body) return;
      b = wrapRun(t);
      b.__orbitW = 1;
    }
    if (b.__orbitU) return;
    b.__orbitU = 1;
    if (skipped(b)) return;
    units.push({ el: b, sent: false });
  }

  /** Adres, alan adı ya da e-posta gibi görünen metin çevrilmez ("android.googlesource.com"). */
  var VERBATIM = /^\s*(?:[a-z][a-z0-9+.-]*:\/\/\S+|[\w-]+(?:\.[\w-]+)+(?:\/\S*)?|[^\s@]+@[^\s@]+\.[^\s@]+)\s*$/i;

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** Bloğu çeviriye gidecek HTML'e çevirir; öğeler [map] içinde numaralanır. */
  function ser(node, map) {
    var out = '';
    for (var c = node.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) {
        out += esc(c.nodeValue);
      } else if (c.nodeType === 1) {
        var k = map.length;
        map.push(c);
        if (INLINE[name(c)] && !skipped(c) && !VERBATIM.test(c.textContent)) {
          out += '<span data-o="' + k + '">' + ser(c, map) + '</span>';
        } else {
          // Görsel, <br>, iç içe blok vb. olduğu gibi kalır; yalnızca yeri taşınır.
          out += '<span data-o="' + k + '" data-x="1"></span>';
        }
      }
    }
    return out;
  }

  function near(el) {
    if (!el.parentNode) return false;
    var r = el.getBoundingClientRect();
    if (!r.width && !r.height) return false;
    var h = window.innerHeight || document.documentElement.clientHeight;
    return r.bottom > -h && r.top < 2 * h;
  }

  function pump() {
    pumpTimer = 0;
    if (!active) return;
    while (inflight < MAX_INFLIGHT) {
      var batch = [];
      var texts = [];
      var chars = 0;
      for (var i = 0; i < units.length && batch.length < MAX_UNITS; i++) {
        var u = units[i];
        if (u.sent || !near(u.el)) continue;
        var map = [];
        var html = ser(u.el, map);
        u.sent = true;
        if (!/\S/.test(html.replace(/<[^>]*>/g, '')) || html.length > MAX_UNIT_CHARS) continue;
        if (batch.length && chars + html.length > MAX_CHARS) { u.sent = false; break; }
        batch.push({ u: u, map: map });
        texts.push(html);
        chars += html.length;
      }
      if (!batch.length) return;
      var id = nextId++;
      pending[id] = batch;
      inflight++;
      try {
        OrbitTranslate.request(token, id, JSON.stringify(texts));
      } catch (e) {
        delete pending[id];
        inflight--;
        return;
      }
    }
  }

  function schedulePump() {
    if (!pumpTimer) pumpTimer = setTimeout(pump, 200);
  }

  function save(el) {
    if (el.__orbitS) return;
    el.__orbitS = 1;
    var kids = [];
    for (var c = el.firstChild; c; c = c.nextSibling) kids.push(c);
    touched.push([el, kids]);
  }

  function clear(el) {
    while (el.firstChild) el.removeChild(el.firstChild);
  }

  /** [target]'ı çevrilmiş [src] ağacına göre yeniden kurar. */
  function rebuild(target, src, map, used) {
    clear(target);
    for (var c = src.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) {
        target.appendChild(document.createTextNode(c.nodeValue));
      } else if (c.nodeType === 1) {
        var k = c.getAttribute('data-o');
        var orig = k !== null ? map[+k] : null;
        if (orig && !used[k]) {
          used[k] = 1;
          if (!c.getAttribute('data-x')) rebuild(orig, c, map, used);
          target.appendChild(orig);
        } else {
          // Bizim işaretimiz değil: yalnızca metni alınır.
          target.appendChild(document.createTextNode(c.textContent));
        }
      }
    }
  }

  function apply(item, html) {
    var el = item.u.el;
    var map = item.map;
    if (!el.parentNode || typeof html !== 'string') return;
    var doc = document.implementation.createHTMLDocument('');
    doc.body.innerHTML = html;
    save(el);
    for (var i = 0; i < map.length; i++) {
      if (map[i].nodeType === 1 && map[i].parentNode && INLINE[name(map[i])]) save(map[i]);
    }
    var used = {};
    rebuild(el, doc.body, map, used);
    // Çeviride kaybolan öğeler (görsel, bağlantı) atılmaz; bloğun sonuna eklenir.
    for (var k = 0; k < map.length; k++) {
      if (!used[k] && !el.contains(map[k])) el.appendChild(map[k]);
    }
  }

  function onMutations(records) {
    if (!active) return;
    for (var i = 0; i < records.length; i++) {
      var a = records[i].addedNodes;
      for (var j = 0; j < a.length; j++) added.push(a[j]);
    }
    if (!scanTimer) {
      scanTimer = setTimeout(function () {
        scanTimer = 0;
        var list = added;
        added = [];
        for (var n = 0; n < list.length; n++) collect(list[n]);
        schedulePump();
      }, 800);
    }
  }

  window.__orbitTr = {
    start: function (t) {
      token = t;
      active = true;
      // Sayfanın beyan ettiği dil: "otomatik" kaynakta Google'ın her kısa
      // parçayı ayrı tahmin etmesi yerine bu kullanılır ("Talk" -> "Talc").
      try {
        OrbitTranslate.setPageLanguage(t, document.documentElement.lang || '');
      } catch (e) {}
      collect(document.body);
      window.addEventListener('scroll', schedulePump, false);
      window.addEventListener('resize', schedulePump, false);
      if (window.MutationObserver && !observer) {
        observer = new MutationObserver(onMutations);
        observer.observe(document.body, { childList: true, subtree: true });
      }
      pump();
    },
    done: function (id, results) {
      var batch = pending[id];
      if (!batch) return;
      delete pending[id];
      inflight--;
      if (!active) return;
      for (var i = 0; i < batch.length && i < results.length; i++) {
        try { apply(batch[i], results[i]); } catch (e) {}
      }
      schedulePump();
    },
    fail: function (id) {
      if (!pending[id]) return;
      delete pending[id];
      inflight--;
      // Başarısız birimler yeniden denenmez; sayfa döngüye girmesin.
      schedulePump();
    },
    restore: function () {
      active = false;
      if (observer) { observer.disconnect(); observer = null; }
      window.removeEventListener('scroll', schedulePump, false);
      window.removeEventListener('resize', schedulePump, false);
      for (var i = touched.length - 1; i >= 0; i--) {
        var el = touched[i][0];
        var kids = touched[i][1];
        clear(el);
        for (var j = 0; j < kids.length; j++) el.appendChild(kids[j]);
        el.__orbitS = 0;
      }
      for (var u = 0; u < units.length; u++) units[u].el.__orbitU = 0;
      for (var w = 0; w < wrappers.length; w++) {
        var wr = wrappers[w];
        var p = wr.parentNode;
        if (!p) continue;
        while (wr.firstChild) p.insertBefore(wr.firstChild, wr);
        p.removeChild(wr);
      }
      wrappers = [];
      touched = [];
      units = [];
      pending = {};
      inflight = 0;
      delete window.__orbitTr;
    }
  };
})();
