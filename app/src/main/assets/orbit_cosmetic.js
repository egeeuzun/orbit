
/*
 * Orbit — kozmetik filtreleme çalışma zamanı.
 *
 * uBlock Origin'in içerik betiğinin yaptığı işin WebView karşılığı:
 *   1. Motorun sayfaya özel gizleme CSS'ini uygular.
 *   2. Prosedürel filtreleri (:has-text, :upward, :style, :remove) yürütür.
 *   3. DOM'daki gerçek class/id değerlerini toplayıp motora sorar ve yalnızca
 *      karşılığı olan genel seçicileri uygular. On binlerce seçiciyi baştan
 *      enjekte etmek yerine bu yol izlendiği için 1 GB RAM'li cihazda da
 *      stil ağacı şişmez.
 */
(function () {
  'use strict';
  if (window.__orbitCosmetic) return;
  window.__orbitCosmetic = true;

  var STYLE_ID = '__orbit_hide';
  var MAX_SEEN = 20000;      // takip edilen benzersiz class/id üst sınırı
  var MAX_PROCEDURAL = 800;  // sayfa başına prosedürel filtre üst sınırı
  var MAX_ROOTS = 48;        // bir turda ayrı ayrı taranacak yeni alt ağaç sayısı

  /*
   * Tarama aralığı uyarlanır. Sayfa yüklenirken sık sık yeni seçici çıkar ve
   * aralık kısa kalır; sonsuz kaydırmada olduğu gibi DOM sürekli değişip de
   * yeni bir şey çıkmıyorsa aralık iki katına çıkarak 2 saniyeye kadar
   * gevşer. Sabit 250 ms, uzun oturumlarda kaydırma boyunca sürekli DOM
   * taraması demekti.
   */
  var MIN_THROTTLE = 250;
  var MAX_THROTTLE = 2000;
  var throttle = MIN_THROTTLE;

  var styleNode = null;
  var procedural = [];
  var genericOn = false;
  var seenClass = Object.create(null);
  var seenId = Object.create(null);
  var seenCount = 0;
  var pendingClass = [];
  var pendingId = [];
  var pendingRoots = [];
  var scanAll = false;       // eklenen düğüm sayısı sınırı aştı: baştan tara
  var dirty = false;         // son turdan beri DOM değişti mi
  var timer = null;

  // ------------------------------------------------------------------ CSS

  function ensureStyle() {
    if (styleNode && styleNode.parentNode) return styleNode;
    var root = document.head || document.documentElement;
    if (!root) return null;
    styleNode = document.createElement('style');
    styleNode.id = STYLE_ID;
    styleNode.type = 'text/css';
    root.appendChild(styleNode);
    return styleNode;
  }

  /*
   * Aynı CSS iki kez uygulanabilir (belge başı + sayfa sonu enjeksiyonu),
   * bu yüzden uygulanmışlar hafif bir anahtarla işaretlenir.
   */
  var appliedCss = Object.create(null);

  function cssKey(css) {
    return css.length + '|' + css.slice(0, 48);
  }

  function applyCss(css) {
    if (!css) return;
    var key = cssKey(css);
    if (appliedCss[key]) return;
    var node = ensureStyle();
    if (!node) {
      // Belge henüz yok; bir sonraki karede yeniden dene.
      setTimeout(function () { applyCss(css); }, 16);
      return;
    }
    appliedCss[key] = 1;
    node.appendChild(document.createTextNode(css));
  }

  // ------------------------------------------------- prosedürel filtreler

  function toArray(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(list[i]);
    return out;
  }

  /** `/desen/i` biçimini gerçek RegExp'e, düz metni alt dize aramasına çevirir. */
  function textTest(arg) {
    if (arg.length > 2 && arg.charAt(0) === '/') {
      var end = arg.lastIndexOf('/');
      if (end > 0) {
        try {
          var re = new RegExp(arg.slice(1, end), arg.slice(end + 1));
          return function (s) { return re.test(s); };
        } catch (e) { /* geçersiz desen: alt dizeye düş */ }
      }
    }
    return function (s) { return s.indexOf(arg) !== -1; };
  }

  function upward(node, arg) {
    var n = parseInt(arg, 10);
    if (String(n) === String(arg).trim()) {
      var el = node;
      while (n-- > 0 && el) el = el.parentElement;
      return el;
    }
    return node.closest ? node.closest(arg) : null;
  }

  var SUPPORTED = { 'css-selector': 1, 'has-text': 1, 'upward': 1 };

  function isSupported(filter) {
    var ops = filter.selector;
    if (!ops || !ops.length || ops[0].type !== 'css-selector') return false;
    for (var i = 0; i < ops.length; i++) {
      if (!SUPPORTED[ops[i].type]) return false;
    }
    var action = filter.action;
    if (!action) return true;
    return action.type === 'style' || action.type === 'remove';
  }

  function evaluate(ops) {
    var nodes = null;
    for (var i = 0; i < ops.length; i++) {
      var op = ops[i];
      if (op.type === 'css-selector') {
        if (nodes === null) {
          try { nodes = toArray(document.querySelectorAll(op.arg)); }
          catch (e) { return []; }
        } else {
          nodes = nodes.filter(function (n) {
            return n.matches && n.matches(op.arg);
          });
        }
      } else if (op.type === 'has-text') {
        if (nodes === null) return [];
        var test = textTest(op.arg);
        nodes = nodes.filter(function (n) { return test(n.textContent || ''); });
      } else if (op.type === 'upward') {
        if (nodes === null) return [];
        var lifted = [];
        for (var j = 0; j < nodes.length; j++) {
          var up = upward(nodes[j], op.arg);
          if (up && lifted.indexOf(up) === -1) lifted.push(up);
        }
        nodes = lifted;
      } else {
        return [];
      }
      if (!nodes.length) return [];
    }
    return nodes || [];
  }

  /**
   * @return bu turda gerçekten *yeni* bir öğeye dokunuldu mu
   *
   * İşlenen öğeler filtre başına işaretlenir. Bu hem aynı öğeye her turda
   * yeniden dokunmayı önler (`:style()` kuralında `cssText` sınırsız
   * büyüyordu) hem de tarama aralığının ne zaman gevşetilebileceğini söyler.
   */
  function runProcedural() {
    var acted = false;
    for (var i = 0; i < procedural.length; i++) {
      var f = procedural[i];
      var nodes;
      try { nodes = evaluate(f.selector); } catch (e) { continue; }
      var mark = '__orbit' + i;
      for (var j = 0; j < nodes.length; j++) {
        var el = nodes[j];
        if (el[mark]) continue;
        el[mark] = 1;
        acted = true;
        if (!f.action) {
          if (el.style) el.style.setProperty('display', 'none', 'important');
        } else if (f.action.type === 'style') {
          if (el.style) el.style.cssText += ';' + f.action.arg;
        } else if (f.action.type === 'remove') {
          if (el.parentNode) el.parentNode.removeChild(el);
        }
      }
    }
    return acted;
  }

  // --------------------------------------------- genel kozmetik (class/id)

  /** Tek bir öğenin class/id değerlerini kuyruğa alır. */
  function take(el) {
    var cls = el.getAttribute('class');
    if (cls) {
      var parts = cls.split(/\s+/);
      for (var j = 0; j < parts.length; j++) {
        var c = parts[j];
        if (c && seenClass[c] === undefined) {
          seenClass[c] = 1;
          seenCount++;
          pendingClass.push(c);
        }
      }
    }
    var id = el.getAttribute('id');
    if (id && seenId[id] === undefined) {
      seenId[id] = 1;
      seenCount++;
      pendingId.push(id);
    }
  }

  function collect(root) {
    if (seenCount >= MAX_SEEN || !root.querySelectorAll) return;
    // Kökün kendisi de sayılır: reklam kapsayıcıları çoğu kez tek parça
    // olarak eklenir ve asıl seçici o düğümün kendi sınıfıdır.
    if (root.getAttribute) take(root);
    var nodes = root.querySelectorAll('[class],[id]');
    for (var i = 0; i < nodes.length && seenCount < MAX_SEEN; i++) {
      take(nodes[i]);
    }
  }

  function flush() {
    var roots = pendingRoots;
    pendingRoots = [];
    var changed = dirty;
    dirty = false;

    if (genericOn) {
      if (scanAll) {
        scanAll = false;
        if (document.documentElement) collect(document.documentElement);
      } else {
        for (var i = 0; i < roots.length; i++) collect(roots[i]);
      }
    }

    var found = false;
    if (genericOn && (pendingClass.length || pendingId.length)) {
      var cls = pendingClass, ids = pendingId;
      pendingClass = [];
      pendingId = [];
      found = true;
      try {
        // Eşzamansız: CSS hazır olduğunda uygulama `__orbit.css()` ile iter.
        OrbitCosmetic.requestGenericCss(JSON.stringify(cls), JSON.stringify(ids));
      } catch (e) { /* köprü yok: genel filtreleme atlanır */ }
    }

    // Prosedürel filtrelerin her biri kendi `querySelectorAll` turunu
    // çalıştırır; DOM değişmediyse sonuç da değişmez.
    var acted = (procedural.length && changed) ? runProcedural() : false;

    // Bir şey bulunduğu sürece sık tara; sayfa durulunca aralığı gevşet.
    throttle = (found || acted) ? MIN_THROTTLE : Math.min(throttle * 2, MAX_THROTTLE);
  }

  function schedule() {
    if (timer !== null) return;
    if (!genericOn && !procedural.length) return;   // yapacak iş yok
    timer = setTimeout(function () {
      timer = null;
      // Boşta çalıştır: kaydırma ve dokunma karesi taramanın önünde kalsın.
      if (window.requestIdleCallback) {
        requestIdleCallback(flush, { timeout: 1000 });
      } else {
        flush();
      }
    }, throttle);
  }

  function scan(root) {
    if (genericOn) {
      pendingRoots.push(root);
      dirty = true;
    }
    schedule();
  }

  // ------------------------------------------------------------ gözlemci

  var observer = null;

  /**
   * Gözlemci yalnızca yapacak iş varken bağlanır.
   *
   * `subtree: true` bir gözlemci, geri çağrısı boş olsa bile Blink'i her DOM
   * değişikliğinde kayıt üretmeye zorlar. Sayfaya ait kozmetik filtre yoksa
   * ve genel filtreleme kapalıysa bu maliyet karşılıksızdı.
   */
  function observe() {
    if (observer) return;
    if (!genericOn && !procedural.length) return;
    if (!window.MutationObserver || !document.documentElement) return;
    /*
     * Gözlemci geri çağrısı sayfanın betik iş parçacığında, hem de her
     * mikro görevden sonra çalışır. Burada tarama yapmak — her eklenen alt
     * ağaç için `querySelectorAll` — ağır sayfalarda doğrudan takılma
     * demekti. Artık yalnızca kökler biriktirilir; tarama boştaki tura
     * bırakılır.
     */
    observer = new MutationObserver(function (records) {
      dirty = true;
      if (genericOn && !scanAll) {
        for (var i = 0; i < records.length; i++) {
          var added = records[i].addedNodes;
          for (var j = 0; j < added.length; j++) {
            if (added[j].nodeType !== 1) continue;
            if (pendingRoots.length >= MAX_ROOTS) {
              // Çok fazla ayrı kök birikti: tek seferde baştan taramak ucuz.
              pendingRoots.length = 0;
              scanAll = true;
              i = records.length;
              break;
            }
            pendingRoots.push(added[j]);
          }
        }
      }
      schedule();
    });
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  }

  // ------------------------------------------------------- dışa açık API

  window.__orbit = {
    css: applyCss,

    procedural: function (json) {
      if (!json) return;
      // CSS gibi, prosedürel liste de iki kez gönderilebilir.
      var key = cssKey(json);
      if (appliedCss[key]) return;
      appliedCss[key] = 1;
      var arr;
      try { arr = JSON.parse(json); } catch (e) { return; }
      for (var i = 0; i < arr.length && procedural.length < MAX_PROCEDURAL; i++) {
        var f = arr[i];
        if (typeof f === 'string') {
          try { f = JSON.parse(f); } catch (e) { continue; }
        }
        if (f && isSupported(f)) procedural.push(f);
      }
      // Yeni filtreler geldi: bir sonraki tur hemen ve dolu çalışsın.
      dirty = true;
      throttle = MIN_THROTTLE;
      start();
    },

    generic: function (on) {
      genericOn = !!on;
      throttle = MIN_THROTTLE;
      if (genericOn) start();
    }
  };

  /**
   * Gözlemciyi ve ilk taramayı başlatır. Motordan filtre gelene kadar
   * hiçbiri kurulmaz; belge henüz yoksa bir sonraki karede yeniden denenir.
   */
  function start() {
    if (!document.documentElement) {
      setTimeout(start, 16);
      return;
    }
    observe();
    if (genericOn) scan(document.documentElement); else schedule();
  }

  // Tek sayfalık uygulamalarda gövde çoğu kez bu olaydan sonra dolar.
  document.addEventListener('DOMContentLoaded', function ()
    if (genericOn) scan(document.documentElement);
  }, { once: true });
})();
