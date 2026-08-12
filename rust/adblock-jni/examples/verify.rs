//! Geliştirme yardımcısı: kıyas sayfasındaki reklam yollarının gerçekten
//! engellendiğini ve masum kaynakların engellenmediğini doğrular.
//! `cargo run --release --example verify -- <filtre-dizini>`

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;

const SOURCE: &str = "http://127.0.0.1:8099/test.html";

const ADS: [&str; 20] = [
    "/ads/banner1.gif", "/ads/banner2.gif", "/adserver/pixel.gif",
    "/advert/top.gif", "/banners/side.gif", "/pagead/show_ads.js",
    "/adimages/hero.gif", "/images/ads/promo.gif", "/adframe.js",
    "/ad/third-party/tag.js", "/adsense/pixel.gif", "/popupads.js",
    "/ads/track.gif", "/adclick.gif", "/ad-banner.gif",
    "/advertisement.gif", "/ads/iframe.html", "/adtech/probe.gif",
    "/ad_track.js", "/banner-ads/wide.gif",
];

fn kind(path: &str) -> &'static str {
    if path.ends_with(".js") {
        "script"
    } else if path.ends_with(".html") {
        "sub_frame"
    } else {
        "image"
    }
}

fn main() {
    let dir = std::env::args().nth(1).expect("kullanım: verify <filtre-dizini>");
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
    let engine = Engine::from_filter_set(set, true);

    let mut blocked = 0;
    println!("reklam kaynakları:");
    for p in ADS {
        let url = format!("http://127.0.0.1:8099{p}");
        let r = Request::new(&url, SOURCE, kind(p)).unwrap();
        let m = engine.check_network_request(&r).matched;
        if m {
            blocked += 1;
        }
        println!("  {:<26} {}", p, if m { "ENGELLENDİ" } else { "geçti" });
    }
    println!("--> {blocked}/20 engellendi");

    let mut leaked = 0;
    println!("\nmasum kaynaklar (engellenmemeli):");
    for p in ["/s/style0.css", "/j/app0.js", "/i/img0_0.gif", "/i/img39_14.gif"] {
        let url = format!("http://127.0.0.1:8099{p}");
        let r = Request::new(&url, SOURCE, kind(p)).unwrap();
        let m = engine.check_network_request(&r).matched;
        if m {
            leaked += 1;
        }
        println!("  {:<26} {}", p, if m { "YANLIŞ ENGEL" } else { "ok" });
    }
    println!("--> {leaked} yanlış engel");
}
