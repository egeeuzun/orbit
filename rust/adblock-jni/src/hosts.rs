//! Saf ana bilgisayar kurallarının sıkıştırılmış özeti.
//!
//! Orbit'in listelerindeki ağ kurallarının yaklaşık dörtte üçü
//! `||reklam.example.com^` biçiminde — seçenek yok, `$domain=` yok, yol
//! deseni yok. Bunlar tam motora hiç gerek olmadan tek bir arama ile
//! yanıtlanabilir.
//!
//! Via'nın yaptığı da tam olarak bu (`assets/simple.txt` + `HashSet`), ama
//! orada liste 3.000 kuralla sınırlı. Burada aynı yapı Orbit'in kendi
//! listelerinden üretiliyor: ~90.000 ana bilgisayar, 64 bitlik özet olarak
//! sıralı bir dizide, yani ~720 KB. Diskten okunması ve kullanıma hazır hale
//! gelmesi tam motorun onda biri kadar bile sürmüyor; bu yüzden soğuk
//! açılışta engelleme, tam motor kurulmayı beklerken de çalışır.
//!
//! Kotlin tarafı bu diziyi olduğu gibi `LongArray` olarak tutar ve ikili
//! arama yapar — istek başına JNI sınırı geçilmez.

/// FNV-1a 64. Kotlin tarafındaki `HostBlocklist.hash` ile birebir aynı
/// olmalı; küçük harfe çevrilmiş ASCII ana bilgisayar adı üzerinde çalışır.
pub fn hash(host: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in host.as_bytes() {
        let b = if b.is_ascii_uppercase() { b + 32 } else { *b };
        h ^= b as u64;
        h = h.wrapping_mul(0x1000_0000_01b3);
    }
    h
}

/// Bir satır `||host^` biçiminde saf bir ana bilgisayar kuralıysa ana
/// bilgisayar adını döndürür.
///
/// Kasıtlı olarak dar: `$` seçeneği, joker, yol, şema ya da alt çizgi gibi
/// ana bilgisayar adında yeri olmayan bir karakter varsa `None` döner ve
/// kural tam motora bırakılır.
fn pure_host(line: &str) -> Option<&str> {
    let s = line.trim();
    let s = s.strip_prefix("||")?;
    let s = s.strip_suffix('^')?;
    if s.len() < 3 || s.contains('$') || s.contains('*') || s.contains('/') || s.contains('^') {
        return None;
    }
    // En az bir nokta olmalı ve etiketler boş olmamalı.
    let mut has_dot = false;
    let mut prev_dot = true; // baştaki nokta geçersiz
    for c in s.chars() {
        match c {
            '.' => {
                if prev_dot {
                    return None;
                }
                has_dot = true;
                prev_dot = true;
            }
            'a'..='z' | 'A'..='Z' | '0'..='9' | '-' => prev_dot = false,
            _ => return None,
        }
    }
    if !has_dot || prev_dot {
        return None;
    }
    Some(s)
}

/// Satır `$badfilter` seçeneği taşıyorsa iptal ettiği kuralın desen kısmını
/// döndürür (`||x.com^$badfilter` -> `||x.com^`).
fn badfilter_base(line: &str) -> Option<&str> {
    let s = line.trim();
    let at = s.rfind('$')?;
    let opts = &s[at + 1..];
    if !opts.split(',').any(|o| o.trim() == "badfilter") {
        return None;
    }
    Some(&s[..at])
}

/// Bir istisna satırının (`@@...`) andığı ana bilgisayar adı parçasını
/// kabaca çıkarır. Amaç kesinlik değil, *şüpheli* ana bilgisayarları hızlı
/// yoldan tamamen dışlamak.
fn exception_mentions(line: &str, out: &mut Vec<String>) {
    let s = line.trim();
    let Some(s) = s.strip_prefix("@@") else {
        return;
    };
    // `$domain=a.com|b.com` içindeki adlar da anılmış sayılır.
    for part in s.split('$') {
        for token in part.split(['|', ',', '=', '/', '^', '*', '&', '?']) {
            let t = token.trim_start_matches("domain=").trim();
            if t.len() >= 3
                && t.contains('.')
                && t.chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '~')
            {
                out.push(t.trim_start_matches('~').to_ascii_lowercase());
            }
        }
    }
}

/// Filtre metninden hızlı yol tablosunu üretir.
///
/// Dönen dizi sıralı ve tekilleştirilmiş 64 bitlik özetlerden oluşur.
/// İstisna kurallarında adı geçen her ana bilgisayar — ve o adın altındaki
/// her alt alan adı — dışarıda bırakılır; böylece hızlı yol bir `@@`
/// kuralını asla ezmez.
pub fn build(rules: &str) -> Vec<u64> {
    let mut blocked: Vec<&str> = Vec::new();
    let mut excepted: Vec<String> = Vec::new();

    for line in rules.lines() {
        let t = line.as_bytes();
        if t.is_empty() {
            continue;
        }
        match t[0] {
            b'!' | b'[' | b'#' => continue,
            b'@' => exception_mentions(line, &mut excepted),
            _ => {
                // Kozmetik kurallar ağ kuralı değildir.
                if line.contains('#') {
                    continue;
                }
                // `$badfilter`, başka bir listedeki aynı kuralı iptal eder.
                // İptal edilen kuralın ana bilgisayarı hızlı yola giremez;
                // aksi halde motorun izin verdiği isteği engellerdik.
                if let Some(base) = badfilter_base(line) {
                    if let Some(h) = pure_host(base) {
                        excepted.push(h.to_ascii_lowercase());
                    }
                    continue;
                }
                if let Some(h) = pure_host(line) {
                    blocked.push(h);
                }
            }
        }
    }

    excepted.sort();
    excepted.dedup();

    let is_excepted = |host: &str| -> bool {
        // Adın kendisi ya da üst alan adlarından biri anılmışsa dışla.
        let lower = host.to_ascii_lowercase();
        let mut rest: &str = &lower;
        loop {
            if excepted.binary_search(&rest.to_string()).is_ok() {
                return true;
            }
            match rest.find('.') {
                Some(i) if rest[i + 1..].contains('.') => rest = &rest[i + 1..],
                _ => return false,
            }
        }
    };

    let mut out: Vec<u64> = blocked
        .into_iter()
        .filter(|h| !is_excepted(h))
        .map(hash)
        .collect();
    out.sort_unstable();
    out.dedup();
    out
}

/// Hızlı yolun kararı: ana bilgisayar ya da üst alan adlarından biri
/// tabloda mı?
///
/// Yalnızca doğrulama ve sınama içindir; üretimde aynı arama Kotlin
/// tarafında yapılır.
pub fn contains(table: &[u64], host: &str) -> bool {
    let lower = host.to_ascii_lowercase();
    let mut rest: &str = &lower;
    loop {
        if table.binary_search(&hash(rest)).is_ok() {
            return true;
        }
        match rest.find('.') {
            Some(i) if rest[i + 1..].contains('.') => rest = &rest[i + 1..],
            _ => return false,
        }
    }
}

/// Tabloyu Kotlin'in doğrudan `LittleEndian` okuyabileceği bayt dizisine
/// çevirir.
pub fn to_bytes(table: &[u64]) -> Vec<u8> {
    let mut out = Vec::with_capacity(table.len() * 8);
    for v in table {
        out.extend_from_slice(&v.to_le_bytes());
    }
    out
}
