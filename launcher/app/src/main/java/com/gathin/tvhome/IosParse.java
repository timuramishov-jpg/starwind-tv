package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор ответов YouTube, Рутуба и Кинопоиска без Android: проверяется тестами на образцах ответов. */
final class IosParse {
    /** Одна позиция ленты, как её отдал сервис. */
    static final class Entry {
        String id = "", title = "", sub = "", duration = "", year = "", rating = "";
        String[] urls = new String[0];
        long published;
    }

    static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");

    private IosParse() { }

    /** Строка или пусто: JSON null и строка "null" — тоже пусто. */
    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        String s = o.optString(key, "");
        return "null".equals(s) ? "" : s;
    }

    static String[] sizes(JSONObject thumbs) {
        List<String> out = new ArrayList<>();
        if (thumbs == null) return new String[0];
        for (String k : new String[]{"standard", "high", "medium"}) {
            JSONObject o = thumbs.optJSONObject(k);
            if (o != null && !str(o, "url").isEmpty()) out.add(str(o, "url"));
        }
        return out.toArray(new String[0]);
    }

    static Entry youtube(JSONObject snippet, String id) {
        Entry e = new Entry();
        e.id = id;
        e.title = str(snippet, "title");
        e.sub = str(snippet, "channelTitle");
        e.urls = sizes(snippet == null ? null : snippet.optJSONObject("thumbnails"));
        e.published = IosText.when(str(snippet, "publishedAt"));
        return e;
    }

    /** videos.list: id строкой, длительность в contentDetails. Нет items — ошибка с текстом Google. */
    static List<Entry> youtubeVideos(JSONObject r) throws Exception {
        JSONArray list = r.optJSONArray("items");
        if (list == null) throw new Exception(error(r));
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null || str(v, "id").isEmpty() || v.optJSONObject("snippet") == null) continue;
            Entry e = youtube(v.optJSONObject("snippet"), str(v, "id"));
            JSONObject cd = v.optJSONObject("contentDetails");
            e.duration = cd == null ? "" : str(cd, "duration");
            out.add(e);
        }
        return out;
    }

    /** search.list и playlistItems: id лежит глубже. */
    static List<Entry> youtubeSearch(JSONObject r) throws Exception {
        JSONArray list = r.optJSONArray("items");
        if (list == null) throw new Exception(error(r));
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            JSONObject s = v == null ? null : v.optJSONObject("snippet");
            if (s == null) continue;
            JSONObject idBox = v.optJSONObject("id");
            String id = idBox != null ? str(idBox, "videoId") : "";
            if (id.isEmpty() && s.optJSONObject("resourceId") != null) id = str(s.optJSONObject("resourceId"), "videoId");
            if (id.isEmpty()) continue;
            out.add(youtube(s, id));
        }
        return out;
    }

    static String error(JSONObject r) {
        JSONObject e = r.optJSONObject("error");
        String m = e == null ? "" : str(e, "message");
        return m.isEmpty() ? "Пустой ответ" : m;
    }

    /** Ролик Рутуба из любого места ответа. */
    static Entry rutube(String id, JSONObject v) {
        Entry e = new Entry();
        e.id = id;
        e.title = str(v, "title");
        JSONObject author = v.optJSONObject("author");
        e.sub = author == null ? "" : str(author, "name");
        String thumb = str(v, "thumbnail_url").replaceAll("\\?.*$", "");
        e.urls = thumb.isEmpty() ? new String[0] : new String[]{thumb + "?size=l", thumb};
        int d = v.optInt("duration");
        e.duration = d > 0 ? IosText.clock(d) : "";
        return e;
    }

    /** Ответы Рутуба бывают разной формы: ищем ролики и ссылки на источники на любой глубине. */
    static void walk(Object o, Map<String, JSONObject> videos, Deque<String> queue) {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), videos, queue);
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String id = str(j, "id"), thumb = str(j, "thumbnail_url"), title = str(j, "title");
            if (id.length() != 32) {
                Matcher m = HEX32.matcher(str(j, "video_url"));
                id = m.find() ? m.group() : "";
            }
            if (!id.isEmpty() && !thumb.isEmpty() && !title.isEmpty() && !j.optBoolean("is_adult")) {
                if (!videos.containsKey(id)) videos.put(id, j);
                return;
            }
            for (String k : new String[]{"url", "feed_url", "source_url"}) {
                String u = str(j, k);
                if (!u.contains("/api/")) continue;
                if (u.startsWith("/")) u = "https://rutube.ru" + u;
                u = u.replace("http://", "https://");
                if (u.startsWith("https://rutube.ru/")) queue.add(u + (u.contains("?") ? "&" : "?") + "format=json");
            }
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) walk(j.opt(keys.next()), videos, queue);
        }
    }

    /** Фильмы Кинопоиска: подборки (items) и поиск (films). Без названия — пропускаем. */
    static List<Entry> kino(JSONArray films, int max) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; films != null && i < films.length() && out.size() < max; i++) {
            JSONObject f = films.optJSONObject(i);
            if (f == null) continue;
            Entry e = new Entry();
            e.title = str(f, "nameRu");
            if (e.title.isEmpty()) e.title = str(f, "nameOriginal");
            if (e.title.isEmpty()) e.title = str(f, "nameEn");
            if (e.title.isEmpty()) continue;
            e.id = str(f, "kinopoiskId").isEmpty() ? str(f, "filmId") : str(f, "kinopoiskId");
            e.year = str(f, "year");
            e.rating = str(f, "ratingKinopoisk");
            if (e.rating.isEmpty()) e.rating = str(f, "rating");
            List<String> u = new ArrayList<>();
            for (String k : new String[]{"posterUrlPreview", "posterUrl"}) if (!str(f, k).isEmpty()) u.add(str(f, k));
            e.urls = u.toArray(new String[0]);
            out.add(e);
        }
        return out;
    }
}
