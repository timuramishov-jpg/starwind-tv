package com.gathin.tvhome;

import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Разбор ответов сервисов на сохранённых образцах: если сервис поменяет формат, увидим здесь, а не пустую ленту. */
public final class ParseTest {
    private static int failed;

    private static void check(String what, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ок     " : "ОШИБКА ") + what + (ok ? "" : ": получили «" + got + "», ждали «" + want + "»"));
        if (!ok) failed++;
    }

    private static String text(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("starwind/test/samples/" + name)), "UTF-8");
    }

    private static JSONObject sample(String name) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get("starwind/test/samples/" + name)), "UTF-8"));
    }

    public static void main(String[] a) throws Exception {
        List<IosParse.Entry> rss = IosParse.youtubeRss(text("youtube-rss.xml"));
        check("YouTube RSS: битая запись пропущена", rss.size(), 2);
        check("YouTube RSS: id", rss.get(0).id, "abcDEF12345");
        check("YouTube RSS: название с &amp;", rss.get(0).title, "Большой выпуск: Tom & Jerry");
        check("YouTube RSS: канал", rss.get(0).sub, "Канал Тест");
        check("YouTube RSS: дата", rss.get(0).published > rss.get(1).published, true);
        check("YouTube RSS: обложка", rss.get(0).urls[0], "https://i.ytimg.com/vi/abcDEF12345/sddefault.jpg");
        List<IosParse.Entry> live = IosParse.twitchStreams(sample("twitch-streams.json"));
        check("Twitch эфиры: без логина пропущен", live.size(), 1);
        check("Twitch эфиры: логин", live.get(0).login, "streamer_one");
        check("Twitch эфиры: подпись", live.get(0).sub, "Streamer One · Fortnite");
        check("Twitch эфиры: зрители", live.get(0).viewers, 12345);
        check("Twitch эфиры: обложка в размер", live.get(0).urls[0], "https://static-cdn.jtvnw.net/previews-ttv/live_user_streamer_one-880x495.jpg");
        List<IosParse.Entry> vods = IosParse.twitchVideos(sample("twitch-videos.json"));
        check("Twitch записи: идущий эфир без обложки пропущен", vods.size(), 1);
        check("Twitch записи: длительность", IosText.clock(IosText.twitchSeconds(vods.get(0).duration)), "3:02:10");
        check("Twitch записи: обложка", vods.get(0).urls[0], "https://static-cdn.jtvnw.net/cf_vods/x/thumb/thumb0-880x495.jpg");
        String twErr;
        try { IosParse.twitchStreams(new JSONObject("{\"error\":\"Unauthorized\",\"status\":401,\"message\":\"Invalid OAuth token\"}")); twErr = "нет ошибки"; } catch (Exception e) { twErr = e.getMessage(); }
        check("Twitch: ошибка — текст Twitch", twErr, "Invalid OAuth token");
        List<IosParse.Entry> pop = IosParse.youtubeVideos(sample("youtube-popular.json"));
        check("YouTube: битый ролик пропущен", pop.size(), 2);
        check("YouTube: название", pop.get(0).title, "Обзор телевизора");
        check("HTML-сущности в названиях", IosParse.html("I&#39;ll be back &amp; Tom &quot;Jerry&quot; &#x41;"), "I'll be back & Tom \"Jerry\" A");
        check("YouTube: канал", pop.get(0).sub, "Канал Тест");
        check("YouTube: длительность", IosText.iso(pop.get(0).duration), "12:05");
        check("YouTube: лучшая обложка первой", pop.get(0).urls[0], "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg");
        check("YouTube: дата выхода", pop.get(0).published > 0, true);
        check("YouTube: шортс по тегу", IosText.taggedShort(pop.get(1).title), true);
        String err;
        try { IosParse.youtubeVideos(sample("youtube-error.json")); err = "нет ошибки"; } catch (Exception e) { err = e.getMessage(); }
        check("YouTube: квота — понятная ошибка", err.startsWith("The request cannot be completed"), true);
        List<IosParse.Entry> found = IosParse.youtubeSearch(sample("youtube-search.json"));
        check("Поиск YouTube: только видео", found.size(), 1);
        check("Поиск YouTube: id", found.get(0).id, "abcdefghijk");

        Map<String, JSONObject> videos = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        IosParse.walk(sample("rutube-feed.json"), videos, queue);
        check("Рутуб: ролики, без взрослых", videos.size(), 2);
        check("Рутуб: id из ссылки", videos.containsKey("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), true);
        check("Рутуб: вложенная лента в очереди", queue.peek(), "https://rutube.ru/api/feeds/tabs/123/?format=json");
        IosParse.Entry r = IosParse.rutube("0123456789abcdef0123456789abcdef", videos.get("0123456789abcdef0123456789abcdef"));
        check("Рутуб: автор", r.sub, "Студия");
        check("Рутуб: обложка большая", r.urls[0], "https://pic.rutubelist.ru/video/aa/bb.jpg?size=l");
        check("Рутуб: длительность", r.duration, "45:10");

        List<IosParse.Entry> kino = IosParse.kino(sample("kino-collection.json").getJSONArray("items"), 50);
        check("Кинопоиск: без названия пропущен", kino.size(), 2);
        check("Кинопоиск: название", kino.get(0).title, "Матрица");
        check("Кинопоиск: год", kino.get(0).year, "1999");
        check("Кинопоиск: рейтинг", kino.get(0).rating, "8.5");
        check("Кинопоиск: оригинальное название вместо null", kino.get(1).title, "Untitled Film");
        check("Кинопоиск: год null — пусто", kino.get(1).year, "");
        List<IosParse.Entry> kp = IosParse.kino(sample("kino-search.json").getJSONArray("films"), 8);
        check("Поиск Кинопоиска: фильм", kp.get(0).title + " " + kp.get(0).year, "Брат 1997");

        check("Фото без людей: место", IosText.hasPeople("Озеро Байкал, Россия"), false);
        check("Фото без людей: люди", IosText.hasPeople("Серферы на волне, Португалия"), true);
        check("Фото без людей: праздник", IosText.hasPeople("Праздник фонарей, Тайвань"), true);

        if (failed > 0) { System.out.println("НЕ ПРОШЛИ: " + failed); System.exit(1); }
        System.out.println("ВСЕ ТЕСТЫ РАЗБОРА ПРОШЛИ");
    }
}
