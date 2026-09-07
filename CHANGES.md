# История изменений / Changelog

## 2.5 — 7 September 2026

Version bumped from 2.0.0 to 2.5 (version code 2500).

### Background audio
- **Radio and video keep playing when you leave Orbit.** The browser no
  longer pauses WebView when it goes to the background — those calls were
  cutting Chromium's audio pipeline.
- A small foreground notification keeps the process alive while sound is
  playing, with **Play / Pause / Stop** buttons. It also registers a
  `MediaSession`, so lock-screen controls and headset/Bluetooth buttons work.
- The notification only appears when a page is **actually playing audible
  sound**. A watcher script in each frame reports playback state, so leaving
  a silent page no longer claims "Orbit is playing media". Stop ends
  playback for real, not just the notification.

### Tablets, foldables and landscape
- **The address bar can sit at the top or the bottom.** Settings →
  Appearance → Address bar position: Automatic (top on tablets, bottom on
  phones), Always top, Always bottom. Both layouts share the same view ids,
  so one binding drives either one.
- Shortcut grid columns follow screen width: 4 on phones, 6 on wide screens,
  8 on large tablets.
- Touch targets, address bar height and text size grow on wide screens.
- Bottom sheets (menu, tabs, shield, translate) open fully expanded instead
  of a short peek that had to be dragged open on every use.

### Old Samsung / low-end devices
- **The About screen no longer crashes on Android 6 (TouchWiz).**
  `@mipmap/ic_launcher` is an adaptive icon, which cannot be inflated as XML
  below API 26; the screen and the home-screen pin dialog now use a classic
  layer-list drawable instead.
- Legacy PNG launcher icons added for API < 26.
- **New tabs no longer show the previous tab's URL.** On skins where WebView
  hands focus to the address bar while loading, the bar refused updates and
  kept the old address. Focus is now released before the switch and the bar
  is synced to the new tab even while focused.

### Ad blocking
- **The filter catalog grew from 14 to 30 lists, with global coverage.**
  Added AdGuard Base, Tracking Protection, Mobile Ads, Annoyances and
  Social; Fanboy Annoyance; and per-language lists for German, French,
  Spanish/Portuguese, Japanese, Dutch, Italian, Chinese, Polish, Indonesian
  and Korean, next to the existing Turkish and Russian ones.
- Defaults stay conservative: the core global lists plus Turkish and Russian
  are on, the rest are opt-in.

### New in the browser
- **Print page / save as PDF** from the menu, through the system print
  dialog. Devices without a print service show a short error instead.
- **The home screen shortcut grid is editable.** A "+" tile at the end opens
  an Add shortcut dialog (name optional, address required). Long-press a
  shortcut you added to edit or remove it; long-press one that came from
  history to remove it — your history is kept, the site simply stops
  appearing. Removed sites stay removed even after you visit them again.
  Settings → Appearance → **Reset home shortcuts** restores everything.
- **Favicons on the home grid.** Icons already saved while browsing are
  shown on the tiles; sites without one keep the letter tile and fill in on
  the next visit. Icons are decoded downsampled (36 KB each instead of
  147 KB) on a background thread and cached, so on a 1 GB device the whole
  grid costs about 0.3 MB and cold start is unchanged (666 ms with icons vs
  673 ms without, measured on a Galaxy J2 Prime).

### Dark theme fixes
- **Bottom sheets, dialogs and the About screen were stock Material black.**
  The palette lived in two files and the sheet/dialog themes never got it,
  so the menu, tab list, shield, translate sheet and every alert dialog
  painted themselves `#1D1B20` (or AppCompat's `#424242` grey) instead of
  Orbit's navy. The palette is now defined once and applied to the app
  theme, the bottom sheet theme, alert dialogs and the window background.
- **Pages are no longer dark-on-dark.** In dark mode the WebView base colour
  was always navy, so any site that does not paint its own background drew
  its black text onto it and became unreadable (LinkedIn, forums). The base
  is white for normal pages; it stays dark only for the start page and when
  "Darken sites" is enabled.

### Performance
- **Address bar suggestions no longer fire per keystroke.** Queries wait for
  a 150 ms pause, and online suggestions moved off the database thread —
  local history results appear immediately instead of queueing behind a
  network request that could take seconds on a slow link.
- History writes use SQLite WAL, so a page finishing no longer costs two
  fsyncs on slow eMMC.
- Favicon saving (scale + PNG compress) moved off the main thread, and is
  skipped entirely when a fresh file already exists.
- WebView feature checks are resolved once instead of on every navigation,
  and the document-start performance script is only re-registered when its
  contents actually change.
- The request path parses each URL once: no lowercase copies, shared header
  maps, and the local library cache is skipped behind a single flag when it
  is off. Per-site optimization profiles are parsed once into memory instead
  of on every request.
- The reload icon, search hint and status line are only redrawn when their
  value changes; Kotlin's null-check intrinsics are compiled out.
- Under memory pressure the ad blocker's decision cache is released along
  with background tabs.
- The thin progress bar over the page was removed; loading state is tracked
  in code, and the reload button still turns into a stop button while a page
  is loading.

## 2.0.0 — 22 августа 2026 / 22 August 2026

### Русский
- **Приватность**: история, закладки и открытые вкладки больше не уходят в
  облачный бэкап Google и не переносятся на новое устройство (сохраняется
  только файл настроек); вкладка «Приватная» честно сообщает, что куки и
  логины остаются общими с обычными вкладками.
- **Безопасность**: ключ подписи ротирован по-настоящему — выпущен новый
  ключ RSA-4096 (`CN=Ege Uzun, O=Orbit`), а переход оформлен через
  SigningCertificateLineage (APK Signature Scheme v3.1). Благодаря этому
  обновление ставится поверх 1.228 без переустановки: на Android 13+ APK
  проверяется новым ключом, на более старых версиях — прежним сертификатом.
  Ключ и lineage хранятся вне репозитория. Внешние ссылки
  (`tel:/mailto:/…`) открываются только для известных схем.
- **Русские фильтры по умолчанию**: добавлены AdGuard Russian
  (https://filters.adtidy.org/extension/ublock/filters/1.txt) и RU AdList
  (https://easylist-downloads.adblockplus.org/advblock.txt); оба включены по
  умолчанию (каталог списков — 14 позиций); для существующих установок
  автоматическая миграция включает их без ручных действий (в UI «Updated
  today»).
- **Блокировка рекламных попапов**: window.open на домены, заблокированные
  движком с типом «popup», не открывает окно: событие потребляется в
  onNewWindow и в MainActivity, и в WebappActivity, пользователю показывается
  короткий тост «Advertising popup blocked». Исправляет баг версии 1.228,
  где попапы открывались без фильтрации; для страниц с
  невалидным источником (data: и т.п.) используется фиктивный источник.
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
- **Privacy**: browsing history, bookmarks and open tabs are no longer uploaded
  to Google cloud backup or device transfer (only the settings file is backed
  up); the "Private" tab honestly notes that cookies and logins are shared with
  regular tabs.
- **Security**: the signing key has actually been rotated — a new RSA-4096 key
  (`CN=Ege Uzun, O=Orbit`) was issued and the transition is carried by a
  SigningCertificateLineage (APK Signature Scheme v3.1). As a result the build
  installs over 1.228 as a normal update with no reinstall: on Android 13+ the
  APK verifies against the new key, on older releases against the previous
  certificate. Key and lineage live outside the repo. External links
  (`tel:/mailto:/...`) only open for known schemes.
- **Russian filters on by default**: two filter lists added — AdGuard Russian
  (https://filters.adtidy.org/extension/ublock/filters/1.txt) and RU AdList
  (https://easylist-downloads.adblockplus.org/advblock.txt); both enabled by
  default (list catalog is now 14 items); existing installs get them via
  automatic migration, no manual action needed (UI shows "Updated today").
- **Ad popup blocking**: window.open on domains blocked by the engine with the
  "popup" type no longer opens a window — the event is consumed in onNewWindow
  in both MainActivity and WebappActivity, and the user gets a short
  "Advertising popup blocked" toast. Fixes a bug in 1.228 where
  popups opened without filtering; pages with an invalid source (data:
  etc.) use a fake source.
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
