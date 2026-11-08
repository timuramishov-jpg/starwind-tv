package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Telegram на телевизоре: официальная библиотека TDLib (сборка Telegram X), свой процесс :tg.
 * Вход — по QR-коду, как на компьютере; ключ приложения (api_id, api_hash) и облачный пароль приходят с пульта.
 * Сеть — только через VPN: пропал tun0 — TDLib переводится в «нет сети» и ничего не отправляет мимо туннеля.
 */
final class TgCore implements Client.ResultHandler {
    private static final String TAG = "TgCore";
    private static TgCore instance;

    /** Что сейчас с Telegram: api — нет ключа, novpn — VPN выключен, wait — подключаемся, qr — ждём скан,
     *  password — нужен облачный пароль, ready — вошли, error — что-то не так (текст в hint). */
    volatile String state = "wait";
    volatile String qrLink = "", hint = "";

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<Integer, List<Runnable>> fileWaiters = new ConcurrentHashMap<>();
    private Client client;
    private boolean libs, vpnUp, closing, feedWanted;

    static synchronized TgCore get(Context c) {
        if (instance == null) instance = new TgCore(c.getApplicationContext());
        return instance;
    }

    private TgCore(Context c) {
        context = c;
        prefs = c.getSharedPreferences("tg", Context.MODE_PRIVATE);
        watchVpn();
    }

    // ---------- Подписка экрана на перемены ----------

    void listen(Runnable r) { synchronized (listeners) { listeners.add(r); } }

    void unlisten(Runnable r) { synchronized (listeners) { listeners.remove(r); } }

    private void changed(String newState) {
        state = newState;
        writeStatus();
        main.post(() -> {
            List<Runnable> copy;
            synchronized (listeners) { copy = new ArrayList<>(listeners); }
            for (Runnable r : copy) r.run();
        });
    }

    /** Состояние для страницы пульта (её сервер читает файл, лаунчер ему не нужен). */
    private void writeStatus() {
        try {
            File d = new File(context.getExternalFilesDir(null), "remote");
            d.mkdirs();
            File tmp = new File(d, "tg.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(new JSONObject().put("state", state).put("hint", hint).put("hasKey", hasKey())
                        .put("at", System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(new File(d, "tg.json"));
        } catch (Exception e) {
            Log.w(TAG, "status: " + e);
        }
    }

    // ---------- Запуск ----------

    boolean hasKey() { return prefs.getInt("api_id", 0) != 0 && !prefs.getString("api_hash", "").isEmpty(); }

    /** Ключ приложения с my.telegram.org — присылает пульт. */
    void setKey(int id, String hash) {
        prefs.edit().putInt("api_id", id).putString("api_hash", hash.trim()).apply();
        hint = "";
        restart();
    }

    synchronized void start() {
        if (client != null) { writeStatus(); return; }
        if (!loadLibs()) { hint = "Библиотека Telegram не загрузилась"; changed("error"); return; }
        if (!hasKey()) { changed("api"); return; }
        try { Client.execute(new TdApi.SetLogVerbosityLevel(1)); } catch (Exception ignored) { }
        closing = false;
        changed("wait");
        client = Client.create(this, e -> Log.w(TAG, "update", e), e -> Log.w(TAG, "query", e));
        applyNetwork();
    }

    private synchronized void restart() {
        if (client != null) {
            closing = true;
            client.send(new TdApi.Close(), r -> { });
        } else start();
    }

    /** Обновить ленту для дока: сейчас, если вошли, или как только войдём. */
    void feed() {
        if ("ready".equals(state)) TgFeed.build(context, this);
        else { feedWanted = true; start(); }
    }

    void logOut() {
        Client c = client;
        if (c != null) c.send(new TdApi.LogOut(), r -> { });
    }

    void password(String p) {
        Client c = client;
        if (c == null) return;
        hint = "Проверяю пароль…";
        changed("password");
        c.send(new TdApi.CheckAuthenticationPassword(p), r -> {
            if (r instanceof TdApi.Error) {
                hint = "Неверный пароль, попробуй ещё раз";
                changed("password");
            }
        });
    }

    private boolean loadLibs() {
        if (libs) return true;
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("cryptox");
            System.loadLibrary("sslx");
            System.loadLibrary("tdjni");
            libs = true;
        } catch (Throwable t) {
            Log.e(TAG, "libs", t);
        }
        return libs;
    }

    // ---------- VPN: Telegram только через туннель ----------

    static boolean vpn() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (n.isUp() && (n.getName().startsWith("tun") || n.getName().startsWith("ppp"))) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private void watchVpn() {
        Thread t = new Thread(() -> {
            while (true) {
                boolean up = vpn();
                if (up != vpnUp) {
                    vpnUp = up;
                    applyNetwork();
                    if (!up && client != null) { hint = "VPN выключен — Telegram на паузе"; changed("novpn"); }
                    else if (up && "novpn".equals(state)) { hint = ""; changed("wait"); start(); refreshAuth(); }
                }
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            }
        }, "tg-vpn");
        t.setDaemon(true);
        vpnUp = vpn();
        t.start();
    }

    private void applyNetwork() {
        Client c = client;
        if (c == null) return;
        c.send(new TdApi.SetNetworkType(vpnUp ? new TdApi.NetworkTypeWiFi() : new TdApi.NetworkTypeNone()), r -> { });
    }

    private void refreshAuth() {
        Client c = client;
        if (c != null) c.send(new TdApi.GetAuthorizationState(), r -> { if (r instanceof TdApi.AuthorizationState) onAuth((TdApi.AuthorizationState) r); });
    }

    // ---------- Ответы TDLib ----------

    @Override public void onResult(TdApi.Object o) {
        if (o instanceof TdApi.UpdateAuthorizationState) onAuth(((TdApi.UpdateAuthorizationState) o).authorizationState);
        else if (o instanceof TdApi.UpdateFile) {
            List<Runnable> w = fileWaiters.get(((TdApi.UpdateFile) o).file.id);
            if (w != null) synchronized (w) { for (Runnable r : w) r.run(); }
        }
    }

    private void onAuth(TdApi.AuthorizationState a) {
        Client c = client;
        if (c == null) return;
        if (a instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            File db = new File(context.getFilesDir(), "tg");
            File files = new File(context.getCacheDir(), "tg");
            c.send(new TdApi.SetTdlibParameters(false, db.getPath(), files.getPath(), null, true, true, false, false,
                    prefs.getInt("api_id", 0), prefs.getString("api_hash", ""), "ru", "Starwind TV", "Android " + Build.VERSION.RELEASE,
                    "1.0"), r -> {
                if (r instanceof TdApi.Error) {
                    hint = "Ключ не подошёл: " + ((TdApi.Error) r).message;
                    changed("api");
                }
            });
        } else if (a instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            c.send(new TdApi.RequestQrCodeAuthentication(new long[0]), r -> {
                if (r instanceof TdApi.Error) {
                    String m = ((TdApi.Error) r).message;
                    hint = m.contains("API_ID") ? "Ключ не подошёл — проверь api_id и api_hash" : "Telegram: " + m;
                    changed(m.contains("API_ID") ? "api" : "error");
                }
            });
        } else if (a instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            qrLink = ((TdApi.AuthorizationStateWaitOtherDeviceConfirmation) a).link;
            hint = "";
            changed("qr");
        } else if (a instanceof TdApi.AuthorizationStateWaitPassword) {
            String h = ((TdApi.AuthorizationStateWaitPassword) a).passwordHint;
            hint = h == null || h.isEmpty() ? "" : "Подсказка: " + h;
            changed("password");
        } else if (a instanceof TdApi.AuthorizationStateReady) {
            hint = "";
            if (feedWanted) { feedWanted = false; TgFeed.build(context, this); }
            changed(vpnUp ? "ready" : "novpn");
            // Кэш видео не копим: больше 300 МБ — старое удаляется.
            c.send(new TdApi.OptimizeStorage(300L << 20, 3 * 24 * 3600, 0, 60, new TdApi.FileType[0], new long[0], new long[0], false, 0), r -> { });
        } else if (a instanceof TdApi.AuthorizationStateClosed) {
            synchronized (this) { client = null; }
            if (closing) { closing = false; start(); }
            else { hint = ""; changed("wait"); start(); }
        } else if (a instanceof TdApi.AuthorizationStateLoggingOut) {
            hint = "Выхожу из аккаунта…";
            changed("wait");
        }
    }

    // ---------- Запросы ----------

    interface Done { void on(TdApi.Object r); }

    /** Запрос с ответом в главном потоке. */
    void send(TdApi.Function<?> f, Done done) {
        Client c = client;
        if (c == null) { main.post(() -> done.on(new TdApi.Error(500, "Telegram не подключён"))); return; }
        c.send(f, r -> main.post(() -> done.on(r)));
    }

    /** Запрос с ожиданием ответа — только из фонового потока (плеер). */
    TdApi.Object call(TdApi.Function<?> f, long timeoutMs) {
        Client c = client;
        if (c == null) return new TdApi.Error(500, "Telegram не подключён");
        final TdApi.Object[] out = new TdApi.Object[1];
        CountDownLatch done = new CountDownLatch(1);
        c.send(f, r -> { out[0] = r; done.countDown(); });
        try { if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return new TdApi.Error(408, "timeout"); }
        catch (InterruptedException e) { return new TdApi.Error(408, "interrupted"); }
        return out[0];
    }

    /** Будить ожидающего, когда по файлу пришло обновление (докачался ещё кусок). */
    void watchFile(int id, Runnable r) {
        fileWaiters.computeIfAbsent(id, k -> new ArrayList<>());
        List<Runnable> w = fileWaiters.get(id);
        synchronized (w) { w.add(r); }
    }

    void unwatchFile(int id, Runnable r) {
        List<Runnable> w = fileWaiters.get(id);
        if (w != null) synchronized (w) { w.remove(r); }
    }
}
