//! Kaynak sayfa adresinin biçimi eşleşmeyi etkiliyor mu?
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;

fn main() {
    let mut set = FilterSet::new(false);
    set.add_filter_list("||google-analytics.com^\n||pagead2.googlesyndication.com^\n".to_string(), ParseOptions::default());
    let engine = Engine::new_with_filter_set(set);

    for src in [
        "https://haber.example.com/",
        "http://10.0.2.2:8099/test.html",
        "http://127.0.0.1:8099/test.html",
        "http://localhost:8099/test.html",
        "",
    ] {
        match Request::new("https://www.google-analytics.com/analytics.js", src, "script", "get") {
            Ok(r) => println!("{:<34} -> matched={}", format!("{:?}", src), engine.check_network_request(&r).filter.is_some()),
            Err(e) => println!("{:<34} -> Request::new HATASI {:?}", format!("{:?}", src), e),
        }
    }
}
