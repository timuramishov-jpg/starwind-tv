#!/bin/bash
# Проверяет правила пульта на компьютере, без телевизора.
cd "$(dirname "$0")/../.." || exit 1
T=$(mktemp -d)
javac -d "$T" launcher/app/src/main/java/com/gathin/tvhome/IosNav.java starwind/test/NavTest.java && java -cp "$T" com.gathin.tvhome.NavTest
