//! Gerçek filtre listeleriyle motorun derlenmesi, serileştirilmesi ve geri
//! yüklenmesi. `cargo run --release --example roundtrip -- <liste_dizini>`

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::time::Instant;

fn main() {
    let dir = std::env::args().nth(1).expect("liste dizini gerekli");
    let mut text = String::new();
    let mut files = 0;
    for entry in std::fs::read_dir(&dir).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().map(|e| e == "txt").unwrap_or(false) {
            text.push_str(&std::fs::read_to_string(&path).unwrap());
            text.push('\n');
            files += 1;
        }
    }
    println!("{} dosya, {} bayt kural metni", files, text.len());

    let t = Instant::now();
    let mut set = FilterSet::new(false);
    set.add_filter_list(&text, ParseOptions::default());
    let engine = Engine::from_filter_set(set, true);
    println!("derleme: {:?}", t.elapsed());

    let t = Instant::now();
    match engine.serialize() {
        Ok(bytes) => {
            println!("serialize: {:?}, {} bayt", t.elapsed(), bytes.len());
            let t = Instant::now();
            let mut back = Engine::new(true);
            match back.deserialize(&bytes) {
                Ok(_) => {
                    println!("deserialize: {:?}", t.elapsed());
                    check(&back, "geri yüklenen");
                }
                Err(e) => println!("DESERIALIZE HATASI: {:?}", e),
            }
        }
        Err(e) => println!("SERIALIZE HATASI: {:?}", e),
    }

    check(&engine, "taze");
}

fn check(engine: &Engine, label: &str) {
    let cases = [
        ("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "script", true),
        ("https://www.google-analytics.com/analytics.js", "script", true),
        ("https://sb.scorecardresearch.com/beacon.js", "script", true),
        ("https://cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js", "script", false),
    ];
    print!("[{}] ", label);
    for (url, ty, expect) in cases {
        let req = Request::new(url, "https://haber.example.com/", ty).unwrap();
        let got = engine.check_network_request(&req).matched;
        print!("{}{} ", if got == expect { "OK " } else { "HATA " }, short(url));
    }
    let cos = engine.url_cosmetic_resources("https://haber.example.com/");
    let generic = engine.hidden_class_id_selectors(
        &["adsbygoogle".to_string(), "addthis_toolbox".to_string(),
          "playerTextReklam".to_string(), "ads_single_font".to_string()],
        &["div-gpt-ad-slot1".to_string()],
        &Default::default(),
    );
    let mut sample: Vec<&String> = cos.hide_selectors.iter().take(3).collect();
    sample.sort();
    println!("\n         hide={} ornek={:?}", cos.hide_selectors.len(), sample);
    println!("         genel_sinif_sorgusu={:?}", generic);
}

fn short(url: &str) -> &str {
    url.split('/').nth(2).unwrap_or(url)
}
