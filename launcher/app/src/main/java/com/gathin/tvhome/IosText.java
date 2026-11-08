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

    /** Значок погоды по коду WMO: sun, moon, sun_cloud, moon_cloud, cloud, fog, drizzle, rain, snow, storm. */
    static String weatherIcon(int code, boolean day) {
        if (code == 0) return day ? "sun" : "moon";
        if (code <= 2) return day ? "sun_cloud" : "moon_cloud";
        if (code == 3) return "cloud";
        if (code <= 48) return "fog";
        if (code <= 57) return "drizzle";
        if (code <= 67 || code >= 80 && code <= 82) return "rain";
        if (code <= 77 || code == 85 || code == 86) return "snow";
        return "storm";
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

    /** Длительность с карточки обратно в секунды: 1:54 → 114, 1:02:03 → 3723; не число — 0. */
    static int clockSeconds(String c) {
        if (c == null || !c.matches("\\d+(:\\d{1,2}){1,2}")) return 0;
        int s = 0;
        for (String p : c.split(":")) s = s * 60 + Integer.parseInt(p);
        return s;
    }

    /** Длительность записи Twitch: 1h2m3s → 3723. */
    static int twitchSeconds(String d) {
        Matcher m = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matcher(d == null ? "" : d);
        if (d == null || d.isEmpty() || !m.matches()) return 0;
        int s = 0;
        for (int k = 1; k <= 3; k++) s = s * 60 + (m.group(k) == null ? 0 : Integer.parseInt(m.group(k)));
        return s;
    }

    /** Сколько смотрят эфир: 850 зрителей, 1,2 тыс. зрителей, 15 тыс. зрителей. */
    static String viewers(int n) {
        if (n >= 10_000) return n / 1000 + " тыс. зрителей";
        if (n >= 1000) return String.format(Locale.ROOT, "%.1f", n / 1000.0).replace('.', ',').replace(",0", "") + " тыс. зрителей";
        int t = n % 100, u = n % 10;
        return n + (t >= 11 && t <= 14 ? " зрителей" : u == 1 ? " зритель" : u >= 2 && u <= 4 ? " зрителя" : " зрителей");
    }

    static final long CONTINUE_FOR = 12 * 3600_000L, PEEK = 90_000L;
    static final int CONTINUE_FROM = 15 * 60;

    /** Держать ли карточку в «Продолжить». Лаунчер не видит, что смотрят внутри YouTube, поэтому судим по времени:
     *  открыто больше 12 часов назад — нет; короткое видео (меньше 15 минут) — досмотрено, нет; после открытия на главный
     *  экран вернулись через полторы минуты — передумали, нет; пропадали дольше самого видео (досмотрели или смотрели
     *  другое) — нет. away — сколько отсутствовали после открытия, -1 — ещё не возвращались. */
    static boolean keepContinue(long openedAt, long now, long away, int durationSec) {
        if (now - openedAt > CONTINUE_FOR || now < openedAt) return false;
        if (durationSec > 0 && durationSec < CONTINUE_FROM) return false;
        if (away < 0) return true;
        if (away < PEEK) return false;
        long limit = durationSec > 0 ? (long) (durationSec * 850L) : 3 * 3600_000L;
        return away < limit;
    }
}
