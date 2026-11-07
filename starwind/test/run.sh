#!/bin/bash
# Проверяет правила пульта и разбор лент на компьютере, без телевизора.
cd "$(dirname "$0")/../.." || exit 1
T=$(mktemp -d)
S=launcher/app/src/main/java/com/gathin/tvhome
javac -encoding UTF-8 -d "$T" $S/IosNav.java $S/IosText.java starwind/test/NavTest.java starwind/test/TextTest.java || exit 1
java -cp "$T" com.gathin.tvhome.NavTest && java -cp "$T" com.gathin.tvhome.TextTest
