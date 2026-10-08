# nodep Android — готово из коробки

## GitHub (без Android Studio)

1. Создай репозиторий, залей **все** файлы из этого архива
2. Actions → **Build APK** → Run workflow
3. Artifacts → скачай `nodep-apk` → внутри `app-debug.apk`

## Что внутри

- WebView UI: `assets/index.html`, `assets/blocked.html`
- Блокировка: `CasinoBlockerService` (Accessibility, без VPN)
- JS-мост: `Nodep.isAccessibilityEnabled()`, `openAccessibilitySettings()`, `goHome()`

## Телефон

Установить APK → открыть nodep → «включить защиту» → в спец. возможностях включить **nodep**.
