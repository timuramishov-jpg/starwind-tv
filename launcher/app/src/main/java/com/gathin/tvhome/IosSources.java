package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Источники лент: YouTube (вход по коду на телевизоре, подписки, популярное, без шортсов), ВК, Рутуб, Кинопоиск, Зона,
 *  и поиск по ним. Только сеть и разбор; кэш, обложки и ротация — в IosFeed. */
final class IosSources {
    private static final String TAG = "IosFeed";
    private final IosFeed f;

    IosSources(IosFeed f) { this.f = f; }

    String accessToken() throws Exception {
        JSONObject t = f.call("https://oauth2.googleapis.com/token", f.form("client_id", f.clientId, "client_secret", f.clientSecret,
                "refresh_token", f.prefs.getString("refresh", ""), "grant_type", "refresh_token"), null);
        if (!t.has("access_token")) {
            if ("invalid_grant".equals(t.optString("error"))) f.prefs.edit().remove("refresh").apply();
            throw new Exception("Нужно войти заново");
        }
        return t.getString("access_token");
    }
    List<IosHome.FeedItem> youtube() throws Exception {
        if (f.signedIn() && !"popular".equals(f.mode(IosFeed.YOUTUBE))) {
            try {
                return subscriptions();
            } catch (Exception e) {
                Log.w(TAG, "subscriptions: " + e);
            }
        }
        List<IosHome.FeedItem> out = new ArrayList<>();
        for (int round = 0; round < 3 && out.size() < IosFeed.MAX_ITEMS; round++) {
            JSONObject r = f.call("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&chart=mostPopular&regionCode=RU&maxResults=50"
                    + (f.popularPage.isEmpty() ? "" : "&pageToken=" + f.popularPage) + "&key=" + f.apiKey, null, null);
            f.popularPage = r.optString("nextPageToken", "");
            List<IosParse.Entry> list;
            try { list = IosParse.youtubeVideos(r); } catch (Exception e) { if (out.isEmpty()) throw e; break; }
            Map<String, String> len = new HashMap<>(), names = new HashMap<>();
            for (IosParse.Entry v : list) { len.put(v.id, v.duration); names.put(v.id, v.title); }
            Set<String> shorts = shortsAmong(len, names);
            for (IosParse.Entry v : list) if (!shorts.contains(v.id)) out.add(youtubeItem(v));
            if (f.popularPage.isEmpty()) break;
        }
        Collections.shuffle(out);
        return out;
    }
    /** Что из роликов шортсы — проверенное правило: всё до 3 минут и всё с меткой #shorts.
     *  Проверка через youtube.com/shorts/ID отвечала по-разному (через VPN приходили перенаправления) и пропускала шортсы. */
    Set<String> shortsAmong(Map<String, String> durations, Map<String, String> titles) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : durations.entrySet()) {
            int sec = f.seconds(e.getValue());
            if (f.taggedShort(titles.get(e.getKey())) || sec > 0 && sec <= 180) out.add(e.getKey());
        }
        // Старые ответы проверки больше не нужны.
        if (!f.prefs.getBoolean("shorts_v2", false)) {
            SharedPreferences.Editor clean = f.prefs.edit();
            for (String k : f.prefs.getAll().keySet()) if (k.startsWith("short_")) clean.remove(k);
            clean.putBoolean("shorts_v2", true).apply();
        }
        return out;
    }
    IosHome.FeedItem youtubeItem(JSONObject s, String id) throws Exception {
        return youtubeItem(IosParse.youtube(s, id));
    }
    IosHome.FeedItem youtubeItem(IosParse.Entry e) {
        IosHome.FeedItem it = new IosHome.FeedItem();
        it.title = e.title;
        it.subtitle = e.sub;
        it.open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + e.id))
                .setPackage(IosFeed.YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.urls = e.urls;
        it.published = e.published;
        if (!e.duration.isEmpty()) it.duration = f.iso(e.duration);
        return it;
    }
    List<IosHome.FeedItem> subscriptions() throws Exception {
        String token = accessToken();
        JSONObject subs = f.call("https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&order=relevance&maxResults=50", null, token);
        JSONArray list = subs.optJSONArray("items");
        if (list == null) throw new Exception(subs.has("error") ? subs.getJSONObject("error").optString("message") : "Нет подписок");
        List<Callable<JSONObject>> jobs = new ArrayList<>();
        List<Integer> picks = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) picks.add(i);
        Collections.shuffle(picks);
        for (int i : picks.subList(0, Math.min(25, picks.size()))) {
            String channel = list.getJSONObject(i).getJSONObject("snippet").getJSONObject("resourceId").getString("channelId");
            jobs.add(() -> f.call("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=4&playlistId=UU" + channel.substring(2), null, token));
        }
        List<Object[]> found = new ArrayList<>();
        for (Future<JSONObject> done : f.requests.invokeAll(jobs)) {
            JSONArray v;
            try { v = done.get().optJSONArray("items"); } catch (ExecutionException e) { continue; }
            for (int k = 0; v != null && k < v.length(); k++) {
                JSONObject s = v.getJSONObject(k).getJSONObject("snippet");
                found.add(new Object[]{s.optString("publishedAt"), s});
            }
        }
        if (found.isEmpty()) throw new Exception("Подписки пусты");
        Collections.sort(found, (a, b) -> ((String) b[0]).compareTo((String) a[0]));
        List<IosHome.FeedItem> out = new ArrayList<>();
        for (Object[] hit : found) {
            if (out.size() >= 50) break;
            JSONObject s = (JSONObject) hit[1];
            out.add(youtubeItem(s, s.getJSONObject("resourceId").getString("videoId")));
        }
        try {
            StringBuilder ids = new StringBuilder();
            for (IosHome.FeedItem it : out) ids.append(ids.length() == 0 ? "" : ",").append(Uri.parse(Uri.decode(it.open.getDataString())).getQueryParameter("v"));
            JSONArray d = f.call("https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=" + ids + "&key=" + f.apiKey, null, null).optJSONArray("items");
            Map<String, String> len = new HashMap<>(), names = new HashMap<>();
            for (int i = 0; d != null && i < d.length(); i++) len.put(d.getJSONObject(i).getString("id"), d.getJSONObject(i).getJSONObject("contentDetails").optString("duration"));
            for (IosHome.FeedItem it : out) names.put(it.open.getData().getQueryParameter("v"), it.title);
            Set<String> shorts = shortsAmong(len, names);
            List<IosHome.FeedItem> keep = new ArrayList<>();
            for (IosHome.FeedItem it : out) {
                String id = it.open.getData().getQueryParameter("v"), raw = len.get(id);
                if (shorts.contains(id)) continue;
                it.duration = f.iso(raw);
                keep.add(it);
            }
            out = keep;
        } catch (Exception e) {
            Log.w(TAG, "durations: " + e);
        }
        return out;
    }
    /** ВК и Кинопоиск сами публикуют каналы для главного экрана телевизора: читаем их. */
    List<IosHome.FeedItem> channel(String pkg) throws Exception {
        f.context.sendBroadcast(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(pkg));
        for (int attempt = 0; attempt < 5; attempt++) {
            List<IosHome.FeedItem> out = previews(pkg);
            if (!out.isEmpty()) return out;
            Thread.sleep(1500);
        }
        throw new Exception("Приложение не публикует ленту");
    }
    static String firstOf(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty()) return s.trim().split("\n")[0];
        return "";
    }
    static String views(long n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1f", n / 1e6).replace(".0", "").replace('.', ',') + " млн просмотров";
        if (n >= 1_000) return (n / 1000) + " тыс. просмотров";
        return n + " просмотров";
    }
    List<IosHome.FeedItem> previews(String pkg) throws Exception {
        List<IosHome.FeedItem> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        String[] columns = {"package_name", "title", "short_description", "poster_art_uri", "thumbnail_uri", "intent_uri", "duration_millis",
                "author", "long_description", "episode_title", "release_date", "interaction_count", "interaction_type", "channel_id"};
        // Названия рядов приложения («Популярное», «Для вас»): ими подписываем, если у видео нет автора.
        Map<Long, String> rows = new HashMap<>();
        try (Cursor c = f.context.getContentResolver().query(TvContract.Channels.CONTENT_URI, new String[]{"_id", "display_name", "package_name"}, null, null, null)) {
            while (c != null && c.moveToNext()) if (pkg.equals(c.getString(2)) && c.getString(1) != null) rows.put(c.getLong(0), c.getString(1));
        } catch (Exception e) {
            Log.i(TAG, "channels: " + e);
        }
        try (Cursor c = f.context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String owner = c.getString(0);
                seen.merge(String.valueOf(owner), 1, Integer::sum);
                if (!pkg.equals(owner) || out.size() >= IosFeed.POOL) continue;
                IosHome.FeedItem it = new IosHome.FeedItem();
                it.title = c.getString(1);
                it.subtitle = firstOf(c.getString(7), c.getString(9), c.getString(2), c.getString(8));
                if (it.subtitle.isEmpty() && c.getLong(11) > 0 && c.getInt(12) == TvContract.PreviewPrograms.INTERACTION_TYPE_VIEWS)
                    it.subtitle = views(c.getLong(11));
                // Название ряда («Главное») подписью не ставим: оно одинаковое у всех роликов и ничего не говорит.
                if (out.size() < 3) Log.i(TAG, "preview row " + pkg + ": intent=" + c.getString(5) + " author=" + c.getString(7) + " short=" + c.getString(2) + " long=" + c.getString(8)
                        + " episode=" + c.getString(9) + " date=" + c.getString(10) + " count=" + c.getLong(11) + "/" + c.getInt(12) + " rows=" + rows);
                List<String> urls = new ArrayList<>();
                for (int i = 3; i <= 4; i++) if (c.getString(i) != null && c.getString(i).startsWith("http")) urls.add(c.getString(i));
                it.urls = urls.toArray(new String[0]);
                String link = c.getString(5);
                if (it.title == null || link == null) continue;
                it.open = Intent.parseUri(link, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                it.duration = f.clock((int) (c.getLong(6) / 1000));
                out.add(it);
            }
        }
        Log.i(TAG, "previews for " + pkg + ": " + seen);
        Map<String, Integer> next = new HashMap<>();
        try (Cursor c = f.context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, new String[]{"package_name"}, null, null, null)) {
            while (c != null && c.moveToNext()) next.merge(String.valueOf(c.getString(0)), 1, Integer::sum);
        } catch (Exception e) {
            Log.i(TAG, "watch next: " + e);
        }
        Log.i(TAG, "watch next: " + next);
        Collections.shuffle(out);
        return out;
    }
    List<IosHome.FeedItem> searchYouTube(String q) throws Exception {
        List<IosHome.FeedItem> out = new ArrayList<>();
        JSONObject r = f.call("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=10&regionCode=RU&q="
                + Uri.encode(q) + "&key=" + f.apiKey, null, null);
        for (IosParse.Entry e : IosParse.youtubeSearch(r)) {
            IosHome.FeedItem it = youtubeItem(e);
            it.subtitle = "YouTube · " + it.subtitle;
            out.add(it);
        }
        return out;
    }
    List<IosHome.FeedItem> searchRutube(String q) throws Exception {
        List<IosHome.FeedItem> out = new ArrayList<>();
        JSONObject r = f.call("https://rutube.ru/api/search/video/?format=json&query=" + Uri.encode(q), null, null);
        JSONArray list = r.optJSONArray("results");
        for (int i = 0; list != null && i < list.length() && out.size() < 8; i++) {
            JSONObject v = list.optJSONObject(i);
            String id = IosParse.str(v, "id");
            if (v == null || id.isEmpty()) continue;
            IosHome.FeedItem it = rutubeItem(IosParse.rutube(id, v));
            it.subtitle = "Рутуб" + (it.subtitle.isEmpty() ? "" : " · " + it.subtitle);
            it.duration = null;
            out.add(it);
        }
        return out;
    }
    List<IosHome.FeedItem> searchKino(String q) throws Exception {
        List<IosHome.FeedItem> out = new ArrayList<>();
        if (f.kinoKey.isEmpty()) return out;
        HttpURLConnection c = (HttpURLConnection) new URL("https://kinopoiskapiunofficial.tech/api/v2.1/films/search-by-keyword?page=1&keyword=" + Uri.encode(q)).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("X-API-KEY", f.kinoKey);
        if (c.getResponseCode() >= 400) return out;
        JSONArray films = new JSONObject(new String(f.read(c.getInputStream()), StandardCharsets.UTF_8)).optJSONArray("films");
        for (IosParse.Entry film : IosParse.kino(films, 8)) {
            IosHome.FeedItem it = new IosHome.FeedItem();
            it.title = film.title;
            it.subtitle = "Кинопоиск" + (film.year.isEmpty() ? "" : " · " + film.year);
            it.poster = true;
            it.open = kinoHome();
            it.urls = film.urls;
            out.add(it);
        }
        return out;
    }
    /** Афиша открывает Кинопоиск так же, как иконка: телевизионная точка входа, задача поднимается целиком. */
    Intent kinoHome() {
        android.content.pm.PackageManager pm = f.context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(IosFeed.KINOPOISK);
        if (i == null) i = pm.getLaunchIntentForPackage(IosFeed.KINOPOISK);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
    /** Кинопоиск сам публикует ряды для главного экрана (из аккаунта, с переходом прямо в фильм).
     *  Пока он их не выложил, показываем афишу из каталога. */
    List<IosHome.FeedItem> kino() throws Exception {
        f.context.sendBroadcast(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(IosFeed.KINOPOISK));
        List<IosHome.FeedItem> own = previews(IosFeed.KINOPOISK);
        if (!own.isEmpty()) return own;
        return posters("TOP_POPULAR_ALL", false);
    }
    /** Рутуб сам публикует ряды для главного экрана: они открываются сразу в его приложении. */
    List<IosHome.FeedItem> rutubeOwn() throws Exception {
        f.context.sendBroadcast(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(IosFeed.RUTUBE));
        List<IosHome.FeedItem> own = previews(IosFeed.RUTUBE);
        if (own.size() >= 10) return own;
        return rutube();
    }
    List<IosHome.FeedItem> zona() throws Exception {
        try {
            return posters("POPULAR_SERIES", true);
        } catch (Exception e) {
            return posters("TOP_POPULAR_MOVIES", true);
        }
    }
    List<IosHome.FeedItem> posters(String collection, boolean inZona) throws Exception {
        if (f.kinoKey.isEmpty()) throw new Exception("Нет ключа");
        // Две страницы подборки подряд: сорок афиш.
        JSONArray list = new JSONArray();
        int first = 1 + new Random().nextInt(2);
        for (int page = first; page < first + 2; page++) {
            HttpURLConnection c = (HttpURLConnection) new URL("https://kinopoiskapiunofficial.tech/api/v2.2/films/collections?type=" + collection + "&page=" + page).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("X-API-KEY", f.kinoKey);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            Log.i(TAG, collection + " p" + page + " " + code);
            if (code >= 400) { if (list.length() == 0) throw new Exception("Каталог ответил " + code); break; }
            JSONArray got = new JSONObject(new String(f.read(c.getInputStream()), StandardCharsets.UTF_8)).getJSONArray("items");
            for (int k = 0; k < got.length(); k++) list.put(got.get(k));
        }
        List<IosHome.FeedItem> out = new ArrayList<>();
        for (IosParse.Entry film : IosParse.kino(list, IosFeed.POOL)) {
            IosHome.FeedItem it = new IosHome.FeedItem();
            it.title = film.title;
            it.subtitle = film.year + (film.rating.isEmpty() ? "" : (film.year.isEmpty() ? "" : ", ") + "рейтинг " + film.rating);
            it.poster = true;
            it.urls = film.urls;
            // У Zona нет ссылок на отдельные фильмы, поэтому карточка открывает приложение.
            it.open = inZona ? new Intent(Intent.ACTION_MAIN).setClassName(IosFeed.ZONA, IosFeed.ZONA + ".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    : kinoHome();

            out.add(it);
        }
        if (out.isEmpty()) throw new Exception("Каталог пуст");
        Collections.shuffle(out);
        return out;
    }
    List<IosHome.FeedItem> rutube() throws Exception {
        Map<String, JSONObject> videos = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(
                "https://rutube.ru/api/feeds/vendors/android_recommendation?format=json",
                "https://rutube.ru/pangolin/api/tv/feeds/movies-serials/?format=json",
                "https://rutube.ru/pangolin/api/tv/feeds/smart_category/?format=json"));
        Set<String> seen = new HashSet<>();
        for (int asked = 0; asked < 9 && !queue.isEmpty() && videos.size() < IosFeed.POOL; asked++) {
            String url = queue.poll();
            if (!seen.add(url)) { asked--; continue; }
            try {
                JSONObject r = f.call(url, null, null);
                Log.i(TAG, "rutube " + r.optInt("_status") + " " + url);
                f.walk(r, videos, queue);
            } catch (Exception e) {
                Log.w(TAG, "rutube " + url + ": " + e);
            }
        }
        if (videos.isEmpty()) throw new Exception("Рутуб не отдал видео");
        List<IosHome.FeedItem> out = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : videos.entrySet()) {
            if (out.size() >= IosFeed.POOL) break;
            out.add(rutubeItem(IosParse.rutube(e.getKey(), e.getValue())));
        }
        Collections.shuffle(out);
        return out;
    }
    IosHome.FeedItem rutubeItem(IosParse.Entry e) {
        IosHome.FeedItem it = new IosHome.FeedItem();
        it.title = e.title;
        it.subtitle = e.sub;
        it.open = new Intent(Intent.ACTION_VIEW, Uri.parse("rutube://rutube.ru/video/" + e.id + "/"))
                .setPackage(IosFeed.RUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.urls = e.urls;
        it.duration = e.duration.isEmpty() ? null : e.duration;
        return it;
    }
}
