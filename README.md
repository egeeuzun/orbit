# 🚀 Orbit Browser

**A high-performance, ultra-lightweight Android web browser built on Android System WebView, featuring an embedded Brave `adblock-rust` C++/Rust engine, uBlock Origin filter lists, multi-language page translation, and Material Design 3 UI.**

Designed to run smoothly on devices with **1 GB RAM** and fully compatible with **Android 5.0 (Lollipop - API 21)** and above.

---

## 📸 Screenshots

<div align="center">
  <img src="Screenshot_20260812-203516_Orbit.png" width="45%" alt="Brave-style Shield Card &amp; Ad Blocking" />
  <img src="Screenshot_20260812-205710_Orbit.png" width="45%" alt="Web Page Translation Service" />
</div>

---

## ✨ Features

### 🛡️ 1. Brave-Style Shield & Ad Blocking
- **Embedded C++/Rust JNI Engine:** Powered by Brave Software's `adblock-rust` crate executing directly at native speed.
- **Live Blocking Stats:** Tapping the Shield icon opens a bottom sheet showing blocked item counts for the current page and total blocked requests.
- **Per-Site Protection Toggle:** Easily toggle adblocking ON/OFF for any website with a single tap (per-site allowlist).

### 🌐 2. Multi-Language Web Page Translation
- **Flexible Source & Target Languages:** Tap "Translate Page" from the overflow menu to translate any webpage.
- **Auto-Detection & System Locale Matching:** Source language is auto-detected; target language defaults to system language (supports English, Turkish, German, French, Spanish, Russian, Arabic, Japanese, Chinese, Korean, Hindi, etc.) while keeping the webpage layout and links intact.

### 🔍 3. Dynamic Search Engine & Real-Time Suggestions
- Google is set as default (switchable to DuckDuckGo, Bing, Yandex, Brave, Startpage).
- Dynamic search bar hint automatically updates to reflect the chosen engine (*"Search with Google or type URL"*).
- Optional real-time OpenSearch autocompletion in Settings.

### 🌙 4. Full Material 3 Light/Dark Theme & Web Darkening
- Complete Material Design 3 Day/Night theme with synchronized status bar and navigation bar icon colors.
- **Independent Web Darkening:** Web pages are NOT forcibly darkened just because Dark Theme is on. Web darkening ("Force Dark Web") is an independent option in Settings > Appearance.

### 📌 5. Optional Tab Session Restore
- *"Restore open tabs"* setting under Settings > General automatically restores tabs and URLs when reopening the app (Disabled by default).

### 📜 6. Auto-Hiding Toolbars on Scroll
- Scrolling down automatically slides the top Omnibox and bottom navigation bar out of view for immersive full-screen reading. Scrolling up smoothly restores toolbars.

---

## ⚡ Memory & Performance Optimization

Orbit is meticulously engineered for minimal resource consumption and peak fluidity:

- **Ultra-Low Memory Footprint:** Consumes only **~78 MB RAM total** (~0.078 GB).
- **Single Live WebView Architecture:** Background tabs are serialized into memory states (`saveState()`) to free renderer memory, waking only when selected.
- **Compiled Engine Binary (`engine.bin`):** Pre-compiles 4 MB filter rule sets into a binary cache to eliminate cold-start parsing delays.
- **Overdraw & Pre-Warming:** Off-main-thread SQLite DB & WebView provider initialization.

---

## 📜 Licenses & Open Source

Orbit Browser is open-source software distributed under the terms of the **Mozilla Public License 2.0 (MPL 2.0)**.

### Open Source Libraries & Components:
- **Orbit Browser:** Mozilla Public License 2.0 (MPL 2.0)
- **Brave adblock-rust Engine:** Mozilla Public License 2.0 (MPL 2.0) — Native C++/Rust adblock JNI engine by Brave Software.
- **uBlock Origin Filter Lists:** GNU General Public License v3.0 (GPLv3) — Community filter lists by Raymond Hill (gorhill) & contributors.
- **AndroidX & Jetpack Libraries:** Apache License 2.0 — Core-KTX, AppCompat, RecyclerView, WebKit, Lifecycle.
- **Google Material Components:** Apache License 2.0 — Material Design 3 UI components & BottomSheet dialogs.
- **Kotlin Standard Library:** Apache License 2.0 — Language runtime & tools by JetBrains.

---

## 🛠 Building & Running

Clone the repository and build using Android Studio or terminal:

```bash
./gradlew assembleRelease
```

To re-compile the native `.so` adblock JNI engine from Rust sources (optional):

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
./gradlew buildNativeAdblock
```

---

## 📁 Project Structure

```
app/src/main/
  java/com/orbit/browser/
    adblock/     AdblockService (downloading, compilation, decision cache), JNI wrapper
    browser/     OrbitWebView (touch focus & scroll listener), WebView clients, tab management
    data/        Preferences (Prefs), Search Suggestions (SearchSuggestions), SQLite DB
    ui/          MainActivity, SettingsActivity, ShieldSheet, TranslateSheet, AboutActivity
  res/
    layout/      dialog_shield.xml, dialog_translate.xml, activity_main.xml, activity_about.xml
    values/      strings.xml (English default), themes.xml
    values-tr/   strings.xml (Turkish)
rust/adblock-jni/ JNI bridge for Brave adblock-rust engine
```
