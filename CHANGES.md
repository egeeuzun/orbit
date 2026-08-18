# Изменения в форке / Changes in this fork

## Русский
- **Приватность**: история, закладки и открытые вкладки больше не уходят в
  облачный бэкап Google и не переносятся на новое устройство (сохраняется
  только файл настроек); вкладка «Приватная» честно сообщает, что куки и
  логины остаются общими с обычными вкладками.
- **Безопасность**: release подписывается реальным ключом из
  `keystore.properties` (вне репозитория), ключ подписи ротирован; внешние
  ссылки (`tel:/mailto:/…`) открываются только для известных схем.
- **Русские фильтры по умолчанию**: добавлены AdGuard Russian
  (https://filters.adtidy.org/extension/ublock/filters/1.txt) и RU AdList
  (https://easylist-downloads.adblockplus.org/advblock.txt); оба включены по
  умолчанию (каталог списков — 14 позиций); для существующих установок
  автоматическая миграция включает их без ручных действий (в UI «Updated
  today»).
- **Блокировка рекламных попапов**: window.open на домены, заблокированные
  движком с типом «popup», не открывает окно: событие потребляется в
  onNewWindow и в MainActivity, и в WebappActivity, пользователю показывается
  короткий тост «Advertising popup blocked». Фикс бага оригинала
  (egeeuzun/orbit), где попапы открывались без фильтрации; для страниц с
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

## English
- **Privacy**: browsing history, bookmarks and open tabs are no longer uploaded
  to Google cloud backup or device transfer (only the settings file is backed
  up); the "Private" tab honestly notes that cookies and logins are shared with
  regular tabs.
- **Security**: release is signed with a real key from `keystore.properties`
  (outside the repo), the signing key was rotated; external links
  (`tel:/mailto:/...`) only open for known schemes.
- **Russian filters on by default**: two filter lists added — AdGuard Russian
  (https://filters.adtidy.org/extension/ublock/filters/1.txt) and RU AdList
  (https://easylist-downloads.adblockplus.org/advblock.txt); both enabled by
  default (list catalog is now 14 items); existing installs get them via
  automatic migration, no manual action needed (UI shows "Updated today").
- **Ad popup blocking**: window.open on domains blocked by the engine with the
  "popup" type no longer opens a window — the event is consumed in onNewWindow
  in both MainActivity and WebappActivity, and the user gets a short
  "Advertising popup blocked" toast. Fixes an original bug (egeeuzun/orbit)
  where popups opened without filtering; pages with an invalid source (data:
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
