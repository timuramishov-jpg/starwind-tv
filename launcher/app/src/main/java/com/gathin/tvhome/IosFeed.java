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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ленты сервисов: YouTube (подписки или популярное) и Рутуб. Только настоящие данные, без заглушек. */
final class IosFeed {
    interface Sink { void done(List<IosHome.FeedItem> items, String error); }
    interface CodeSink { void code(String code, String url); void signedIn(); void failed(String error); }

    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private static final long FRESH_MS = 10 * 60 * 1000L, RETRY_MS = 20 * 1000L;
    private static final int MAX_ITEMS = 10, POOL = 36, TARGET_W = 880;
    private static final String TAG = "IosFeed";
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    static final String YOUTUBE = "com.yandex.tv.ytplayer", RUTUBE = "ru.rutube.app.tv", VK = "com.vk.tv", KINOPOISK = "ru.kinopoisk.yandex.tv", ZONA = "ru.zonaplayer.app";
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ExecutorService pictures = Executors.newFixedThreadPool(3);
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String clientId, clientSecret, apiKey, kinoKey;
    private final Map<String, List<IosHome.FeedItem>> items = new HashMap<>(), pools = new HashMap<>();
    private final Map<String, Set<String>> shown = new HashMap<>();
    private String popularPage = "";
    private final Map<String, Long> loadedAt = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();
    private volatile boolean polling;

    IosFeed(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("ios_feed", Context.MODE_PRIVATE);
        dir = new File(context.getCacheDir(), "feed");
        dir.mkdirs();
        pool.execute(this::prune);
        keys();
    }

    /** Ключи лежат в настройках приложения, их туда кладёт команда tv keys. */
    void keys() {
        clientId = IosConfig.get(context, "yt_client_id");
        clientSecret = IosConfig.get(context, "yt_client_secret");
        apiKey = IosConfig.get(context, "yt_api_key");
        kinoKey = IosConfig.get(context, "kino_key");
    }

    boolean signedIn() { return prefs.contains("refresh"); }
    static boolean supports(String pkg) { return YOUTUBE.equals(pkg) || RUTUBE.equals(pkg) || VK.equals(pkg) || KINOPOISK.equals(pkg) || ZONA.equals(pkg); }
    List<IosHome.FeedItem> cached(String pkg) {
        List<IosHome.FeedItem> l = items.get(pkg);
        return l == null ? new ArrayList<>() : l;
    }
    boolean loaded(String pkg) { return loadedAt.containsKey(pkg); }
    String error(String pkg) { return errors.get(pkg); }
    void signOut() { prefs.edit().clear().apply(); forget(YOUTUBE); }
    /** Появился интернет или VPN: всё устарело, обновляем при следующем запросе. */
    void expire() { loadedAt.clear(); }
    /** Новая порция: сначала то, что ещё не показывали. Показанное запоминается между включениями. */
    void rotate(String pkg) {
        List<IosHome.FeedItem> pool = pools.get(pkg);
        if (pool == null || pool.isEmpty()) return;
        markShown(pkg, cached(pkg));
        for (IosHome.FeedItem it : cached(pkg)) { it.image = null; it.onImage = null; }
        window(pkg);
        Log.i(TAG, "rotate " + pkg + " -> " + (cached(pkg).isEmpty() ? "" : cached(pkg).get(0).title));
    }

    private Set<String> shown(String pkg) {
        Set<String> s = shown.get(pkg);
        if (s == null) {
            s = new LinkedHashSet<>(Arrays.asList(prefs.getString("shown_" + pkg, "").split("\n")));
            s.remove("");
            shown.put(pkg, s);
        }
        return s;
    }

    private void markShown(String pkg, List<IosHome.FeedItem> list) {
        Set<String> s = shown(pkg);
        for (IosHome.FeedItem it : list) s.add(it.title);
        while (s.size() > 300) s.remove(s.iterator().next());
        prefs.edit().putString("shown_" + pkg, TextUtils.join("\n", s)).apply();
    }

    private void window(String pkg) {
        List<IosHome.FeedItem> pool = pools.get(pkg);
        Set<String> s = shown(pkg);
        List<IosHome.FeedItem> fresh = new ArrayList<>();
        for (IosHome.FeedItem it : pool) if (!s.contains(it.title) && fresh.size() < MAX_ITEMS) fresh.add(it);
        if (fresh.size() < Math.min(MAX_ITEMS, pool.size())) {
            for (IosHome.FeedItem it : pool) s.remove(it.title);
            fresh.clear();
            List<IosHome.FeedItem> mixed = new ArrayList<>(pool);
            Collections.shuffle(mixed);
            fresh.addAll(mixed.subList(0, Math.min(MAX_ITEMS, mixed.size())));
        }
        items.put(pkg, fresh);
    }

    /** Свежий список с сервера: на экран идут невиденные ролики, уже показанные отодвигаются. */
    private void publish(String pkg, List<IosHome.FeedItem> list) {
        pools.put(pkg, list);
        window(pkg);
    }

    void forget(String pkg) { pools.remove(pkg); items.remove(pkg); loadedAt.remove(pkg); errors.remove(pkg); }
    void stopPolling() { polling = false; }

    private static String form(String... kv) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) b.append('&');
            b.append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return b.toString();
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static JSONObject call(String url, String post, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (post != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) { o.write(post.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = in == null ? "{}" : new String(read(in), StandardCharsets.UTF_8);
        JSONObject j = s.trim().startsWith("[") ? new JSONObject().put("results", new JSONArray(s)) : new JSONObject(s);
        j.put("_status", code);
        return j;
    }

    void startSignIn(CodeSink sink) {
        polling = true;
        pool.execute(() -> {
            try {
                JSONObject d = call("https://oauth2.googleapis.com/device/code",
                        form("client_id", clientId, "scope", SCOPE), null);
                if (!d.has("device_code")) throw new Exception(d.optString("error", "нет кода"));
                final String code = d.getString("user_code"), url = d.getString("verification_url");
                main.post(() -> sink.code(code, url));
                long interval = d.optLong("interval", 5) * 1000, end = System.currentTimeMillis() + d.optLong("expires_in", 1800) * 1000;
                while (polling && System.currentTimeMillis() < end) {
                    Thread.sleep(interval);
                    JSONObject t = call("https://oauth2.googleapis.com/token", form("client_id", clientId,
                            "client_secret", clientSecret, "device_code", d.getString("device_code"),
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code"), null);
                    if (t.has("refresh_token")) {
                        prefs.edit().putString("refresh", t.getString("refresh_token")).apply();
                        main.post(sink::signedIn);
                        return;
                    }
                    String e = t.optString("error");
                    if ("slow_down".equals(e)) interval += 5000;
                    else if (!"authorization_pending".equals(e)) throw new Exception(e);
                }
            } catch (Exception e) {
                main.post(() -> sink.failed(String.valueOf(e.getMessage())));
            }
        });
    }

    private String accessToken() throws Exception {
        JSONObject t = call("https://oauth2.googleapis.com/token", form("client_id", clientId, "client_secret", clientSecret,
                "refresh_token", prefs.getString("refresh", ""), "grant_type", "refresh_token"), null);
        if (!t.has("access_token")) {
            if ("invalid_grant".equals(t.optString("error"))) prefs.edit().remove("refresh").apply();
            throw new Exception("Нужно войти заново");
        }
        return t.getString("access_token");
    }

    private void prune() {
        File[] files = dir.listFiles();
        long old = System.currentTimeMillis() - 4 * 24 * 3600 * 1000L;
        if (files != null) for (File f : files) if (f.lastModified() < old) f.delete();
    }

    private static Bitmap decode(byte[] b) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= TARGET_W) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    private Bitmap image(String url) {
        File cached = new File(dir, "i" + Integer.toHexString(url.hashCode()));
        try {
            if (cached.exists()) {
                try (FileInputStream in = new FileInputStream(cached)) {
                    Bitmap b = decode(read(in));
                    if (b != null) return b;
                }
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
            if (c.getResponseCode() >= 400) {
                Log.w(TAG, "image " + c.getResponseCode() + " " + url);
                return null;
            }
            byte[] b = read(c.getInputStream());
            Bitmap bmp = decode(b);
            if (bmp != null) try (FileOutputStream out = new FileOutputStream(cached)) { out.write(b); }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "image " + url + ": " + e);
            return null;
        }
    }

    private static String[] sizes(JSONObject thumbs) {
        List<String> out = new ArrayList<>();
        for (String k : new String[]{"standard", "high", "medium"}) {
            JSONObject o = thumbs.optJSONObject(k);
            if (o != null) out.add(o.optString("url"));
        }
        return out.toArray(new String[0]);
    }

    /** Список появляется сразу, обложки подъезжают по мере загрузки. */
    private void fetchPictures(List<IosHome.FeedItem> list, int limit) {
        for (int n = 0; n < list.size() && n < limit; n++) {
            IosHome.FeedItem it = list.get(n);
            if (it.image != null || it.urls == null || it.fetching) continue;
            it.fetching = true;
            pictures.execute(() -> {
                Bitmap got = null;
                for (int round = 0; round < 2 && got == null; round++) {
                    for (String url : it.urls) {
                        got = image(url);
                        if (got != null) break;
                    }
                }
                final Bitmap b = got;
                main.post(() -> {
                    it.fetching = false;
                    if (b == null) { if (it.onFail != null) it.onFail.run(); return; }
                    it.image = b;
                    if (it.onImage != null) it.onImage.run();
                });
            });
        }
    }

    /** Обложки выбранного сервиса грузятся все, остальные только первые. */
    void wantPictures(String pkg) { fetchPictures(cached(pkg), MAX_ITEMS); }

    private File listFile(String pkg) { return new File(dir, "list-" + pkg + ".json"); }

    private void save(String pkg, List<IosHome.FeedItem> list) {
        try {
            JSONArray a = new JSONArray();
            for (IosHome.FeedItem it : list) {
                JSONObject j = new JSONObject();
                j.put("title", it.title).put("subtitle", it.subtitle).put("poster", it.poster);
                j.put("open", it.open.toUri(Intent.URI_INTENT_SCHEME));
                j.put("urls", new JSONArray(Arrays.asList(it.urls == null ? new String[0] : it.urls)));
                a.put(j);
            }
            try (FileOutputStream out = new FileOutputStream(listFile(pkg))) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) {
            Log.w(TAG, "save " + pkg + ": " + e);
        }
    }

    private List<IosHome.FeedItem> restore(String pkg) {
        List<IosHome.FeedItem> out = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(listFile(pkg))) {
            JSONArray a = new JSONArray(new String(read(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                IosHome.FeedItem it = new IosHome.FeedItem();
                it.title = j.getString("title");
                it.subtitle = j.optString("subtitle");
                it.poster = j.optBoolean("poster");
                it.open = Intent.parseUri(j.getString("open"), Intent.URI_INTENT_SCHEME);
                JSONArray u = j.getJSONArray("urls");
                it.urls = new String[u.length()];
                for (int k = 0; k < u.length(); k++) it.urls[k] = u.getString(k);
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    interface ExitSink { void done(String iso, String city); }
    interface ProbeSink { void done(int avg, int spread, int lost); }
    private long probeAt;

    void resetProbe() { probeAt = 0; }

    /** Освобождает память лаунчера: обложки невидимых лент загрузятся заново с диска. */
    void trim() {
        for (List<IosHome.FeedItem> pool : pools.values()) for (IosHome.FeedItem it : pool) { it.image = null; it.onImage = null; }
    }

    void probe(ProbeSink sink) {
        long now = SystemClock.elapsedRealtime();
        if (probeAt != 0 && now - probeAt < 30000) return;
        probeAt = now;
        pool.execute(() -> {
            List<Long> times = new ArrayList<>();
            int lost = 0;
            for (int i = 0; i < 5; i++) {
                long t0 = SystemClock.elapsedRealtime();
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://www.gstatic.com/generate_204").openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    c.setUseCaches(false);
                    if (c.getResponseCode() == 204) times.add(SystemClock.elapsedRealtime() - t0); else lost++;
                    c.disconnect();
                } catch (Exception e) {
                    lost++;
                }
            }
            if (times.size() > 1) times.remove(0);
            long sum = 0, min = Long.MAX_VALUE, max = 0;
            for (long t : times) { sum += t; min = Math.min(min, t); max = Math.max(max, t); }
            final int avg = times.isEmpty() ? -1 : (int) (sum / times.size()), spread = times.isEmpty() ? 0 : (int) (max - min), miss = lost;
            Log.i(TAG, "probe " + avg + " ms, spread " + spread + ", lost " + lost);
            main.post(() -> sink.done(avg, spread, miss));
        });
    }
    private long exitAt;

    void exit(ExitSink sink) {
        long now = SystemClock.elapsedRealtime();
        if (exitAt != 0 && now - exitAt < 45000) return;
        exitAt = now;
        pool.execute(() -> {
            try {
                JSONObject j = call("https://ifconfig.co/json", null, null);
                String iso = j.optString("country_iso"), city = j.optString("city");
                Log.i(TAG, "exit " + iso + " " + city);
                if (iso.length() == 2) main.post(() -> sink.done(iso, city));
            } catch (Exception e) {
                Log.w(TAG, "exit: " + e);
            }
        });
    }

    void load(String pkg, boolean force, Sink sink) {
        if (apiKey.isEmpty()) keys();
        if (loading.contains(pkg)) return;
        Long at = loadedAt.get(pkg);
        long age = at == null ? Long.MAX_VALUE : System.currentTimeMillis() - at;
        if (!force && age < (failed.contains(pkg) ? RETRY_MS : FRESH_MS)) {
            sink.done(cached(pkg), errors.get(pkg));
            return;
        }
        loading.add(pkg);
        final boolean empty = cached(pkg).isEmpty();
        pool.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            if (empty) {
                List<IosHome.FeedItem> saved = restore(pkg);
                if (!saved.isEmpty()) main.post(() -> {
                    if (!cached(pkg).isEmpty()) return;
                    publish(pkg, saved);
                    fetchPictures(cached(pkg), 3);
                    sink.done(cached(pkg), null);
                });
            }
            List<IosHome.FeedItem> out = null;
            String error = null;
            try {
                out = YOUTUBE.equals(pkg) ? youtube() : RUTUBE.equals(pkg) ? rutube() : KINOPOISK.equals(pkg) ? posters("TOP_POPULAR_ALL", false) : ZONA.equals(pkg) ? zona() : channel(pkg);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
                Log.w(TAG, pkg + ": " + e);
            }
            final List<IosHome.FeedItem> got = out;
            final String err = error;
            main.post(() -> {
                loading.remove(pkg);
                loadedAt.put(pkg, System.currentTimeMillis());
                Log.i(TAG, pkg + " " + (SystemClock.elapsedRealtime() - started) + " ms, " + (got == null ? err : got.size() + " items"));
                if (got == null || got.isEmpty()) failed.add(pkg); else failed.remove(pkg);
                if (got != null && !got.isEmpty()) {
                    keepPictures(got, cached(pkg));
                    publish(pkg, got);
                    errors.remove(pkg);
                    fetchPictures(cached(pkg), 3);
                    pool.execute(() -> save(pkg, got));
                } else if (cached(pkg).isEmpty()) errors.put(pkg, err == null ? "Пусто" : err);
                sink.done(cached(pkg), err);
            });
        });
    }

    private List<IosHome.FeedItem> youtube() throws Exception {
        if (signedIn()) {
            try {
                return subscriptions();
            } catch (Exception e) {
                Log.w(TAG, "subscriptions: " + e);
            }
        }
        JSONObject r = call("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&chart=mostPopular&regionCode=RU&maxResults=50"
                + (popularPage.isEmpty() ? "" : "&pageToken=" + popularPage) + "&key=" + apiKey, null, null);
        popularPage = r.optString("nextPageToken", "");
        JSONArray list = r.optJSONArray("items");
        if (list == null) throw new Exception(r.has("error") ? r.getJSONObject("error").optString("message") : "Пустой ответ");
        List<IosHome.FeedItem> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.getJSONObject(i);
            IosHome.FeedItem it = youtubeItem(v.getJSONObject("snippet"), v.getString("id"));
            it.duration = iso(v.optJSONObject("contentDetails") == null ? "" : v.getJSONObject("contentDetails").optString("duration"));
            out.add(it);
        }
        Collections.shuffle(out);
        return out;
    }

    private static void keepPictures(List<IosHome.FeedItem> fresh, List<IosHome.FeedItem> old) {
        for (IosHome.FeedItem f : fresh) for (IosHome.FeedItem o : old) {
            if (f.title.equals(o.title) && o.image != null) { f.image = o.image; break; }
        }
    }

    /** PT1H2M3S в 1:02:03. */
    static String iso(String d) {
        java.util.regex.Matcher m = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(d == null ? "" : d);
        if (!m.matches()) return "";
        int hh = m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                ss = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return clock(hh * 3600 + mm * 60 + ss);
    }

    static String clock(int s) {
        if (s <= 0) return "";
        return s >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private IosHome.FeedItem youtubeItem(JSONObject s, String id) throws Exception {
        IosHome.FeedItem it = new IosHome.FeedItem();
        it.title = s.optString("title");
        it.subtitle = s.optString("channelTitle");
        it.open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id))
                .setPackage(YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.urls = sizes(s.getJSONObject("thumbnails"));
        return it;
    }

    private List<IosHome.FeedItem> subscriptions() throws Exception {
        String token = accessToken();
        JSONObject subs = call("https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&order=relevance&maxResults=50", null, token);
        JSONArray list = subs.optJSONArray("items");
        if (list == null) throw new Exception(subs.has("error") ? subs.getJSONObject("error").optString("message") : "Нет подписок");
        List<Callable<JSONObject>> jobs = new ArrayList<>();
        List<Integer> picks = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) picks.add(i);
        Collections.shuffle(picks);
        for (int i : picks.subList(0, Math.min(16, picks.size()))) {
            String channel = list.getJSONObject(i).getJSONObject("snippet").getJSONObject("resourceId").getString("channelId");
            jobs.add(() -> call("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=3&playlistId=UU" + channel.substring(2), null, token));
        }
        List<Object[]> found = new ArrayList<>();
        for (Future<JSONObject> done : requests.invokeAll(jobs)) {
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
        for (Object[] f : found) {
            if (out.size() >= POOL) break;
            JSONObject s = (JSONObject) f[1];
            out.add(youtubeItem(s, s.getJSONObject("resourceId").getString("videoId")));
        }
        try {
            StringBuilder ids = new StringBuilder();
            for (IosHome.FeedItem it : out) ids.append(ids.length() == 0 ? "" : ",").append(Uri.parse(Uri.decode(it.open.getDataString())).getQueryParameter("v"));
            JSONArray d = call("https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=" + ids + "&key=" + apiKey, null, null).optJSONArray("items");
            Map<String, String> len = new HashMap<>();
            for (int i = 0; d != null && i < d.length(); i++) len.put(d.getJSONObject(i).getString("id"), iso(d.getJSONObject(i).getJSONObject("contentDetails").optString("duration")));
            for (IosHome.FeedItem it : out) it.duration = len.get(it.open.getData().getQueryParameter("v"));
        } catch (Exception e) {
            Log.w(TAG, "durations: " + e);
        }
        return out;
    }

    /** ВК и Кинопоиск сами публикуют каналы для главного экрана телевизора: читаем их. */
    private List<IosHome.FeedItem> channel(String pkg) throws Exception {
        context.sendBroadcast(new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(pkg));
        for (int attempt = 0; attempt < 5; attempt++) {
            List<IosHome.FeedItem> out = previews(pkg);
            if (!out.isEmpty()) return out;
            Thread.sleep(1500);
        }
        throw new Exception("Приложение не публикует ленту");
    }

    private List<IosHome.FeedItem> previews(String pkg) throws Exception {
        List<IosHome.FeedItem> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        String[] columns = {"package_name", "title", "short_description", "poster_art_uri", "thumbnail_uri", "intent_uri", "duration_millis"};
        try (Cursor c = context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String owner = c.getString(0);
                seen.merge(String.valueOf(owner), 1, Integer::sum);
                if (!pkg.equals(owner) || out.size() >= POOL) continue;
                IosHome.FeedItem it = new IosHome.FeedItem();
                it.title = c.getString(1);
                it.subtitle = c.getString(2) == null ? "" : c.getString(2);
                List<String> urls = new ArrayList<>();
                for (int i = 3; i <= 4; i++) if (c.getString(i) != null && c.getString(i).startsWith("http")) urls.add(c.getString(i));
                it.urls = urls.toArray(new String[0]);
                String link = c.getString(5);
                if (it.title == null || link == null) continue;
                it.open = Intent.parseUri(link, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                it.duration = clock((int) (c.getLong(6) / 1000));
                out.add(it);
            }
        }
        Log.i(TAG, "previews for " + pkg + ": " + seen);
        Collections.shuffle(out);
        return out;
    }

        private List<IosHome.FeedItem> zona() throws Exception {
        try {
            return posters("POPULAR_SERIES", true);
        } catch (Exception e) {
            return posters("TOP_POPULAR_MOVIES", true);
        }
    }

    private List<IosHome.FeedItem> posters(String collection, boolean inZona) throws Exception {
        if (kinoKey.isEmpty()) throw new Exception("Нет ключа");
        HttpURLConnection c = (HttpURLConnection) new URL("https://kinopoiskapiunofficial.tech/api/v2.2/films/collections?type=" + collection + "&page=" + (1 + new Random().nextInt(3))).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("X-API-KEY", kinoKey);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        Log.i(TAG, collection + " " + code);
        if (code >= 400) throw new Exception("Каталог ответил " + code);
        JSONArray list = new JSONObject(new String(read(c.getInputStream()), StandardCharsets.UTF_8)).getJSONArray("items");
        List<IosHome.FeedItem> out = new ArrayList<>();
        for (int i = 0; i < list.length() && out.size() < POOL; i++) {
            JSONObject f = list.getJSONObject(i);
            IosHome.FeedItem it = new IosHome.FeedItem();
            it.title = f.optString("nameRu", f.optString("nameOriginal"));
            if (it.title.isEmpty() || "null".equals(it.title)) it.title = f.optString("nameOriginal");
            String rating = f.optString("ratingKinopoisk"), year = f.optString("year");
            it.subtitle = (year.equals("null") ? "" : year) + (rating.isEmpty() || rating.equals("null") ? "" : ", рейтинг " + rating);
            it.poster = true;
            it.urls = new String[]{f.optString("posterUrlPreview"), f.optString("posterUrl")};
            // У Zona нет ссылок на отдельные фильмы, поэтому карточка открывает приложение.
            it.open = inZona ? new Intent(Intent.ACTION_MAIN).setClassName(ZONA, ZONA + ".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse("kpatv://film/" + f.optString("kinopoiskId"))).setPackage(KINOPOISK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(it);
        }
        if (out.isEmpty()) throw new Exception("Каталог пуст");
        Collections.shuffle(out);
        return out;
    }

    private List<IosHome.FeedItem> rutube() throws Exception {
        Map<String, JSONObject> videos = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(Arrays.asList(
                "https://rutube.ru/api/feeds/vendors/android_recommendation?format=json",
                "https://rutube.ru/pangolin/api/tv/feeds/movies-serials/?format=json",
                "https://rutube.ru/pangolin/api/tv/feeds/smart_category/?format=json"));
        Set<String> seen = new HashSet<>();
        for (int asked = 0; asked < 9 && !queue.isEmpty() && videos.size() < POOL; asked++) {
            String url = queue.poll();
            if (!seen.add(url)) { asked--; continue; }
            try {
                JSONObject r = call(url, null, null);
                Log.i(TAG, "rutube " + r.optInt("_status") + " " + url);
                walk(r, videos, queue);
            } catch (Exception e) {
                Log.w(TAG, "rutube " + url + ": " + e);
            }
        }
        if (videos.isEmpty()) throw new Exception("Рутуб не отдал видео");
        List<IosHome.FeedItem> out = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : videos.entrySet()) {
            if (out.size() >= POOL) break;
            JSONObject v = e.getValue();
            IosHome.FeedItem it = new IosHome.FeedItem();
            it.title = v.optString("title");
            JSONObject author = v.optJSONObject("author");
            it.subtitle = author == null ? "" : author.optString("name");
            it.open = new Intent(Intent.ACTION_VIEW, Uri.parse("rutube://rutube.ru/video/" + e.getKey() + "/"))
                    .setPackage(RUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String thumb = v.optString("thumbnail_url").replaceAll("\\?.*$", "");
            it.urls = new String[]{thumb + "?size=l", thumb};
            it.duration = clock(v.optInt("duration"));
            out.add(it);
        }
        Collections.shuffle(out);
        return out;
    }

    /** Ответы Рутуба бывают разной формы: ищем ролики и ссылки на источники на любой глубине. */
    private void walk(Object o, Map<String, JSONObject> videos, Deque<String> queue) {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), videos, queue);
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String id = j.optString("id"), thumb = j.optString("thumbnail_url"), title = j.optString("title");
            if (id.length() != 32) {
                Matcher m = HEX32.matcher(j.optString("video_url"));
                id = m.find() ? m.group() : "";
            }
            if (!id.isEmpty() && !thumb.isEmpty() && !title.isEmpty() && !j.optBoolean("is_adult")) {
                if (!videos.containsKey(id)) videos.put(id, j);
                return;
            }
            for (String k : new String[]{"url", "feed_url", "source_url"}) {
                String u = j.optString(k);
                if (!u.contains("/api/")) continue;
                if (u.startsWith("/")) u = "https://rutube.ru" + u;
                u = u.replace("http://", "https://");
                if (u.startsWith("https://rutube.ru/")) queue.add(u + (u.contains("?") ? "&" : "?") + "format=json");
            }
            Iterator<String> keys = j.keys();
            while (keys.hasNext()) walk(j.opt(keys.next()), videos, queue);
        }
    }
}
