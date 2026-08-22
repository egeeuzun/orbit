# 🚀 Orbit Browser

**A high-performance, ultra-lightweight Android web browser built on Android System WebView, featuring an embedded Brave `adblock-rust` C++/Rust engine, uBlock Origin filter lists, multi-language page translation, and Material Design 3 UI.**

Designed to run smoothly on devices with 1 GB RAM and fully compatible with newer Android versions.

---

## 🆕 What's new in 2.0.0

- **Webapp mode** — pin any site to your home screen and it opens as a fullscreen
  window with no address bar, in its own task; ad blocking and downloads work inside it.
- **Downloads** — handled through the system DownloadManager, with a progress
  notification and files landing in Downloads; APK links served without a
  `Content-Disposition` header now work.
- **Ad popup blocking** — `window.open` calls to domains the engine flags as popups
  no longer open a window, in both the browser and webapp mode.
- **Russian filter lists on by default** — AdGuard Russian and RU AdList, bringing the
  catalog to 14 lists; existing installs pick them up automatically.
- **Privacy** — history, bookmarks and open tabs no longer go into Google cloud backup
  or device transfer; the Private tab now states plainly that cookies and logins are
  shared with regular tabs.
- **Signing** — signed with a real RSA-4096 key instead of the Android debug key, with a
  `SigningCertificateLineage` so 2.0.0 installs over 1.228 without a reinstall.
- **Interface** — Jelly-style bottom bar (Home / Address / Reload / Tabs / Menu), a thin
  progress bar over the page, and edge-to-edge layout with proper insets for cutouts
  and the keyboard.

Full changelog, in Russian and English: [CHANGES.md](CHANGES.md) —
[release notes](https://github.com/egeeuzun/orbit/releases/tag/v2.0.0).

---

## ✨ Features

### 🛡️ 1. Ad Blocking
- **Embedded C++/Rust JNI Engine:** Powered by Brave Software's `adblock-rust` crate executing directly at native speed.
- **Live Blocking Stats:** Tapping the Shield icon opens a bottom sheet showing blocked item counts for the current page and total blocked requests.
- **Per-Site Protection Toggle:** Easily toggle adblocking ON/OFF for any website with a single tap (per-site allowlist).
- **Popup Blocking:** `window.open` calls to domains the engine flags as popups are consumed instead of opening a window, with a short toast in their place.

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

### 📱 7. Webapp Mode & Home-Screen Shortcuts
- Pin any site to the home screen through the system dialog (`requestPinShortcut`); the shortcut icon is the site favicon, cached per host.
- The site opens as a fullscreen window with no address bar or toolbars, in its own task, so it multitasks as a separate app. Re-tapping the shortcut returns to the same window.
- Ad blocking, popup blocking and downloads all work inside a webapp window.

### ⬇️ 8. Downloads
- Files go through the system DownloadManager into the Downloads folder, with a progress notification, in both the browser and webapp mode.
- Handles APK links served without a `Content-Disposition` header.

---

## ⚡ Memory & Performance Optimization

Orbit is meticulously engineered for minimal resource consumption and peak fluidity:

- **Ultra-Low Memory Footprint:** Consumes only **~78 MB RAM total** (~0.078 GB).
- **Single Live WebView Architecture:** Background tabs are serialized into memory states (`saveState()`) to free renderer memory, waking only when selected.
- **Compiled Engine Binary (`engine.bin`):** Pre-compiles 4 MB filter rule sets into a binary cache to eliminate cold-start parsing delays.
- **Overdraw & Pre-Warming:** Off-main-thread SQLite DB & WebView provider initialization.

---

## 🤝 Contributors

- [@timcho968](https://github.com/timcho968) — webapp mode, downloads, popup blocking, Russian
  filter lists, the Jelly-style interface, and the privacy and signing work that went into 2.0.0
  ([#2](https://github.com/egeeuzun/orbit/pull/2)).

Bug reports and pull requests are welcome.

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
    adblock/     AdblockService (downloading, compilation, decision cache), filter list
                 catalog, host blocklist, JNI wrapper (NativeAdblock)
    browser/     OrbitWebView (touch focus & scroll listener), WebView clients, tab
                 management, DownloadHelper, FaviconStore, cosmetic filtering bridge
    data/        Preferences (Prefs), Search Suggestions, SQLite DB, browsing data
    ui/          MainActivity, WebappActivity, SettingsActivity, ShieldSheet, MenuSheet,
                 TabsSheet, TranslateSheet, HomeView, ListActivity, AboutActivity
    util/        URL helpers
  res/
    layout/      activity_main.xml, activity_settings.xml, activity_list.xml,
                 dialog_shield.xml, dialog_translate.xml, view_home.xml
    values/      strings.xml (English default), themes.xml
    values-night/ dark theme overrides
    values-tr/   strings.xml (Turkish)
rust/adblock-jni/ JNI bridge for Brave adblock-rust engine
```
