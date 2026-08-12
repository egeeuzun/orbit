//! Brave'in `adblock` motoru için ince bir JNI köprüsü.
//!
//! Motorun kendisi uBlock Origin filtre sözdizimini (ağ kuralları, kozmetik
//! filtreler, scriptlet ve redirect kaynakları) uygular. Buradaki kod yalnızca
//! Kotlin tarafına güvenli bir yüzey açar ve JNI sınır geçişlerini en aza
//! indirmek için CSS'i Rust tarafında birleştirir.

pub mod hosts;

use std::collections::HashSet;
use std::sync::Arc;

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

/// `check_network_request` sonucunun bit maskesi (Kotlin tarafıyla ortak).
const R_MATCHED: jint = 1;
const R_EXCEPTION: jint = 1 << 1;
const R_IMPORTANT: jint = 1 << 2;
const R_REDIRECT: jint = 1 << 3;

/// Tek bir CSS bloğuna sığdırılacak azami seçici sayısı. Uzun seçici
/// listeleri zayıf cihazlarda stil çözümlemesini yavaşlattığı için bölünür.
const CSS_CHUNK: usize = 250;

struct Handle {
    engine: Arc<Engine>,
}

/// Motor birden çok Java iş parçacığından aynı anda sorgulanıyor. Bu bağ,
/// `unsync-regex-caching` özelliği yanlışlıkla açılırsa derlemeyi durdurur.
const _: fn() = || {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<Engine>();
};

#[inline]
fn as_handle<'a>(ptr: jlong) -> Option<&'a Handle> {
    if ptr == 0 {
        None
    } else {
        unsafe { Some(&*(ptr as *const Handle)) }
    }
}

fn into_ptr(h: Handle) -> jlong {
    Box::into_raw(Box::new(h)) as jlong
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

/// Seçicileri `a,b,c{display:none!important}` bloklarına böler.
fn selectors_to_css(selectors: &HashSet<String>) -> String {
    if selectors.is_empty() {
        return String::new();
    }
    let mut out = String::with_capacity(selectors.len() * 24);
    for chunk in selectors
        .iter()
        .collect::<Vec<_>>()
        .chunks(CSS_CHUNK)
    {
        let mut first = true;
        for s in chunk {
            if !first {
                out.push(',');
            }
            out.push_str(s);
            first = false;
        }
        out.push_str("{display:none!important}");
    }
    out
}

fn string_array<'l>(env: &mut JNIEnv<'l>, items: &[String]) -> jobjectArray {
    let class = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let empty = match env.new_string("") {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr: JObjectArray = match env.new_object_array(items.len() as i32, class, &empty) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, s) in items.iter().enumerate() {
        if let Ok(js) = env.new_string(s) {
            let _ = env.set_object_array_element(&arr, i as i32, js);
        }
    }
    arr.into_raw()
}

// ---------------------------------------------------------------- yaşam döngüsü

/// Filtre listesi metninden yeni bir motor derler.
#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeNew(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
    optimize: jboolean,
) -> jlong {
    let text = jstr(&mut env, &rules);
    let mut set = FilterSet::new(false);
    set.add_filter_list(&text, ParseOptions::default());
    let engine = Engine::from_filter_set(set, optimize == JNI_TRUE);
    into_ptr(Handle {
        engine: Arc::new(engine),
    })
}

/// Önceden derlenmiş (serileştirilmiş) motoru yükler. Soğuk açılışta
/// listeleri yeniden ayrıştırmaktan çok daha hızlıdır.
#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeFromCache(
    env: JNIEnv,
    _class: JClass,
    data: jbyteArray,
) -> jlong {
    let arr = unsafe { jni::objects::JByteArray::from_raw(data) };
    let bytes = match env.convert_byte_array(&arr) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let mut engine = Engine::new(true);
    match engine.deserialize(&bytes) {
        Ok(_) => into_ptr(Handle {
            engine: Arc::new(engine),
        }),
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeSerialize(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jbyteArray {
    let h = match as_handle(ptr) {
        Some(h) => h,
        None => return std::ptr::null_mut(),
    };
    match h.engine.serialize() {
        Ok(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(a) => a.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// Filtre metninden saf `||host^` kurallarının hızlı yol tablosunu üretir.
/// Ayrıntı için [`hosts`] modülüne bakın.
#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeHostsFromRules(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
) -> jbyteArray {
    let text = jstr(&mut env, &rules);
    let table = hosts::build(&text);
    match env.byte_array_from_slice(&hosts::to_bytes(&table)) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe {
            drop(Box::from_raw(ptr as *mut Handle));
        }
    }
}

// ------------------------------------------------------------------ ağ filtresi

/// `WebViewClient.shouldInterceptRequest` içinden çağrılır; sıcak yoldur.
#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeMatch(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jint {
    let h = match as_handle(ptr) {
        Some(h) => h,
        None => return 0,
    };
    let url = jstr(&mut env, &url);
    let source = jstr(&mut env, &source_url);
    let rtype = jstr(&mut env, &request_type);

    let req = match Request::new(&url, &source, &rtype) {
        Ok(r) => r,
        Err(_) => return 0,
    };
    let res = h.engine.check_network_request(&req);

    let mut flags = 0;
    if res.matched {
        flags |= R_MATCHED;
    }
    if res.exception.is_some() {
        flags |= R_EXCEPTION;
    }
    if res.important {
        flags |= R_IMPORTANT;
    }
    if res.redirect.is_some() {
        flags |= R_REDIRECT;
    }
    flags
}

// -------------------------------------------------------------- kozmetik filtre

/// Sayfa için: [0] hazır CSS, [1] enjekte edilecek scriptlet JS,
/// [2] prosedürel filtreler (JSON), [3] generichide ("1"/"0").
#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeCosmetic(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    url: JString,
) -> jobjectArray {
    let h = match as_handle(ptr) {
        Some(h) => h,
        None => return std::ptr::null_mut(),
    };
    let url = jstr(&mut env, &url);
    let res = h.engine.url_cosmetic_resources(&url);

    let css = selectors_to_css(&res.hide_selectors);
    let script = res.injected_script.clone();
    let procedural = serde_json::to_string(&res.procedural_actions).unwrap_or_else(|_| "[]".into());
    let generichide = if res.generichide { "1" } else { "0" };

    string_array(
        &mut env,
        &[css, script, procedural, generichide.to_string()],
    )
}

/// Sayfadaki gerçek `class`/`id` değerlerine göre genel kozmetik seçicileri
/// getirir. uBlock Origin'in yaptığı gibi; on binlerce seçiciyi baştan
/// enjekte etmek yerine yalnızca DOM'da karşılığı olanlar uygulanır —
/// düşük bellekli cihazlarda kritik fark budur.
#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeClassIdCss(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    classes: jobjectArray,
    ids: jobjectArray,
    exceptions: jobjectArray,
) -> jobjectArray {
    let h = match as_handle(ptr) {
        Some(h) => h,
        None => return std::ptr::null_mut(),
    };

    let classes = read_string_array(&mut env, classes);
    let ids = read_string_array(&mut env, ids);
    let exceptions: HashSet<String> = read_string_array(&mut env, exceptions).into_iter().collect();

    let selectors = h
        .engine
        .hidden_class_id_selectors(&classes, &ids, &exceptions);

    let set: HashSet<String> = selectors.into_iter().collect();
    string_array(&mut env, &[selectors_to_css(&set)])
}

fn read_string_array(env: &mut JNIEnv, arr: jobjectArray) -> Vec<String> {
    if arr.is_null() {
        return Vec::new();
    }
    let arr = unsafe { JObjectArray::from_raw(arr) };
    let len = env.get_array_length(&arr).unwrap_or(0);
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let obj = match env.get_object_array_element(&arr, i) {
            Ok(o) => o,
            Err(_) => continue,
        };
        let s = JString::from(obj);
        let owned: Option<String> = env.get_string(&s).ok().map(|v| v.into());
        if let Some(v) = owned {
            out.push(v);
        }
    }
    out
}

// ------------------------------------------------------------------- kaynaklar

/// Kaynakları motora yükler. Motor `Arc` arkasında paylaşıldığı için bu
/// yalnızca kurulum anında (henüz başka sorgu yokken) geçerlidir.
fn install_resources(ptr: jlong, resources: Vec<adblock::resources::Resource>) -> jboolean {
    let handle = match unsafe { (ptr as *mut Handle).as_mut() } {
        Some(h) => h,
        None => return JNI_FALSE,
    };
    match Arc::get_mut(&mut handle.engine) {
        Some(engine) => {
            engine.use_resources(resources);
            JNI_TRUE
        }
        None => JNI_FALSE,
    }
}

/// Hazır JSON kaynak paketi yükler.
#[no_mangle]
pub extern "system" fn Java_com_orbit_browser_adblock_NativeAdblock_nativeLoadResources(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    json: JString,
) -> jboolean {
    if ptr == 0 {
        return JNI_FALSE;
    }
    let text = jstr(&mut env, &json);
    match serde_json::from_str::<Vec<adblock::resources::Resource>>(&text) {
        Ok(resources) => install_resources(ptr, resources),
        Err(_) => JNI_FALSE,
    }
}
