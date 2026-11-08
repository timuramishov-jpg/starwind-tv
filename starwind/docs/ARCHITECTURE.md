# Устройство программы

## Общая схема

```
 Мак (~/Documents/Claude)                    Телевизор (Android 11, без root)                     Телефон
 ┌─────────────────────────┐   adb :5555    ┌───────────────────────────────────────────────┐
 │ tv (starwind/tv)        │──────────────▶│ com.gathin.tvhome  — один APK, несколько процессов│
 │  сборка Gradle, тесты,  │               │                                               │
 │  установка, ключи,      │               │  основной   IosHome: экран, док, ленты, меню,  │
 │  журналы                │               │             заставки, VPN, настройки           │
 │ tools/tv.py (adb на     │               │  :ime       IosKeyboard — своя клавиатура       │
 │  Python)                │               │  :tg        TgCore/TgActivity/плеер — Telegram   │   HTTP :8687
 │ agent.sh — очередь      │               │  shell-uid  IosShell — сервер пульта  ◀──────────┼──────────── remote.html
 │  заданий для ИИ         │               │             (app_process от имени оболочки)    │   (в браузере)
 └─────────────────────────┘               └───────────────────────────────────────────────┘
                                             Happ (VPN) заворачивает весь трафик телевизора
```

- **Один APK** `com.gathin.tvhome` (лаунчер CleanYourTV, переписанный экран). Сборка — `launcher/` (Gradle, Java,
  minSdk 26, targetSdk 34). Версия = число коммитов + 100 (`starwindBuild()` в `launcher/app/build.gradle`).
- **Root не нужен.** Права уровня оболочки лаунчер получает двумя путями:
  1. `IosAdb` — свой adb-клиент внутри лаунчера: подключается к отладке телевизора по `127.0.0.1:5555`
     (ключ adb разрешается один раз) и выполняет команды как `shell` — отключить приложение, очистить кэш, DNS и т.п.
  2. `IosShell` — процесс сервера пульта, запущенный через `app_process` от имени `shell` (как scrcpy).
- Права, которые `tv deploy` выдаёт явно: `WRITE_SECURE_SETTINGS`, `READ_TV_LISTINGS`, администратор устройства
  (`IosAdmin`, только «заблокировать экран» — для таймера сна), установка APK (`REQUEST_INSTALL_PACKAGES`), заставка
  (`IosDream`), клавиатура (`ime enable/set`), домашний экран (`cmd package set-home-activity`).

## Классы (`launcher/app/src/main/java/com/gathin/tvhome`)

Свои классы начинаются на `Ios*` (экран и сервисы) и `Tg*` (Telegram). Остальные (`MainActivity`, `AppAdapter`,
`*Drawable`…) — исходный лаунчер CleanYourTV, в Starwind не используются для экрана.

| Класс | Строк | Что делает |
|---|---|---|
| `IosHome` | ~1580 | Главный экран: каркас, док, навигация с пульта, ленты (отрисовка), ряд приложений, шапка, переходы |
| `IosNav` | 86 | Правила навигации стрелками — чистая логика без Android, покрыта `NavTest` |
| `IosCards` | 192 | Карточки ленты: рамка, подписи, обложки рядом с выбранной, подсветка |
| `IosMenu` | 290 | Боковая панель справа (все меню: настройки, поиск, приложение, Wi‑Fi): строки, тумблеры, выбор стрелками |
| `IosSettings` | 184 | Пункты настроек |
| `IosSearch` | 117 | Поиск: поле, недавние, результаты по сервисам |
| `IosVpn` | 153 | Экран VPN (Happ): тумблер, отклик, скорость, страна |
| `IosInputs` | 93 | Источники (HDMI), подсказка при подключении |
| `IosFeed` | ~1040 | Ленты: YouTube (OAuth на ТВ, подписки/популярное, фильтр шортсов), ВК, Рутуб, Кинопоиск, Зона, Telegram; кэш обложек |
| `IosParse`, `IosText` | | Разбор ответов сервисов и тексты — без Android, покрыты тестами |
| `IosIcons`, `IosArt` | | Логотипы-маски в цвете сервиса, рисунки на холсте |
| `IosPalette` | 87 | Дизайн-система (см. [DESIGN.md](DESIGN.md)) |
| `IosSaver`, `IosSaverActivity`, `IosDream` | ~750 | Семь заставок; окно заставки и системная заставка (DreamService) |
| `IosSleep`, `IosAdmin` | | Таймер сна через будильник системы, гашение экрана |
| `IosKeyboard` | 235 | Своя клавиатура (InputMethodService, процесс `:ime`) |
| `IosRemote` | 188 | Сторона лаунчера для пульта: ключ, список приложений с логотипами, состояние, запуск `IosShell` |
| `IosShell` | ~770 | Сервер пульта (HTTP :8687), см. ниже |
| `IosCommand` | 48 | Поручения от сервера пульта, которые может выполнить только лаунчер (VPN, сон, заставка, буфер) |
| `IosTools` | 363 | Действия с правами оболочки через `IosAdb`: приложения, кэш, DNS, анимации, перезагрузка |
| `IosAdb` | 270 | adb-клиент к `127.0.0.1:5555` |
| `IosMemory` | 86 | Автоочистка памяти при возврате на главный экран |
| `IosConfig` | 32 | Приём ключей сервисов по adb (только от `shell`) |
| `IosUpdate` | 122 | Обновление с GitHub (`timuramishov-jpg/tv-home`, релизы `v<номер>`) |
| `IosBackup`, `IosCrash` | | Копия настроек на случай переустановки, журнал падений |
| `IosWeather` | 109 | Погода (Open‑Meteo, без ключа) |
| `IosQr` | 29 | QR-коды (ZXing) |
| `Tg*` | | Клиент Telegram — см. [TELEGRAM.md](TELEGRAM.md) |

## Сервер пульта (`IosShell`)

- Запуск (это делает `IosRemote.ensureShell()` при старте лаунчера и `tv deploy`):
  ```sh
  A=$(pm path com.gathin.tvhome | cut -d: -f2)
  (CLASSPATH=$A nohup setsid app_process /system/bin com.gathin.tvhome.IosShell </dev/null >/data/local/tmp/iosshell.log 2>&1 &)
  ```
  Путь к APK сервер пишет в `/data/local/tmp/iosshell.apk` и сам выходит, если APK обновился (лаунчер запустит новый).
- Почему отдельно от лаунчера: на телевизоре 1 ГБ, тяжёлые приложения (YouTube) выгружают лаунчер, а сервер оболочки
  система не трогает; кнопки вводятся напрямую через `InputManager.injectInputEvent` (мгновенно); ответы не уходят в VPN.
- Данные от лаунчера: `/sdcard/Android/data/com.gathin.tvhome/files/remote/` — `key`, `apps.json`, `labels.json`,
  `logos/*.png`, `state.json`, `typing` (клавиатура открыта), `tg.json` (состояние Telegram).
- Что требует лаунчера, сервер передаёт широковещательным `IosCommand` / `TgReceiver` (защищены разрешением `DUMP`,
  которое есть только у оболочки, плюс ключ пульта).
- Список запросов — в [REMOTE.md](REMOTE.md#запросы-сервера).

## Ввод текста с телефона

- В своей клавиатуре (`IosKeyboard`): клавиатура пишет `remote/typing = 1`, пульт показывает поле, текст уходит
  широковещательным `com.gathin.tvhome.TYPE` прямо в поле (кириллица работает).
- YouTube (Cobalt) свою клавиатуру не отдаёт системе — поиск идёт ссылкой `https://www.youtube.com/results?search_query=…`
  на экран `com.yandex.tv.ytplayer/dev.cobalt.app.MainActivity`.

## Процессы и память

| Процесс | Что | Память |
|---|---|---|
| `com.gathin.tvhome` | Главный экран | ~85–90 МБ |
| `com.gathin.tvhome:ime` | Клавиатура | мало |
| `com.gathin.tvhome:tg` | Telegram (TDLib + плеер) | ~90 МБ, только когда открыт |
| `app_process` (shell) | Сервер пульта | мало |

Без аппаратных слоёв на карточках, обложки в размер карточки, `onTrimMemory` отпускает обложки; при возврате домой при
нехватке памяти — автоочистка фоновых приложений (`IosMemory`).

## Хранилище

| Где | Что |
|---|---|
| Настройки лаунчера (`SharedPreferences` `ios_home_v4`) | Порядок дока, скрытые, ленты, выбранный сервис, заставка, клавиатура |
| `ios_keys` | Ключи сервисов (из `tv keys`) |
| `tg` (процесс `:tg`) | Ключ Telegram API, позиции просмотра, «недавние» |
| `files/tg`, `cache/tg` | База и файлы TDLib |
| `/sdcard/Android/data/com.gathin.tvhome/files/` | Данные для пульта (`remote/`), копия настроек (`backup/`), `crash.txt` |

## Мак-сторона

| Файл | Что |
|---|---|
| `starwind/tv` | Единый инструмент (`tv` без аргументов — справка) |
| `starwind/agent.sh` | Агент: выполняет задания из `~/Documents/Claude/agent/in/*.sh`, вывод в `agent/out/` (так ИИ-помощник работает с телевизором) |
| `starwind/route-on.sh` | Профиль маршрутизации Happ «только YouTube» |
| `starwind/icons.swift` | Рисует значки SF Symbols в PNG |
| `starwind/test/` | Тесты без Android (Java): палитра, сетка, пульт, навигация, тексты, разбор лент |
| `starwind/vendor/tdlib/` | Нативная библиотека Telegram |
| `starwind/art/` | Векторные исходники (знак Telegram) |
| `tools/tv.py`, `tools/b64push.py` | Связь с телевизором по adb без установленного adb (из CleanYourTV) |
