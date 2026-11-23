//! Eşlenmiş önbellek (`Engine::deserialize_external`) doğrulaması.
//!
//! `cargo run --release --example mmapcheck -- <filtre-dizini>`
//!
//! 1. Listelerden motor derlenir, `engine.bin` biçiminde (3 bayt dolgu +
//!    DAT) geçici dosyaya yazılır.
//! 2. Dosya hem kopyalanarak (`deserialize`) hem de eşlenerek
//!    (`deserialize_external`) yüklenir.
//! 3. Binlerce ağ isteği ve kozmetik sorguda iki motorun sonucu birebir
//!    karşılaştırılır; eşlemenin yerinde kullanılıp kullanılmadığı ve
//!    eşlemenin yerleşik (RSS) boyutu raporlanır.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;

const PAD: [u8; 3] = [0; 3];

struct Mapped(memmap2::Mmap);
impl AsRef<[u8]> for Mapped {
    fn as_ref(&self) -> &[u8] {
        &self.0[PAD.len()..]
    }
}

extern "C" {
    fn madvise(addr: *mut std::ffi::c_void, len: usize, advice: i32) -> i32;
}

/// `/proc/self/smaps` içinde verilen dosyanın eşlemesinin yerleşik boyutu (KB).
fn mapping_rss_kb(path: &str) -> u64 {
    let smaps = std::fs::read_to_string("/proc/self/smaps").unwrap_or_default();
    let mut inside = false;
    let mut total = 0;
    for line in smaps.lines() {
        if line.contains('-') && line.split_whitespace().count() >= 5 && !line.starts_with(' ') {
            inside = line.ends_with(path);
        } else if inside && line.starts_with("Rss:") {
            total += line.split_whitespace().nth(1).and_then(|v| v.parse::<u64>().ok()).unwrap_or(0);
        }
    }
    total
}

fn main() {
    let dir = std::env::args().nth(1).expect("kullanım: mmapcheck <filtre-dizini>");
    let mut set = FilterSet::new(false);
    let mut hosts: Vec<String> = Vec::new();
    let mut paths: Vec<_> = std::fs::read_dir(&dir)
        .unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map(|x| x == "txt").unwrap_or(false))
        .collect();
    paths.sort();
    for p in &paths {
        let text = std::fs::read_to_string(p).unwrap();
        for line in text.lines() {
            if let Some(h) = line.strip_prefix("||").and_then(|l| l.strip_suffix('^')) {
                if hosts.len() < 4000 && h.contains('.') && !h.contains('*') {
                    hosts.push(h.to_string());
                }
            }
        }
        set.add_filter_list(text, ParseOptions::default());
    }
    let built = Engine::new_with_filter_set(set);
    let dat = built.serialize();
    drop(built);

    let path = std::env::temp_dir().join("orbit_mmapcheck_engine.bin");
    let path_s = path.to_string_lossy().to_string();
    {
        use std::io::Write;
        let mut f = std::fs::File::create(&path).unwrap();
        f.write_all(&PAD).unwrap();
        f.write_all(&dat).unwrap();
    }

    let mut owned = Engine::default();
    owned.deserialize(&dat).expect("kopyalı yükleme");
    drop(dat);

    let file = std::fs::File::open(&path).unwrap();
    let map = unsafe { memmap2::Mmap::map(&file) }.unwrap();
    let (addr, len) = (map.as_ptr() as *mut std::ffi::c_void, map.len());
    let mut mapped = Engine::default();
    let in_place = mapped
        .deserialize_external(Box::new(Mapped(map)))
        .expect("eşlenmiş yükleme");
    println!("yerinde kullanıldı: {in_place}, dosya {} KB", len / 1024);
    println!("yükleme sonrası eşleme RSS: {} KB", mapping_rss_kb(&path_s));
    unsafe {
        madvise(addr, len, 4);
    }
    println!("MADV_DONTNEED sonrası eşleme RSS: {} KB", mapping_rss_kb(&path_s));

    // Gerçekçi bir karışım: reklam alan adları (engellenmeli) ve sıradan
    // birinci taraf kaynakları (geçmeli), birkaç farklı sayfadan.
    let sources = [
        "https://www.hurriyet.com.tr/gundem/haber-1",
        "https://edition.cnn.com/world",
        "https://www.sozcu.com.tr/",
        "https://en.wikipedia.org/wiki/Android",
    ];
    let mut checked = 0;
    let mut blocked = 0;
    let mut mismatches = 0;
    for (i, h) in hosts.iter().enumerate() {
        let src = sources[i % sources.len()];
        for (url, ty) in [
            (format!("https://{h}/ads/tag.js"), "script"),
            (format!("https://{h}/pixel.gif?x={i}"), "image"),
            (format!("{src}/static/app{i}.js"), "script"),
        ] {
            let Ok(r) = Request::new(&url, src, ty, "get") else { continue };
            let a = owned.check_network_request(&r);
            let b = mapped.check_network_request(&r);
            checked += 1;
            if a.filter.is_some() && a.exception.is_none() {
                blocked += 1;
            }
            // Bütün alanlar (filtre, istisna, important, redirect, yeniden yazım).
            if format!("{a:?}") != format!("{b:?}") {
                mismatches += 1;
            }
        }
    }
    let mut cos_mismatch = 0;
    for src in sources {
        let a = owned.url_cosmetic_resources(src);
        let b = mapped.url_cosmetic_resources(src);
        let mut ha: Vec<_> = a.hide_selectors.into_iter().collect();
        let mut hb: Vec<_> = b.hide_selectors.into_iter().collect();
        ha.sort();
        hb.sort();
        if ha != hb || a.injected_script != b.injected_script || a.generichide != b.generichide {
            cos_mismatch += 1;
        }
    }
    println!(
        "ağ: {checked} istek, {blocked} engellendi, {mismatches} uyuşmazlık; kozmetik: {} sayfa, {cos_mismatch} uyuşmazlık",
        sources.len()
    );
    println!(
        "sorgular sonrası eşleme RSS (çalışma kümesi): {} KB",
        mapping_rss_kb(&path_s)
    );
    let _ = std::fs::remove_file(&path);
    if mismatches > 0 || cos_mismatch > 0 || !in_place {
        std::process::exit(1);
    }
}
