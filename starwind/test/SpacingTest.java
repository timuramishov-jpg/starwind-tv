package com.gathin.tvhome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сетка отступов: в отступах и полях экранов — только кратные 4 (а лучше токены S1–S7 из IosPalette). */
public final class SpacingTest {
    public static void main(String[] a) throws Exception {
        Pattern call = Pattern.compile("(setPadding|Margin\\s*=|setMargins)[^;]*;");
        Pattern num = Pattern.compile("px\\((\\d+(?:\\.\\d+)?)f?\\)");
        int bad = 0;
        String[] files = {"IosHome.java", "IosMenu.java", "IosCards.java", "IosSearch.java", "IosSettings.java", "IosVpn.java", "IosInputs.java", "TgActivity.java", "TgPlayerActivity.java"};
        for (String f : files) {
            List<String> lines = Files.readAllLines(Paths.get("launcher/app/src/main/java/com/gathin/tvhome", f));
            for (int i = 0; i < lines.size(); i++) {
                Matcher c = call.matcher(lines.get(i));
                while (c.find()) {
                    Matcher m = num.matcher(c.group());
                    while (m.find()) {
                        double v = Double.parseDouble(m.group(1));
                        if (v % 4 != 0) { System.out.println("ОШИБКА " + f + ":" + (i + 1) + " отступ " + m.group() + " мимо сетки"); bad++; }
                    }
                }
            }
        }
        if (bad > 0) { System.out.println("НЕ ПРОШЛИ: " + bad); System.exit(1); }
        System.out.println("ВСЕ ОТСТУПЫ ПО СЕТКЕ");
    }
}
