# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Сервер пульта запускается через app_process по имени класса.
-keep class com.gathin.tvhome.IosShell { public static void main(java.lang.String[]); }
-keep class com.gathin.tvhome.IosCommand
