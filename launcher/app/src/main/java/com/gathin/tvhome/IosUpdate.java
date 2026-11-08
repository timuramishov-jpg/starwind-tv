package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Обновление лаунчера без мака: берёт последний релиз из GitHub (timuramishov-jpg/tv-home),
 *  тег вида v42 — номер сборки. Новее установленного — скачивает и ставит через системный установщик,
 *  телевизор один раз спросит «Установить?». Обычный путь через мак (tv deploy) тоже работает. */
public class IosUpdate extends BroadcastReceiver {
    interface Sink { void done(String message); }

    static final String REPO = "timuramishov-jpg/tv-home";
    private static final String TAG = "IosUpdate";

    static int installed(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static void check(Context c, Sink sink) {
        Handler main = new Handler(Looper.getMainLooper());
        Context app = c.getApplicationContext();
        new Thread(() -> {
            String say;
            try {
                JSONObject r = new JSONObject(text("https://api.github.com/repos/" + REPO + "/releases/latest"));
                int remote = Integer.parseInt(r.getString("tag_name").replaceAll("\\D", ""));
                int mine = installed(app);
                String apk = null;
                JSONArray assets = r.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    String name = assets.getJSONObject(i).optString("name");
                    if (name.endsWith(".apk")) apk = assets.getJSONObject(i).getString("browser_download_url");
                }
                if (remote <= mine || apk == null) say = "Стоит последняя версия (" + mine + ")";
                else {
                    install(app, download(apk));
                    say = "Скачал версию " + remote + ", подтверди установку на экране";
                }
            } catch (java.io.FileNotFoundException e) {
                // В хранилище ещё нет ни одной сборки (GitHub отвечает 404).
                say = "На GitHub пока нет сборок — стоит версия " + installed(app);
            } catch (Exception e) {
                Log.w(TAG, "check: " + e);
                say = "Не получилось проверить обновление: нет связи с GitHub";
            }
            final String m = say;
            main.post(() -> sink.done(m));
        }, "update").start();
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private static String text(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void install(Context c, byte[] apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("launcher.apk", 0, apk.length)) {
                out.write(apk);
                s.fsync(out);
            }
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, IosUpdate.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000 /* FLAG_MUTABLE: установщик дописывает ответ */);
            s.commit(done.getIntentSender());
        }
    }

    /** Ответ установщика: попросил подтверждения — показываем его окно. */
    @Override public void onReceive(Context c, Intent i) {
        // Обновились: система остановила сервер пульта (его APK больше нет) — поднимаем из нового, не дожидаясь главного экрана.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) { new IosRemote(c).ensureShell(); return; }
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent ask = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (ask != null) c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(c, "Обновление не установилось: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
        }
    }
}
