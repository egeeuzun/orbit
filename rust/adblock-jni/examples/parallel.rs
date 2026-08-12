//! Geliştirme yardımcısı: `shouldInterceptRequest` birden çok iş
//! parçacığından çağrılıyor. `unsync-regex-caching` kapalıyken motorun
//! içindeki `regex_manager` bir `Mutex`'e dönüşüyor ve her `check` çağrısı
//! onu alıyor. Bu, eşzamanlı sorguları sıraya sokuyor mu?
//!
//! `cargo run --release --example parallel -- <filtre-dizini>`

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::sync::Arc;
use std::time::Instant;

const PER_THREAD: u32 = 20_000;
const SOURCE: &str = "https://www.hurriyet.com.tr/gundem/haber-detay-12345678";

fn samples() -> Vec<Request> {
    [
        ("https://www.hurriyet.com.tr/static/css/main.8f2a1c.css", "stylesheet"),
        ("https://www.hurriyet.com.tr/img/2026/08/12/foto.jpg", "image"),
        ("https://cdn.hurriyet.com.tr/bundle/vendor.4b91.js", "script"),
        ("https://www.google-analytics.com/analytics.js", "script"),
        ("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "script"),
        ("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "script"),
        ("https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92.woff2", "font"),
        ("https://www.facebook.com/tr/?id=123&ev=PageView", "image"),
    ]
    .iter()
    .map(|(u, t)| Request::new(u, SOURCE, t).unwrap())
    .collect()
}

fn main() {
    let dir = std::env::args().nth(1).expect("kullanım: parallel <filtre-dizini>");
    let mut set = FilterSet::new(true);
    let mut paths: Vec<_> = std::fs::read_dir(&dir)
        .expect("dizin okunamadı")
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map(|x| x == "txt").unwrap_or(false))
        .collect();
    paths.sort();
    for p in &paths {
        set.add_filter_list(&std::fs::read_to_string(p).unwrap(), ParseOptions::default());
    }
    let engine = Arc::new(Engine::from_filter_set(set, true));

    for threads in [1usize, 2, 4] {
        let t = Instant::now();
        let mut handles = Vec::new();
        for _ in 0..threads {
            let engine = Arc::clone(&engine);
            handles.push(std::thread::spawn(move || {
                let reqs = samples();
                let mut hits = 0u32;
                for i in 0..PER_THREAD {
                    if engine
                        .check_network_request(&reqs[i as usize % reqs.len()])
                        .matched
                    {
                        hits += 1;
                    }
                }
                hits
            }));
        }
        let mut hits = 0u32;
        for h in handles {
            hits += h.join().unwrap();
        }
        let el = t.elapsed();
        let total = PER_THREAD as u64 * threads as u64;
        println!(
            "{} iş parçacığı: {:?} toplam, istek başına {:6.0} ns, iş hacmi {:8.0} istek/sn  (eşleşme {})",
            threads,
            el,
            el.as_nanos() as f64 / total as f64,
            total as f64 / el.as_secs_f64(),
            hits,
        );
    }
}
