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
import android.util.LruCache;
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
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ленты сервисов: YouTube (подписки или популярное) и Рутуб. Только настоящие данные, без заглушек. */
final class IosFeed {
    interface Sink { void done(List<IosHome.FeedItem> items, String error); }
    interface CodeSink { void code(String code, String url); void signedIn(); void failed(String error); }

    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private static final long FRESH_MS = 10 * 60 * 1000L, RETRY_MS = 20 * 1000L;
    static final int MAX_ITEMS = 30;
    static final int POOL = 50, AHEAD = 6, TARGET_W = 880, POSTER_W = 330;
    private static final String TAG = "IosFeed";
    static final String YOUTUBE = "com.yandex.tv.ytplayer", RUTUBE = "ru.rutube.app.tv", VK = "com.vk.tv", KINOPOISK = "ru.kinopoisk.yandex.tv", ZONA = "ru.zonaplayer.app",
            /** Свой клиент Telegram — плитка в ряду приложений (без ленты и места в доке). */
            TELEGRAM = "com.gathin.tvhome.telegram";
    final IosSources src = new IosSources(this);
    final Context context;
    final SharedPreferences prefs;
    // Потоки фоновые и с низким приоритетом, чтобы не отнимать процессор у интерфейса на слабом телевизоре.
    private static final ThreadFactory QUIET = r -> {
        Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); });
        t.setDaemon(true);
        return t;
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(2, QUIET);
    private final ExecutorService pictures = Executors.newFixedThreadPool(2, QUIET);
    final ExecutorService requests = Executors.newFixedThreadPool(2, QUIET);
    /** Обложки в памяти с потолком: восьмая часть памяти приложения, остальное читается с диска. */
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    String clientId, clientSecret, apiKey, kinoKey;
    private final Map<String, List<IosHome.FeedItem>> items = new HashMap<>(), pools = new HashMap<>();
    private final Map<String, Set<String>> shown = new HashMap<>();
    String popularPage = "";
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
        // Первыми — то, что ты недавно открывал из этой ленты: «Продолжить».
        List<IosHome.FeedItem> recent = recent(pkg);
        if (!recent.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (IosHome.FeedItem r : recent) seen.add(r.title);
            List<IosHome.FeedItem> rest = new ArrayList<>();
            for (IosHome.FeedItem it : fresh) if (!seen.contains(it.title)) rest.add(it);
            recent.addAll(rest);
            fresh = recent;
        }
        items.put(pkg, fresh);
    }

    /** Открыл карточку — запоминаем её для ряда «Продолжить» (две последние на сервис). */
    void remember(String pkg, IosHome.FeedItem it) {
        if (it.open == null || it.title == null) return;
        try {
            JSONArray old = new JSONArray(prefs.getString("recent_" + pkg, "[]")), now = new JSONArray();
            JSONObject j = new JSONObject();
            j.put("title", it.title).put("subtitle", it.subtitle == null ? "" : it.subtitle).put("poster", it.poster)
                    .put("open", it.open.toUri(Intent.URI_INTENT_SCHEME)).put("at", System.currentTimeMillis())
                    .put("duration", it.duration == null ? "" : it.duration)
                    .put("urls", new JSONArray(Arrays.asList(it.urls == null ? new String[0] : it.urls)));
            now.put(j);
            for (int i = 0; i < old.length() && now.length() < 2; i++)
                if (!it.title.equals(old.getJSONObject(i).optString("title"))) now.put(old.getJSONObject(i));
            prefs.edit().putString("recent_" + pkg, now.toString()).apply();
            if (pools.containsKey(pkg)) window(pkg);
        } catch (Exception e) {
            Log.w(TAG, "remember: " + e);
        }
    }

    private List<IosHome.FeedItem> recent(String pkg) {
        List<IosHome.FeedItem> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent_" + pkg, "[]"));
            long week = System.currentTimeMillis() - 7 * 24 * 3600_000L;
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                if (j.optLong("at") < week) continue;
                IosHome.FeedItem it = new IosHome.FeedItem();
                it.title = j.getString("title");
                it.subtitle = "Продолжить · " + ago(j.optLong("at"));
                it.poster = j.optBoolean("poster");
                it.duration = j.optString("duration");
                it.open = Intent.parseUri(j.getString("open"), Intent.URI_INTENT_SCHEME);
                JSONArray u = j.getJSONArray("urls");
                it.urls = new String[u.length()];
                for (int k = 0; k < u.length(); k++) it.urls[k] = u.getString(k);
                for (List<IosHome.FeedItem> pool : pools.values())
                    for (IosHome.FeedItem p : pool) if (p.title.equals(it.title) && p.image != null) it.image = p.image;
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String ago(long at) { return IosText.ago(at, System.currentTimeMillis()); }

    /** Режим ленты сервиса: "" — как обычно, "popular" — популярное вместо подписок, "off" — ленты нет. */
    String mode(String pkg) { return prefs.getString("mode_" + pkg, ""); }

    void setMode(String pkg, String mode) {
        prefs.edit().putString("mode_" + pkg, mode).apply();
        forget(pkg);
    }

    /** Свежий список с сервера: на экран идут невиденные ролики, уже показанные отодвигаются. */
    private void publish(String pkg, List<IosHome.FeedItem> list) {
        long day = System.currentTimeMillis() - 24 * 3600_000L;
        for (IosHome.FeedItem it : list) it.fresh = it.published > day;
        pools.put(pkg, list);
        window(pkg);
    }

    void forget(String pkg) { pools.remove(pkg); items.remove(pkg); loadedAt.remove(pkg); errors.remove(pkg); }
    void stopPolling() { polling = false; }

    static String form(String... kv) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) b.append('&');
            b.append(kv[i]).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return b.toString();
    }

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }

    static JSONObject call(String url, String post, String token) throws Exception {
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


    private void prune() {
        File[] files = dir.listFiles();
        long old = System.currentTimeMillis() - 4 * 24 * 3600 * 1000L;
        if (files != null) for (File f : files) if (f.lastModified() < old) f.delete();
    }

    /** Обложка ровно под карточку: 16 бит на точку и не шире самой карточки (афиша 330, широкая 880). */
    static Bitmap decode(byte[] b, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap got = BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (got != null && got.getWidth() > target * 1.15f) {
            Bitmap fit = Bitmap.createScaledBitmap(got, target, Math.round(got.getHeight() * (float) target / got.getWidth()), true);
            if (fit != got) got.recycle();
            got = fit;
        }
        return got;
    }

    private Bitmap image(String url, int target) {
        Bitmap hit = memory.get(url);
        if (hit != null) return hit;
        Bitmap b = imageFromDiskOrNet(url, target);
        if (b != null) memory.put(url, b);
        return b;
    }

    private Bitmap imageFromDiskOrNet(String url, int target) {
        // Превью Telegram — уже на диске (их кладёт процесс :tg).
        if (url.startsWith("/")) {
            try (FileInputStream in = new FileInputStream(url)) { return decode(read(in), target); }
            catch (Exception e) { return null; }
        }
        File cached = new File(dir, "i" + Integer.toHexString(url.hashCode()));
        try {
            if (cached.exists()) {
                try (FileInputStream in = new FileInputStream(cached)) {
                    Bitmap b = decode(read(in), target);
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
            Bitmap bmp = decode(b, target);
            if (bmp != null) try (FileOutputStream out = new FileOutputStream(cached)) { out.write(b); }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "image " + url + ": " + e);
            return null;
        }
    }

    private static String[] sizes(JSONObject thumbs) { return IosParse.sizes(thumbs); }

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
                        got = image(url, it.poster ? POSTER_W : TARGET_W);
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
    void wantPictures(String pkg) { fetchPictures(cached(pkg), AHEAD); }

    /** Лента длинная: обложки держим только вокруг выбранной карточки. */
    void picturesAround(List<IosHome.FeedItem> list, int at) {
        int from = Math.max(0, at - 2), to = Math.min(list.size(), at + AHEAD + 1);
        if (from < to) fetchPictures(list.subList(from, to), to - from);
    }

    private File listFile(String pkg) { return new File(dir, "list-" + pkg + ".json"); }

    /** Для заставки: адреса обложек из сохранённой ленты сервиса (без сети). */
    static List<String> savedPictures(Context c, String pkg) {
        List<String> out = new ArrayList<>();
        try (FileInputStream in = new FileInputStream(new File(new File(c.getCacheDir(), "feed"), "list-" + pkg + ".json"))) {
            JSONArray a = new JSONArray(new String(read(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONArray u = a.getJSONObject(i).optJSONArray("urls");
                if (u != null && u.length() > 0) out.add(u.getString(0));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Обложка из дискового кэша лент, null — её там нет. */
    static Bitmap savedPicture(Context c, String url, int target) {
        File f = new File(new File(c.getCacheDir(), "feed"), "i" + Integer.toHexString(url.hashCode()));
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return decode(read(in), target);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Последнее открытое из всех лент: название, откуда и когда, адрес обложки. */
    static String[] lastOpened(Context c) {
        SharedPreferences p = c.getSharedPreferences("ios_feed", Context.MODE_PRIVATE);
        String[] best = null;
        long at = 0;
        for (String pkg : new String[]{YOUTUBE, VK, RUTUBE, KINOPOISK, ZONA}) {
            try {
                JSONArray a = new JSONArray(p.getString("recent_" + pkg, "[]"));
                if (a.length() == 0) continue;
                JSONObject j = a.getJSONObject(0);
                if (j.optLong("at") <= at) continue;
                at = j.optLong("at");
                JSONArray u = j.optJSONArray("urls");
                String name = pkg.equals(YOUTUBE) ? "YouTube" : pkg.equals(VK) ? "ВК Видео" : pkg.equals(RUTUBE) ? "Рутуб" : pkg.equals(KINOPOISK) ? "Кинопоиск" : "Зона";
                best = new String[]{j.optString("title"), name + " · " + IosText.ago(at, System.currentTimeMillis()), u != null && u.length() > 0 ? u.getString(0) : ""};
            } catch (Exception ignored) { }
        }
        return best;
    }

    private void save(String pkg, List<IosHome.FeedItem> list) {
        try {
            JSONArray a = new JSONArray();
            for (IosHome.FeedItem it : list) {
                JSONObject j = new JSONObject();
                j.put("title", it.title).put("subtitle", it.subtitle).put("poster", it.poster)
                        .put("duration", it.duration == null ? "" : it.duration).put("published", it.published);
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
                it.duration = j.optString("duration");
                it.published = j.optLong("published");
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
    interface SpeedSink { void done(float mbit); }
    private long downloadAt;

    void download(boolean force, SpeedSink sink) {
        long now = SystemClock.elapsedRealtime();
        if (!force && downloadAt != 0 && now - downloadAt < 600_000L) return;
        if (downloadAt != 0 && now - downloadAt < 20_000L) return;
        downloadAt = now;
        pool.execute(() -> {
            float mbit = -1;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://dl.google.com/android/repository/platform-tools-latest-darwin.zip").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Range", "bytes=0-4194303");
                long t0 = SystemClock.elapsedRealtime(), got = 0;
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0 && got < 4194304) got += n;
                }
                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0);
                mbit = got * 8f / ms / 1000f;
                Log.i(TAG, "download " + got + " bytes in " + ms + " ms");
            } catch (Exception e) {
                Log.w(TAG, "download: " + e);
            }
            final float m = mbit;
            main.post(() -> sink.done(m));
        });
    }
    private long probeAt;

    void resetProbe() { probeAt = 0; }

    /** Держит обложки только у видимых лент: выбранной и соседних. Остальные вернутся из кэша при показе. */
    void keepImages(Set<String> visible) {
        for (Map.Entry<String, List<IosHome.FeedItem>> e : pools.entrySet()) {
            if (visible.contains(e.getKey())) continue;
            for (IosHome.FeedItem it : e.getValue()) { it.image = null; it.onImage = null; }
        }
    }

    /** Освобождает память лаунчера: обложки невидимых лент загрузятся заново с диска. */
    void trim() {
        for (List<IosHome.FeedItem> pool : pools.values()) for (IosHome.FeedItem it : pool) { it.image = null; it.onImage = null; }
        memory.evictAll();
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
                out = YOUTUBE.equals(pkg) ? src.youtube() : RUTUBE.equals(pkg) ? src.rutubeOwn() : KINOPOISK.equals(pkg) ? src.kino() : ZONA.equals(pkg) ? src.zona() : src.channel(pkg);
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


    private static void keepPictures(List<IosHome.FeedItem> fresh, List<IosHome.FeedItem> old) {
        for (IosHome.FeedItem f : fresh) for (IosHome.FeedItem o : old) {
            if (f.title.equals(o.title) && o.image != null) { f.image = o.image; break; }
        }
    }

    static boolean taggedShort(String title) { return IosText.taggedShort(title); }
    static int seconds(String d) { return IosText.seconds(d); }


    static String iso(String d) { return IosText.iso(d); }
    static String clock(int s) { return IosText.clock(s); }



    static long when(String iso) { return IosText.when(iso); }






    interface SearchSink { void done(List<IosHome.FeedItem> found); }

    /** Общий поиск: YouTube, Рутуб и каталог Кинопоиска одновременно, сверху вниз в этом порядке. */
    void search(String q, SearchSink sink) {
        if (apiKey.isEmpty()) keys();
        pool.execute(() -> {
            List<Callable<List<IosHome.FeedItem>>> jobs = new ArrayList<>();
            jobs.add(() -> src.searchYouTube(q));
            jobs.add(() -> src.searchRutube(q));
            jobs.add(() -> src.searchKino(q));
            List<IosHome.FeedItem> all = new ArrayList<>();
            try {
                for (Future<List<IosHome.FeedItem>> f : requests.invokeAll(jobs)) {
                    try { all.addAll(f.get()); } catch (ExecutionException e) { Log.w(TAG, "search: " + e.getCause()); }
                }
            } catch (InterruptedException ignored) { }
            main.post(() -> sink.done(all));
        });
    }











    void walk(Object o, Map<String, JSONObject> videos, Deque<String> queue) { IosParse.walk(o, videos, queue); }
}
