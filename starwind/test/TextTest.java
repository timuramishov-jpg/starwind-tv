package com.gathin.tvhome;

/** Тесты разбора лент: длительности, шортсы, даты, погода. Запуск: starwind/test/run.sh */
public final class TextTest {
    private static int failed;

    private static void check(String what, Object got, Object want) {
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("ПРОВАЛ " + what + ": ждали " + want + ", получили " + got);
        } else System.out.println("ок     " + what);
    }

    public static void main(String[] a) {
        check("длительность: минуты и секунды", IosText.iso("PT4M5S"), "4:05");
        check("длительность: часы", IosText.iso("PT1H2M3S"), "1:02:03");
        check("длительность: только секунды", IosText.iso("PT45S"), "0:45");
        check("длительность: сутки", IosText.seconds("P1DT2H"), 93600);
        check("длительность: прямой эфир без длины", IosText.iso("P0D"), "");
        check("длительность: мусор", IosText.seconds("abc"), 0);
        check("шортс: метка #shorts", IosText.taggedShort("Кот прыгает #Shorts"), true);
        check("шортс: метка #шортс", IosText.taggedShort("Смешно #шортс #тренды"), true);
        check("шортс: обычное название", IosText.taggedShort("Обзор iPhone 17"), false);
        check("шортс по длине: 2:30 может быть", IosText.shortLength("PT2M30S"), true);
        check("шортс по длине: 3:01 уже нет", IosText.shortLength("PT3M1S"), false);
        check("шортс по длине: эфир без длины — нет", IosText.shortLength("P0D"), false);
        check("дата YouTube", IosText.when("2026-11-06T18:00:00Z"), 1793988000000L);
        check("дата: пусто", IosText.when(""), 0);
        long now = 1793988000000L;
        check("недавно: минуты", IosText.ago(now - 5 * 60000, now), "5 мин назад");
        check("недавно: меньше минуты", IosText.ago(now - 1000, now), "1 мин назад");
        check("недавно: часы", IosText.ago(now - 3 * 3600000L, now), "3 ч назад");
        check("недавно: вчера", IosText.ago(now - 30 * 3600000L, now), "вчера");
        check("недавно: дни", IosText.ago(now - 4 * 86400000L, now), "4 дн назад");
        check("погода: ясно", IosText.weather(0), "ясно");
        check("погода: облачно", IosText.weather(3), "облачно");
        check("погода: снег", IosText.weather(73), "снег");
        check("погода: гроза", IosText.weather(95), "гроза");
        check("температура плюс", IosText.degrees(2.6), "+3°");
        check("температура минус", IosText.degrees(-1.6), "−2°");
        check("температура ноль", IosText.degrees(0.2), "0°");
        System.out.println(failed == 0 ? "ВСЕ ТЕСТЫ ЛЕНТ ПРОШЛИ" : "ПРОВАЛЕНО: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
