# AGENTS.md — инструкция для ИИ-помощников

Читают: Claude Code (через `CLAUDE.md`), Gemini CLI (`GEMINI.md`), Codex, Cursor, Copilot и другие.
Язык проекта — русский: интерфейс, документация, комментарии в коде, сообщения коммитов.

## Что это

**Starwind TV** — свой домашний экран для телевизора на Яндекс ТВ (проверено на StarWind SW-LED43SG300: Android 11,
~925 МБ памяти, armeabi-v7a, без root), пульт в телефоне (веб-страница с самого телевизора) и свой клиент Telegram для
видео из постов. Ставится с Мака по adb одной командой, всё обратимо. Вырос из открытого CleanYourTV — его исходные
материалы лежат в `upstream/` и **к Starwind не относятся** (не читать и не править без прямой просьбы).

## Прочитать перед работой (по порядку)

1. [`starwind/docs/DEVELOPMENT.md`](starwind/docs/DEVELOPMENT.md) — команды, правила, ловушки, как работать с телевизором.
2. [`starwind/docs/ARCHITECTURE.md`](starwind/docs/ARCHITECTURE.md) — процессы, права, классы, хранилище.
3. [`starwind/docs/DESIGN.md`](starwind/docs/DESIGN.md) — дизайн-система (обязательна).
4. По задаче: [`TELEGRAM.md`](starwind/docs/TELEGRAM.md), [`REMOTE.md`](starwind/docs/REMOTE.md) (в т.ч. все запросы
   сервера пульта), [`USAGE.md`](starwind/docs/USAGE.md), [`INSTALL.md`](starwind/docs/INSTALL.md),
   [`TROUBLESHOOTING.md`](starwind/docs/TROUBLESHOOTING.md), [`HISTORY.md`](starwind/docs/HISTORY.md) (почему так).

## Карта репозитория

| Путь | Что |
|---|---|
| `launcher/app/src/main/java/com/gathin/tvhome/Ios*.java` | Главный экран и сервисы (`IosHome` — каркас, `IosPalette` — дизайн-система, `IosShell` — сервер пульта…) |
| `launcher/app/src/main/java/com/gathin/tvhome/Tg*.java` | Клиент Telegram (TDLib, процесс `:tg`) |
| `launcher/app/src/main/assets/remote.html` | Пульт в телефоне — один файл HTML/CSS/JS |
| `launcher/app/src/main/AndroidManifest.xml` | 12 компонентов, все используются |
| `starwind/tv` | Единый инструмент на Маке (bash): `setup`, `deploy`, `smoke`, `check`, `log`, `release`… |
| `starwind/test/` | Тесты без Android (Java): палитра, сетка отступов, пульт, навигация, тексты, разбор лент |
| `starwind/docs/` | Документация; `Starwind-tutorial.pdf` — учебник «с нуля» (исходник — `docs/tutorial/`) |
| `starwind/vendor/tdlib/` | Нативная TDLib (проверяется по SHA-256 при сборке) |
| `starwind/fallback/` | Запасные значки и шрифт-замена Inter, если на Маке нет SF Pro / Swift |
| `tools/tv.py`, `tools/b64push.py` | adb на Python (из CleanYourTV) — ими пользуется `starwind/tv`; остальное в `tools/` — наследие upstream |
| `upstream/` | Исходный CleanYourTV: примеры устройств, аддоны, темы, переводы README. Для Starwind не нужно |

## Как работать

```bash
bash starwind/test/run.sh              # тесты — обязательно перед любой установкой и коммитом
~/Documents/Claude/tv deploy           # тесты → сборка → установка → права → релиз на GitHub
QUIET=1 ~/Documents/Claude/tv deploy   # то же, не уводя телевизор с текущего экрана
~/Documents/Claude/tv smoke            # проверка после установки: снимки всех экранов, плавность, падения
~/Documents/Claude/tv check | log 100  # состояние / журнал
```

- **Есть доступ к терминалу Мака** — выполняй `tv …` сам.
- **Работаешь в облаке без доступа к домашней сети** — через агента на Маке (`tv agent`): кладёшь скрипт в
  `~/Documents/Claude/agent/in/<уникальное-имя>.sh`, результат появляется в `agent/out/<имя>.txt`. Код передаётся
  `git bundle` → `~/Documents/Claude/starwind.bundle`. Подробно — DEVELOPMENT.md, «Работа с ИИ-помощником».
- Сборка без телевизора: `~/Documents/Claude/tv build` (тесты, шрифты, значки, TDLib, APK).

## Правила (нарушать нельзя)

1. **Дизайн-система.** Цвета, шрифты, скругления, отступы — только токены `IosPalette`; в пульте — `var(--…)` с теми же
   значениями ровно вдвое меньше. Тесты `PaletteTest`/`SpacingTest`/`RemoteTest` это проверяют. Пульт и телевизор
   выглядят одинаково: меняешь одно — меняй и другое.
2. **Два состояния — тумблер, больше — выбор** стрелками на ТВ и списком на пульте.
3. **Тексты интерфейса** — по-русски и коротко. Комментарии в коде — по-русски, объясняют «почему».
4. **Не мешать просмотру.** Перед любым действием на экране телевизора проверить, что открыто
   (`dumpsys activity activities | grep mResumedActivity`): если не наш экран/заставка — ничего не нажимать, катить
   только `QUIET=1`. `tv smoke` проверяет это сам.
5. **Не нажимать вслепую в системных настройках** — сначала снимок экрана, потом нажатие (так однажды выключился Wi‑Fi).
6. **Ничего не удалять у системы** — только `pm disable-user`; всё должно откатываться (`tv rollback`).
7. **Ключи — никогда в git**: `secrets.json`, `starwind.jks`/`.pass`/`.alias`, ключ Telegram API, ключ пульта.
   В APK ключей тоже нет (APK лежит в открытых релизах).
8. **Telegram — только через VPN** (без туннеля сеть TDLib на паузе). Пароля у пульта нет — так задумано (домашняя сеть).
9. **Память — главный ресурс** (~925 МБ на всё): без аппаратных слоёв и тяжёлых эффектов, обложки в размер,
   тяжёлое — в отдельные процессы, долгие операции — не в главном потоке. Плавность проверять `tv smoke`.
10. Крупные экраны — отдельными классами; `IosHome` — только каркас, док и навигация.
11. После изменений, заметных человеку, — обновить документацию в `starwind/docs` (и снимки экрана из `tv smoke`).

## Проверка своей работы

- `bash starwind/test/run.sh` — зелёный.
- Сборка проходит (`tv deploy` или `./gradlew assembleRelease`).
- `tv smoke` → «РЕЗУЛЬТАТ: всё в порядке»; посмотреть снимки в `~/Documents/Claude/smoke/<время>/`.
- Для пульта — открыть страницу и проверить вкладку, которую трогал.

## Git

- Основная ветка — `main` (локально может называться `starwind`). Сообщения коммитов — по-русски, о том, что изменилось
  для человека. На GitHub при каждом пуше запускаются те же тесты (`.github/workflows/starwind.yml`).
- Номер версии = число коммитов + 100; `tv deploy` выкладывает сборку в открытый репозиторий релизов `<логин>/tv-home`,
  откуда телевизор обновляется из меню.

## Другой владелец / другой телевизор

Свой адрес (`tv setup` или `TV_IP=…`), свои ключи (`secrets.json`), свой ключ подписи (создаётся сам), свой репозиторий
релизов (создаётся сам после `gh auth login`). Шрифты SF — из архива Apple (INSTALL.md, шаг 3), без него ставится
замена Inter. Имена пакетов Яндекса на другой модели могут отличаться (`tv slim`).
