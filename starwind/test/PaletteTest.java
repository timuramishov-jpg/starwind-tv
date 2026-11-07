package com.gathin.tvhome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Единая палитра: цвета пишутся только в IosPalette. Остальные файлы ссылаются на неё. */
public final class PaletteTest {
    public static void main(String[] a) throws Exception {
        Pattern color = Pattern.compile("0x[0-9A-Fa-f]{8}");
        int bad = 0;
        List<Path> files;
        try (Stream<Path> s = Files.list(Paths.get("launcher/app/src/main/java/com/gathin/tvhome"))) {
            files = s.filter(p -> p.getFileName().toString().startsWith("Ios")).collect(Collectors.toList());
        }
        for (Path p : files) {
            String name = p.getFileName().toString();
            // Палитра сама; QR — строго чёрно-белый; остальное — не цвета (протокол, флаги).
            if (name.equals("IosPalette.java") || name.equals("IosQr.java") || name.equals("IosAdb.java")
                    || name.equals("IosRemote.java") || name.equals("IosUpdate.java")) continue;
            List<String> lines = Files.readAllLines(p);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = color.matcher(lines.get(i));
                while (m.find()) {
                    String v = m.group().toUpperCase();
                    if (v.equals("0X00FFFFFF") || v.equals("0X00000000")) continue;
                    System.out.println("ОШИБКА " + name + ":" + (i + 1) + " цвет " + m.group() + " мимо IosPalette");
                    bad++;
                }
            }
        }
        if (bad > 0) { System.out.println("НЕ ПРОШЛИ: " + bad); System.exit(1); }
        System.out.println("ВСЕ ЦВЕТА ИЗ ПАЛИТРЫ");
    }
}
