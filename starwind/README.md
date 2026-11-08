# Starwind: всё, что относится к своему экрану

Ветка Starwind поверх CleanYourTV. Обзор и скриншоты — в [корневом README](../README.md), документация — в [`docs/`](docs).

| Папка / файл | Что |
|---|---|
| [`tv`](tv) | Единый инструмент на Маке: `tv setup`, `tv deploy`, `tv check`… (без аргументов — справка) |
| [`docs/`](docs) | Документация: установка, использование, пульт, Telegram, дизайн, устройство, разработка |
| [`test/`](test) | Тесты без Android: дизайн-система, пульт, навигация, тексты, разбор лент |
| [`vendor/tdlib/`](vendor/tdlib) | Библиотека Telegram (TDLib) для armeabi-v7a |
| [`agent.sh`](agent.sh) | Агент для ИИ-помощника: очередь заданий на Маке |
| [`icons.swift`](icons.swift), [`fallback/`](fallback) | Значки SF Symbols; запасные PNG и шрифт-замена Inter (если нет SF Pro) |
| [`art/`](art) | Векторные исходники |
| [`route-on.sh`](route-on.sh) | VPN Happ только для YouTube |

Код экрана — классы `Ios*` и `Tg*` в `launcher/app/src/main/java/com/gathin/tvhome`, пульт — `launcher/app/src/main/assets/remote.html`.

Не в репозитории (хранить отдельно): `~/Documents/Claude/secrets.json` (ключи YouTube и Кинопоиска), ключ подписи
`starwind.jks`, архив шрифтов SF, ключ Telegram API. Подробно — [docs/INSTALL.md](docs/INSTALL.md#что-хранить-отдельно-в-git-этого-нет).
