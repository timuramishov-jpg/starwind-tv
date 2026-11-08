# Библиотека Telegram (TDLib) для armeabi-v7a

Готовые файлы, чтобы сборка не зависела от внешних загрузок. `tv deploy` сверяет их контрольные суммы
(список — в `starwind/tv`, функция `tglibs`) и копирует в `launcher/app/src/main/jniLibs/armeabi-v7a/`.

| Файл | Откуда | Лицензия |
|---|---|---|
| `libtdjni.so`, `libcryptox.so`, `libsslx.so` | официальная сборка Telegram X: github.com/TGX-Android/tdlib, папки `src/main/libs/23.2.8568313/android-16/armeabi-v7a` и `openssl/23.2.8568313/android-16/armeabi-v7a/lib` (Git LFS) | TDLib — Boost Software License 1.0; OpenSSL — Apache 2.0 |
| `libc++_shared.so` | Android NDK r23c, `toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/arm-linux-androideabi/` | Apache 2.0 with LLVM exceptions |

Java-часть (`org/drinkless/tdlib/Client.java`, `TdApi.java`) лежит в исходниках лаунчера и взята из того же
репозитория TGX-Android/tdlib — версии должны совпадать с этими `.so`.

Важно: libc++ нужна именно из NDK r23 — в r27 и новее нет символа `std::__ndk1::__hash_memory`, и `libtdjni.so` не загрузится.
