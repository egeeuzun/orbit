//! Hızlı yol doğrulaması: `hosts` tablosunun "engelle" dediği her istek
//! için tam motor da "engelle" diyor mu?
//!
//! Hızlı yol yalnızca *olumlu* karar verir; bu yüzden tek tehlike yanlış
//! engellemedir (tam motorun izin vereceği bir isteği engellemek). Bu örnek
//! tablodaki her ana bilgisayarı gerçek bir istek gibi motora sorar ve
//! uyuşmazlıkları sayar.
//!
//! `cargo run --release --example hostcheck -- <filtre-dizini>`

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use adblock_jni::hosts;

/// Hızlı yol sayfa bağlamına bakmadığı için en zorlu durum, isteğin
/// birinci taraf olduğu ve bu yüzden `$third-party` kurallarının
/// düşeceği durumdur. Kaynak olarak ana bilgisayarın kendisi kullanılır.
fn main() {
    let dir = std::env::args().nth(1).expect("kullanım: hostcheck <filtre-dizini>");

    let mut text = String::new();
    let mut paths: Vec<_> = std::fs::read_dir(&dir)
        .expect("dizin okunamadı")
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map(|x| x == "txt").unwrap_or(false))
        .collect();
    paths.sort();
    for p in &paths {
        text.push_str(&std::fs::read_to_string(p).unwrap());
        text.push('\n');
    }

    let t0 = std::time::Instant::now();
    let table = hosts::build(&text);
    println!(
        "tablo: {} ana bilgisayar, {:.0} KB, üretim {:?}",
        table.len(),
        (table.len() * 8) as f64 / 1024.0,
        t0.elapsed()
    );

    // Tabloyu üretirken kullanılan adları yeniden çıkar (özet geri
    // çevrilemediği için metinden tekrar okunur).
    let mut names: Vec<&str> = Vec::new();
    for line in text.lines() {
        let s = line.trim();
        if let Some(rest) = s.strip_prefix("||") {
            if let Some(h) = rest.strip_suffix('^') {
                if !h.is_empty() && h.contains('.') && !s.contains('$') && !s.contains('#') {
                    names.push(h);
                }
            }
        }
    }
    names.sort_unstable();
    names.dedup();

    let mut set = FilterSet::new(true);
    set.add_filter_list(&text, ParseOptions::default());
    let engine = Engine::from_filter_set(set, true);

    let types = ["script", "image", "xmlhttprequest", "sub_frame"];
    let mut checked = 0usize;
    let mut fastpath = 0usize;
    let mut disagree = 0usize;
    let mut examples: Vec<String> = Vec::new();

    for name in &names {
        if !hosts::contains(&table, name) {
            continue;
        }
        fastpath += 1;
        for ty in types {
            // Birinci taraf: sayfa da aynı ana bilgisayarda. `$third-party`
            // kuralları burada düşer, yani motorun en hoşgörülü hali.
            let url = format!("https://{name}/a");
            let source = format!("https://{name}/");
            let Ok(req) = Request::new(&url, &source, ty) else {
                continue;
            };
            checked += 1;
            let r = engine.check_network_request(&req);
            let engine_blocks = r.matched && r.exception.is_none();
            if !engine_blocks {
                disagree += 1;
                if examples.len() < 15 {
                    examples.push(format!("{name} [{ty}]"));
                }
            }
        }
    }

    println!(
        "hızlı yolun engellediği ana bilgisayar = {fastpath}, sınanan istek = {checked}"
    );
    println!("uyuşmazlık (motor izin verirdi) = {disagree}");
    if !examples.is_empty() {
        println!("örnekler:");
        for e in &examples {
            println!("  {e}");
        }
    }
    let pct = if checked == 0 {
        0.0
    } else {
        100.0 * disagree as f64 / checked as f64
    };
    println!("uyuşmazlık oranı = %{pct:.4}");
}
