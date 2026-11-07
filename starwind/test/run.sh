#!/bin/bash
# Проверяет правила пульта, тексты и разбор ответов сервисов на компьютере, без телевизора.
cd "$(dirname "$0")/../.." || exit 1
T=$(mktemp -d)
S=launcher/app/src/main/java/com/gathin/tvhome
# org.json, как в Android: исходники JSON-java (общественное достояние) лежат рядом с тестами.
javac -nowarn -encoding UTF-8 -d "$T" starwind/test/json/org/json/*.java $S/IosNav.java $S/IosText.java $S/IosParse.java \
  starwind/test/NavTest.java starwind/test/TextTest.java starwind/test/ParseTest.java starwind/test/PaletteTest.java starwind/test/SpacingTest.java starwind/test/RemoteTest.java || exit 1
java -cp "$T" com.gathin.tvhome.NavTest && java -cp "$T" com.gathin.tvhome.TextTest && java -cp "$T" com.gathin.tvhome.ParseTest && java -cp "$T" com.gathin.tvhome.PaletteTest && java -cp "$T" com.gathin.tvhome.SpacingTest && java -cp "$T" com.gathin.tvhome.RemoteTest
