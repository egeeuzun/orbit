//! Geliştirme yardımcısı: motorun kozmetik/ağ çıktısını ve Brave kaynak
//! paketinin (`resources.json`) scriptlet enjeksiyonunu doğrular.
//! `cargo run --example dump -- <resources.json>` ile çalıştırılır.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;

fn main() {
    let rules = "||ads.example.com^$third-party\n\
                 ||tracker.net^\n\
                 /banner/*.gif\n\
                 example.com##.banner-ad\n\
                 example.com##div:has-text(Reklam)\n\
                 example.com##.wrap:upward(2)\n\
                 example.com##.sticky:style(position:static !important)\n\
                 example.com##+js(set-constant, adsShown, true)\n\
                 ##.generic-ad\n\
                 ###generic-id\n\
                 example.com#@#.banner-ad\n";

    let mut set = FilterSet::new(true);
    set.add_filter_list(rules, ParseOptions::default());
    let mut engine = Engine::from_filter_set(set, true);

    if let Some(path) = std::env::args().nth(1) {
        let text = std::fs::read_to_string(&path).expect("resources.json okunamadı");
        let resources: Vec<Resource> = serde_json::from_str(&text).expect("JSON şeması uyuşmuyor");
        println!("kaynak sayısı  = {}", resources.len());
        engine.use_resources(resources);
    }

    let res = engine.url_cosmetic_resources("https://example.com/page");
    println!("hide_selectors = {:?}", res.hide_selectors);
    println!(
        "procedural     = {}",
        serde_json::to_string(&res.procedural_actions).unwrap()
    );
    println!(
        "injected_script= {} bayt | {}",
        res.injected_script.len(),
        res.injected_script.chars().take(90).collect::<String>()
    );

    let generic = engine.hidden_class_id_selectors(
        &["generic-ad".to_string()],
        &["generic-id".to_string()],
        &Default::default(),
    );
    println!("class/id       = {:?}\n", generic);

    for (url, src, ty) in [
        ("https://ads.example.com/a.js", "https://publisher.org/", "script"),
        ("https://ads.example.com/a.js", "https://ads.example.com/", "script"),
        ("https://tracker.net/px.gif", "https://publisher.org/", "image"),
        ("https://cdn.example.com/banner/top.gif", "https://example.com/", "image"),
        ("https://example.com/app.js", "https://example.com/", "script"),
    ] {
        let req = Request::new(url, src, ty).unwrap();
        let r = engine.check_network_request(&req);
        println!("{:<45} matched={}", url, r.matched);
    }
}
