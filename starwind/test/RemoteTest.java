package com.gathin.tvhome;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Пульт в телефоне — та же дизайн-система, что телевизор. Цвета страницы — точные значения IosPalette,
 * шрифт, скругления и отступы — токены телевизора, делённые на 2. Вне :root — только var(--…).
 */
public final class RemoteTest {
    private static int bad;

    public static void main(String[] a) throws Exception {
        String pal = new String(Files.readAllBytes(Paths.get("launcher/app/src/main/java/com/gathin/tvhome/IosPalette.java")), "UTF-8");
        String html = new String(Files.readAllBytes(Paths.get("launcher/app/src/main/assets/remote.html")), "UTF-8");
        int r0 = html.indexOf(":root{"), r1 = html.indexOf('}', r0);
        String root = html.substring(r0, r1);
        Map<String, String> vars = new LinkedHashMap<>();
        Matcher v = Pattern.compile("--([a-z0-9-]+):([^;]+);?").matcher(root);
        while (v.find()) vars.put(v.group(1), v.group(2).trim().toLowerCase());

        // Цвета: каждая константа палитры есть на странице с тем же значением (#rrggbb или #rrggbbaa).
        Matcher c = Pattern.compile("static final int (\\w+) = 0x([0-9A-Fa-f]{8});").matcher(pal);
        while (c.find()) {
            String name = c.group(1).toLowerCase().replace('_', '-'), argb = c.group(2).toLowerCase();
            String alpha = argb.substring(0, 2), rgb = argb.substring(2);
            String want = "#" + rgb + (alpha.equals("ff") ? "" : alpha);
            check(want.equals(vars.get(name)), "цвет --" + name + " = " + vars.get(name) + ", в палитре " + want);
        }
        // Размеры: токены телевизора / 2.
        Matcher f = Pattern.compile("(\\w+) = (\\d+(?:\\.\\d+)?)").matcher(
                pal.substring(pal.indexOf("static final float DISPLAY"), pal.indexOf("static final float EDGE")));
        while (f.find()) {
            String name = f.group(1).toLowerCase().replace('_', '-');
            double half = Double.parseDouble(f.group(2)) / 2;
            String want = (half == Math.rint(half) ? String.valueOf((long) half) : String.valueOf(half)) + "px";
            check(want.equals(vars.get(name)), "размер --" + name + " = " + vars.get(name) + ", нужно " + want);
        }
        // Вне :root — ни одного цвета и ни одного размера шрифта или скругления числом, ни одного style="".
        String rest = html.substring(0, r0).replace("content=\"#000000\"", "") + html.substring(r1);
        find(rest, "#[0-9a-fA-F]{3,8}\\b(?![\\w-])", "цвет мимо палитры");
        find(rest, "rgba?\\(", "цвет мимо палитры");
        find(rest, "font-size:\\s*\\d", "размер шрифта мимо шкалы");
        find(rest, "border-radius:\\s*(?!0[;}\\s]|50%|var\\()[^;}]+", "скругление мимо шкалы");
        find(rest, "style=\"", "стиль в разметке");
        if (bad > 0) { System.out.println("НЕ ПРОШЛИ: " + bad); System.exit(1); }
        System.out.println("ПУЛЬТ В ДИЗАЙН-СИСТЕМЕ");
    }

    private static void find(String s, String re, String what) {
        Matcher m = Pattern.compile(re).matcher(s);
        while (m.find()) {
            int line = 1;
            for (int i = 0; i < m.start(); i++) if (s.charAt(i) == '\n') line++;
            check(false, what + ": «" + m.group() + "» (строка ~" + line + ")");
        }
    }

    private static void check(boolean ok, String msg) {
        if (!ok) { System.out.println("ОШИБКА " + msg); bad++; }
    }
}
