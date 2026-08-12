//! Geliştirme yardımcısı: istek başına maliyetin nereye gittiğini ölçer.
//!
//! `cargo run --release --example cost -- <filtre-dizini>`
//!
//! Ölçülenler:
//!   1. `Request::new` (iki URL ayrıştırma + iki genel sonek araması)
//!   2. `Request::preparsed` (ayrıştırma yok)
//!   3. `check_network_request` (asıl eşleştirme)
//!   4. Sayfa başına üretilen kozmetik CSS boyutu

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::time::Instant;

const ITERS: u32 = 20_000;

/// Ana çerçeve adresinden ana bilgisayar adını ayıklar; `Request::preparsed`
/// için gereken tek şey bu ve genel sonek listesine hiç bakmaz.
fn hostname_of(url: &str) -> &str {
    let rest = match url.find("://") {
        Some(i) => &url[i + 3..],
        None => url,
    };
    let end = rest
        .find(['/', '?', '#'])
        .unwrap_or(rest.len());
    let authority = &rest[..end];
    let authority = match authority.rfind('@') {
        Some(i) => &authority[i + 1..],
        None => authority,
    };
    // IPv6 köşeli parantez içinde; iki nokta üst üste kapatma sonrası aranır.
    if let Some(stripped) = authority.strip_prefix('[') {
        return match stripped.find(']') {
            Some(i) => &stripped[..i],
            None => stripped,
        };
    }
    match authority.find(':') {
        Some(i) => &authority[..i],
        None => authority,
    }
}

fn main() {
    let dir = std::env::args().nth(1).expect("kullanım: cost <filtre-dizini>");

    let mut set = FilterSet::new(true);
    let mut total_bytes = 0usize;
    let mut entries: Vec<_> = std::fs::read_dir(&dir)
        .expect("dizin okunamadı")
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map(|x| x == "txt").unwrap_or(false))
        .collect();
    entries.sort();
    for path in &entries {
        let text = std::fs::read_to_string(path).expect("liste okunamadı");
        total_bytes += text.len();
        set.add_filter_list(&text, ParseOptions::default());
    }
    println!("liste sayısı = {}, toplam metin = {:.1} MB", entries.len(), total_bytes as f64 / 1e6);

    let t = Instant::now();
    let engine = Engine::from_filter_set(set, true);
    println!("motor derleme = {:?}\n", t.elapsed());

    // Gerçek bir sayfada görülen istek karışımını temsil eden örnekler.
    let source = "https://www.hurriyet.com.tr/gundem/haber-detay-12345678";
    let src_host = hostname_of(source);
    let samples: [(&str, &str); 8] = [
        ("https://www.hurriyet.com.tr/static/css/main.8f2a1c.css", "stylesheet"),
        ("https://www.hurriyet.com.tr/img/2026/08/12/foto-1200x800.jpg", "image"),
        ("https://cdn.hurriyet.com.tr/bundle/vendor.4b91.js", "script"),
        ("https://www.google-analytics.com/analytics.js", "script"),
        ("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "script"),
        ("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "script"),
        ("https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92Fr1Me5Q.woff2", "font"),
        ("https://www.facebook.com/tr/?id=123&ev=PageView", "image"),
    ];

    // --- 1. Request::new: iki URL ayrıştırma + iki genel sonek araması ---
    let t = Instant::now();
    let mut sink = 0usize;
    for i in 0..ITERS {
        let (url, ty) = samples[(i as usize) % samples.len()];
        if let Ok(r) = Request::new(url, source, ty) {
            sink += r.hostname.len();
        }
    }
    let new_ns = t.elapsed().as_nanos() as f64 / ITERS as f64;

    // --- 2. Request::preparsed: ayrıştırma yok, genel sonek araması yok ---
    // `third_party` sayfa başına bir kez hesaplanabildiği için burada da
    // döngü dışında duruyor; gerçek kullanımda da öyle olacak.
    let t = Instant::now();
    for i in 0..ITERS {
        let (url, ty) = samples[(i as usize) % samples.len()];
        let host = hostname_of(url);
        let third_party = host != src_host;
        let r = Request::preparsed(url, host, src_host, ty, third_party);
        sink += r.hostname.len();
    }
    let pre_ns = t.elapsed().as_nanos() as f64 / ITERS as f64;

    // --- 3. Asıl eşleştirme ---
    let reqs: Vec<Request> = samples
        .iter()
        .map(|(url, ty)| Request::new(url, source, ty).unwrap())
        .collect();
    let t = Instant::now();
    for i in 0..ITERS {
        let r = &reqs[(i as usize) % reqs.len()];
        if engine.check_network_request(r).matched {
            sink += 1;
        }
    }
    let match_ns = t.elapsed().as_nanos() as f64 / ITERS as f64;

    println!("istek başına (masaüstü CPU, tek çekirdek):");
    println!("  Request::new        = {:8.0} ns", new_ns);
    println!("  Request::preparsed  = {:8.0} ns", pre_ns);
    println!("  check_network_req.  = {:8.0} ns", match_ns);
    println!(
        "  --> kurulum, eşleştirmenin {:.1} katı; preparsed ile {:.1} katı",
        new_ns / match_ns,
        pre_ns / match_ns
    );
    println!(
        "  --> istek başına kazanç = {:.0} ns (%{:.0})",
        new_ns - pre_ns,
        100.0 * (new_ns - pre_ns) / (new_ns + match_ns)
    );

    // --- 4. Kozmetik CSS boyutu ---
    println!("\nsayfa başına kozmetik yük:");
    for host in [
        "https://www.hurriyet.com.tr/",
        "https://www.youtube.com/",
        "https://www.reddit.com/",
        "https://www.milliyet.com.tr/",
        "https://www.amazon.com/",
        "https://tr.wikipedia.org/",
    ] {
        let r = engine.url_cosmetic_resources(host);
        let css: String = r
            .hide_selectors
            .iter()
            .map(|s| s.as_str())
            .collect::<Vec<_>>()
            .join(",");
        // JSON kaçışlaması sonrası `evaluateJavascript`'e giden gerçek boyut.
        let quoted = serde_json::to_string(&css).unwrap();
        println!(
            "  {:<32} seçici={:5}  CSS={:7.1} KB  JS'e giden={:7.1} KB  prosedürel={}",
            host.trim_start_matches("https://").trim_end_matches('/'),
            r.hide_selectors.len(),
            css.len() as f64 / 1024.0,
            quoted.len() as f64 / 1024.0,
            r.procedural_actions.len(),
        );
    }

    if sink == usize::MAX {
        println!("{sink}");
    }
}
