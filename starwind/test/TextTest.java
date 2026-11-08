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
        check("карточка → секунды: 1:54", IosText.clockSeconds("1:54"), 114);
        check("карточка → секунды: 1:02:03", IosText.clockSeconds("1:02:03"), 3723);
        check("карточка → секунды: пусто", IosText.clockSeconds(""), 0);
        check("Twitch: 3h2m10s", IosText.twitchSeconds("3h2m10s"), 10930);
        check("Twitch: 45m", IosText.twitchSeconds("45m"), 2700);
        check("зрители: 1", IosText.viewers(1), "1 зритель");
        check("зрители: 3", IosText.viewers(3), "3 зрителя");
        check("зрители: 12", IosText.viewers(12), "12 зрителей");
        check("зрители: 850", IosText.viewers(850), "850 зрителей");
        check("зрители: 1200", IosText.viewers(1200), "1,2 тыс. зрителей");
        check("зрители: 1000", IosText.viewers(1000), "1 тыс. зрителей");
        check("зрители: 15400", IosText.viewers(15400), "15 тыс. зрителей");
        long H = 3600_000L, t0 = 1_000_000_000_000L;
        check("продолжить: только открыли час назад", IosText.keepContinue(t0, t0 + H, -1, 3600), true);
        check("продолжить: клип 1:54 — нет", IosText.keepContinue(t0, t0 + 60_000, -1, 114), false);
        check("продолжить: прошло 13 часов — нет", IosText.keepContinue(t0, t0 + 13 * H, -1, 7200), false);
        check("продолжить: вернулись через минуту — нет", IosText.keepContinue(t0, t0 + H, 60_000, 3600), false);
        check("продолжить: смотрели 20 мин из часа — да", IosText.keepContinue(t0, t0 + H, 20 * 60_000, 3600), true);
        check("продолжить: пропадали дольше видео — нет", IosText.keepContinue(t0, t0 + 3 * H, 2 * H, 3600), false);
        check("продолжить: фильм без длины, 4 часа — нет", IosText.keepContinue(t0, t0 + 5 * H, 4 * H, 0), false);
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
