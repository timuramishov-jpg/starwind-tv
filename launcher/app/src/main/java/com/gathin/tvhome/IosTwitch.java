package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Лента Twitch по аккаунту: сначала каналы из подписок, которые сейчас в эфире, потом свежие записи их эфиров.
 * Вход — кодом на экране (Device Code Flow, как YouTube): twitch.tv/activate на телефоне. Нужен Client ID своего
 * приложения с dev.twitch.tv (тип «Public»); он приходит как остальные ключи (tv keys или пульт → Аккаунты → Twitch),
 * в APK его нет. Право одно — user:read:follows (читать подписки).
 */
final class IosTwitch {
    private static final String TAG = "IosTwitch", SCOPES = "user:read:follows";
    static final String NEED_KEY = "Нужен Client ID Twitch: пульт → Аккаунты → Twitch",
            NEED_LOGIN = "Войди в Twitch, чтобы видеть свои каналы";
    private final Context context;
    private final IosFeed f;
    final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean polling;

    IosTwitch(Context context, IosFeed f) {
        this.context = context;
        this.f = f;
        prefs = context.getSharedPreferences("ios_twitch", Context.MODE_PRIVATE);
    }

    String clientId() { return IosConfig.get(context, "twitch_client_id"); }
    boolean hasKey() { return !clientId().isEmpty(); }
    boolean signedIn() { return prefs.contains("refresh") && !prefs.getString("user", "").isEmpty(); }
    String login() { return prefs.getString("name", ""); }
    void signOut() { prefs.edit().clear().apply(); f.forget(IosFeed.TWITCH); }
    void stopPolling() { polling = false; }

    /** Вход по коду: показываем код, ждём, пока его введут на twitch.tv/activate. */
    void startSignIn(IosFeed.CodeSink sink) {
        polling = true;
        new Thread(() -> {
            try {
                if (!hasKey()) throw new Exception(NEED_KEY);
                JSONObject d = post("https://id.twitch.tv/oauth2/device", IosFeed.form("client_id", clientId(), "scopes", SCOPES));
                if (!d.has("device_code")) throw new Exception(IosParse.twitchError(d));
                String code = d.getString("user_code");
                main.post(() -> sink.code(code, "https://www.twitch.tv/activate"));
                long interval = Math.max(5, d.optLong("interval", 5)) * 1000, end = System.currentTimeMillis() + d.optLong("expires_in", 1800) * 1000;
                while (polling && System.currentTimeMillis() < end) {
                    Thread.sleep(interval);
                    JSONObject t = post("https://id.twitch.tv/oauth2/token", IosFeed.form("client_id", clientId(), "scopes", SCOPES,
                            "device_code", d.getString("device_code"), "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
                    if (t.has("access_token")) {
                        keep(t);
                        JSONObject me = helix("users").optJSONArray("data").getJSONObject(0);
                        prefs.edit().putString("user", me.getString("id")).putString("name", me.optString("display_name", me.optString("login"))).apply();
                        Log.i(TAG, "signed in");
                        main.post(sink::signedIn);
                        return;
                    }
                    String m = t.optString("message");
                    if ("slow_down".equals(m)) interval += 5000;
                    else if (!"authorization_pending".equals(m)) throw new Exception(m.isEmpty() ? "Twitch не принял вход" : m);
                }
            } catch (Exception e) {
                Log.w(TAG, "sign in: " + e);
                main.post(() -> sink.failed(String.valueOf(e.getMessage())));
            }
        }, "twitch-sign-in").start();
    }

    private void keep(JSONObject t) throws Exception {
        prefs.edit().putString("access", t.getString("access_token"))
                .putString("refresh", t.optString("refresh_token", prefs.getString("refresh", "")))
                .putLong("until", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000).apply();
    }

    /** Токен живёт ~4 часа; у публичного приложения обновляется без секрета. Отозвали вход — просим войти заново. */
    private synchronized String token(boolean force) throws Exception {
        if (!force && System.currentTimeMillis() < prefs.getLong("until", 0) - 60_000) return prefs.getString("access", "");
        JSONObject t = post("https://id.twitch.tv/oauth2/token", IosFeed.form("client_id", clientId(),
                "grant_type", "refresh_token", "refresh_token", prefs.getString("refresh", "")));
        if (!t.has("access_token")) {
            int status = t.optInt("_status");
            if (status == 400 || status == 401) prefs.edit().clear().apply();
            throw new Exception(status == 400 || status == 401 ? NEED_LOGIN : IosParse.twitchError(t));
        }
        keep(t);
        return t.getString("access_token");
    }

    private JSONObject helix(String path) throws Exception {
        JSONObject r = get("https://api.twitch.tv/helix/" + path, token(false));
        if (r.optInt("_status") == 401) r = get("https://api.twitch.tv/helix/" + path, token(true));
        return r;
    }

    /** Лента: эфиры подписок (по числу зрителей), затем записи эфиров за две недели (новое сверху). */
    List<IosHome.FeedItem> feed() throws Exception {
        if (!hasKey()) throw new Exception(NEED_KEY);
        if (!signedIn()) throw new Exception(NEED_LOGIN);
        String me = prefs.getString("user", "");
        List<IosParse.Entry> live = IosParse.twitchStreams(helix("streams/followed?user_id=" + me + "&first=50"));
        List<IosHome.FeedItem> out = new ArrayList<>();
        Set<String> onAir = new HashSet<>();
        long slot = System.currentTimeMillis() / 300_000;  // превью эфира меняется — новый адрес раз в 5 минут
        for (IosParse.Entry e : live) {
            onAir.add(e.login);
            IosHome.FeedItem it = item(e, "https://www.twitch.tv/" + e.login);
            it.subtitle = e.sub + " · " + IosText.viewers(e.viewers);
            it.duration = "в эфире";
            it.published = 0;  // без метки «новое»: эфир и так идёт сейчас
            if (it.urls != null && it.urls.length > 0) it.urls[0] += "?t=" + slot;
            out.add(it);
        }
        List<IosParse.Entry> vods = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<IosParse.Entry>>> jobs = new ArrayList<>();
            for (String id : followed()) {
                if (jobs.size() >= 20) break;
                jobs.add(() -> IosParse.twitchVideos(helix("videos?user_id=" + id + "&type=archive&first=2")));
            }
            for (Future<List<IosParse.Entry>> done : pool.invokeAll(jobs, 30, TimeUnit.SECONDS)) {
                try { vods.addAll(done.get()); } catch (Exception ignored) { }
            }
        } finally {
            pool.shutdownNow();
        }
        long fortnight = System.currentTimeMillis() - 14L * 24 * 3600_000L;
        Collections.sort(vods, (a, b) -> Long.compare(b.published, a.published));
        for (IosParse.Entry e : vods) {
            if (out.size() >= IosFeed.MAX_ITEMS) break;
            if (e.published < fortnight || onAir.contains(e.login)) continue;
            IosHome.FeedItem it = item(e, "https://www.twitch.tv/videos/" + e.id);
            it.duration = IosText.clock(IosText.twitchSeconds(e.duration));
            out.add(it);
        }
        Log.i(TAG, "feed: " + live.size() + " live, " + (out.size() - live.size()) + " videos");
        if (out.isEmpty()) throw new Exception("Сейчас никто из подписок не в эфире");
        return out;
    }

    /** Подписки (кого читаешь), свежие сверху; список меняется редко — раз в 6 часов. */
    private List<String> followed() throws Exception {
        String cached = prefs.getString("follows", "");
        if (!cached.isEmpty() && System.currentTimeMillis() - prefs.getLong("follows_at", 0) < 6 * 3600_000L)
            return Arrays.asList(cached.split(","));
        JSONObject r = helix("channels/followed?user_id=" + prefs.getString("user", "") + "&first=100");
        JSONArray d = r.optJSONArray("data");
        if (d == null) throw new Exception(IosParse.twitchError(r));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < d.length(); i++) ids.add(d.getJSONObject(i).optString("broadcaster_id"));
        prefs.edit().putString("follows", TextUtils.join(",", ids)).putLong("follows_at", System.currentTimeMillis()).apply();
        return ids;
    }

    private static IosHome.FeedItem item(IosParse.Entry e, String link) {
        IosHome.FeedItem it = new IosHome.FeedItem();
        it.title = e.title;
        it.subtitle = e.sub;
        it.urls = e.urls;
        it.published = e.published;
        // Открывает приложение Twitch; если оно не берёт ссылку, IosHome откроет само приложение.
        it.open = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(IosFeed.TWITCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return it;
    }

    private JSONObject get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Client-Id", clientId());
        c.setRequestProperty("Authorization", "Bearer " + token);
        return answer(c);
    }

    private static JSONObject post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
        return answer(c);
    }

    private static JSONObject answer(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String s = in == null ? "{}" : new String(IosFeed.read(in), StandardCharsets.UTF_8);
        JSONObject j = s.trim().startsWith("{") ? new JSONObject(s) : new JSONObject();
        return j.put("_status", code);
    }
}
