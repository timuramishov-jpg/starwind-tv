# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Сервер пульта запускается через app_process по имени класса.
-keep class com.gathin.tvhome.IosShell { public static void main(java.lang.String[]); }
-keep class com.gathin.tvhome.IosCommand

# Telegram (TDLib): нативная часть создаёт объекты TdApi по именам классов и полей.
-keep class org.drinkless.tdlib.** { *; }
-keep class com.gathin.tvhome.TgReceiver
-dontwarn org.drinkless.tdlib.**
