package com.gathin.tvhome;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор и подписи без Android: длительности, даты, «шортс или нет», погода словами.
 *  Отдельно от лент, чтобы проверять тестами на компьютере (starwind/test). */
final class IosText {
    private IosText() { }

    private static final Pattern ISO = Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");

    /** P1DT2H3M4S в секунды, 0 если не разобрать. */
    static int seconds(String d) {
        Matcher m = ISO.matcher(d == null ? "" : d);
        if (!m.matches()) return 0;
        int[] v = new int[4];
        for (int k = 0; k < 4; k++) v[k] = m.group(k + 1) == null ? 0 : Integer.parseInt(m.group(k + 1));
        return v[0] * 86400 + v[1] * 3600 + v[2] * 60 + v[3];
    }

    /** PT1H2M3S в 1:02:03, PT4M5S в 4:05, пусто для нуля. */
    static String iso(String d) { return clock(seconds(d)); }

    static String clock(int s) {
        if (s <= 0) return "";
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Помечен как шортс прямо в названии. */
    static boolean taggedShort(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return t.contains("#short") || t.contains("#шортс") || t.contains("#шорт");
    }

    /** Может ли ролик быть шортсом по длине: от 1 секунды до 3 минут. */
    static boolean shortLength(String isoDuration) {
        int s = seconds(isoDuration);
        return s > 0 && s <= 180;
    }

    /** 2026-11-06T18:00:00Z в миллисекунды, 0 если не разобрать. */
    static long when(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** «5 мин назад», «3 ч назад», «вчера», «4 дн назад». */
    static String ago(long at, long now) {
        long m = (now - at) / 60000;
        if (m < 60) return Math.max(1, m) + " мин назад";
        if (m < 24 * 60) return (m / 60) + " ч назад";
        return m / (24 * 60) == 1 ? "вчера" : (m / (24 * 60)) + " дн назад";
    }

    /** Коды погоды WMO словами. */
    static String weather(int code) {
        if (code == 0) return "ясно";
        if (code <= 2) return "переменная облачность";
        if (code == 3) return "облачно";
        if (code <= 48) return "туман";
        if (code <= 57) return "морось";
        if (code <= 67) return "дождь";
        if (code <= 77) return "снег";
        if (code <= 82) return "ливень";
        if (code <= 86) return "снегопад";
        return "гроза";
    }

    /** Температура со знаком: +3°, −2°, 0°. */
    static String degrees(double t) {
        long r = Math.round(t);
        return (r > 0 ? "+" : r < 0 ? "−" : "") + Math.abs(r) + "°";
    }

    private static final String[] PEOPLE = {"человек", "люд", "мужчин", "женщин", "девушк", "девочк", "мальчик", "парн", "ребён",
            "ребен", "дети", "детск", "семья", "семей", "туристы", "толп", "фестивал", "праздн", "карнавал", "парад", "серфер",
            "лыжник", "сноубордист", "альпинист", "велосипедист", "бегун", "рыбак", "пастух", "монах", "танц", "музыкант",
            "спортсмен", "портрет", "участник", "зрител", "паломник", "жители", "фермер"};

    /** Есть ли на фото дня люди — по подписи. Нужны только места. */
    static boolean hasPeople(String caption) {
        String t = caption == null ? "" : caption.toLowerCase(java.util.Locale.ROOT);
        for (String w : PEOPLE) if (t.contains(w)) return true;
        return false;
    }
}
