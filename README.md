# 🚀 Orbit Browser

**A high-performance, ultra-lightweight Android web browser built on Android System WebView, featuring an embedded Brave `adblock-rust` C++/Rust engine, uBlock Origin filter lists, multi-language page translation, and Material Design 3 UI.**

Designed to run smoothly on devices with 1 GB RAM and fully compatible with newer Android versions.

---

## ✍️ Changes in this fork

### Русский
- **Webapp-режим** по ярлыку: сайт открывается полноэкранным окном без
  адресной строки и панелей, в отдельной задаче; адблок и скачивания работают;
  повторный запуск переиспользует окно.
- **Ярлыки на рабочий стол** через системный диалог (`requestPinShortcut`) —
  launcher3 игнорирует dynamic-ярлыки; иконка = favicon сайта (кэш по хосту,
  192px), вместо него — иконка приложения.
- **Жест «назад» в webapp работает с первого раза**: скрыт только статусбар,
  навигационная полоса видна (SystemUI не «съедает» первый свайп).
- **Скачивания**: общий `DownloadHelper` (DownloadManager, папка Downloads,
  уведомление) — работает и в браузере, и в webapp; проверено на F-Droid.apk.
- **Живая статус-строка адблока**: слушатель движка — после запуска без
  действий показывает «Total N requests blocked» вместо «Loading filters…».
- **Jelly-стиль**: нижняя панель Дом/Строка/Обновить/Вкладки/Меню, back/forward
  убраны, тонкий прогресс-бар 3dp поверх страницы, подсказки над панелью.
- **Релоад = стоп**: во время загрузки кнопка превращается в ✕, первый back
  останавливает загрузку.
- **Edge-to-edge**: контент под статусбаром, отступы под статусбар/вырез
  камеры/навигацию/клавиатуру; во время видео отступы замораживаются.
- **Полноэкранное видео**: чёрная подложка поверх страницы (страница не
  перерисовывается), позиция скролла сохраняется и восстанавливается,
  статусбар принудительно скрыт.
- **Щит перенесён в меню** (пункт-статус), кнопки на тулбаре больше нет.
- **Фиксы**: URL не теряется при закрытии вкладок; стабильный скролл
  (без дрожания); тёмный about:blank по теме; favicon теперь реально
  сохраняется (раньше onIcon был заглушкой).

### English
- **Webapp mode** via home-screen shortcut: fullscreen site window with no
  address bar or panels, own task; adblock and downloads work; re-launch
  reuses the window.
- **Pin-to-home via the system dialog** (`requestPinShortcut`) — launcher3
  ignores plain dynamic shortcuts; icon = site favicon (per-host cache, 192px)
  or the app icon as fallback.
- **Back gesture in webapp works on the first swipe**: only the status bar is
  hidden, the navigation bar stays visible.
- **Downloads**: shared `DownloadHelper` (DownloadManager, Downloads folder,
  notification) in both browser and webapp; verified with real F-Droid.apk.
- **Live adblock status line**: engine state listener — shows
  "Total N requests blocked" right after launch, no user action needed.
- **Jelly-style UI**: Home/Url/Reload/Tabs/Menu bottom bar (back/forward
  removed), thin 3dp progress bar over the page, suggestions above the bar.
- **Reload = stop**: during loading the button turns into ✕, first back
  cancels loading.
- **Edge-to-edge**: content draws under the status bar; insets for status
  bar/camera cutout/navigation/IME; insets frozen during fullscreen video.
- **Fullscreen video**: black overlay on top of the page (no page redraw),
  scroll position saved/restored, system bars hidden.
- **Shield moved into the menu** (status item), toolbar button removed.
- **Fixes**: URL stays in the address bar when tabs are closed; stable scroll
  (no jitter); dark `about:blank` follows dark mode; favicons are actually
  saved now (onIcon used to be a no-op).

---

## ✨ Features

### 🛡️ 1. Ad Blocking
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
