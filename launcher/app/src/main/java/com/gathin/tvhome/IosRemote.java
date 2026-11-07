package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Пульт в телефоне со стороны лаунчера. Сам сервер — IosShell: он работает от имени оболочки телевизора и не
 * умирает, когда система выгружает лаунчер ради тяжёлого приложения. Лаунчер только запускает его и кладёт
 * для него в files/remote ключ, список приложений с логотипами, названия всех приложений и своё состояние.
 */
final class IosRemote {
    /** Одно приложение для страницы пульта. */
    static final class Entry {
        String pkg, label, component;
        int accent;
        Bitmap logo;
    }

    private static final String TAG = "IosRemote";
    private final String key;
    private final Context context;
    private volatile boolean checking;

    IosRemote(Context c) {
        context = c.getApplicationContext();
        android.content.SharedPreferences p = c.getSharedPreferences("ios_remote", 0);
        String k = p.getString("key", "");
        if (k.isEmpty()) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder();
            for (byte x : b) s.append(String.format("%02x", x));
            k = s.toString();
            p.edit().putString("key", k).apply();
        }
        key = k;
    }

    String key() { return key; }

    /** Адрес для QR-кода, null — телевизор не в сети. */
    String address() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + IosShell.PORT + "/?k=" + key;
    }

    private File dir() {
        File d = new File(context.getExternalFilesDir(null), "remote");
        d.mkdirs();
        return d;
    }

    /** Список приложений с логотипами и названия всех пакетов — для страницы пульта. В фоне. */
    void publish(List<Entry> apps, Map<String, String> labels) {
        new Thread(() -> {
            try {
                long updatedAt = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
                File d = dir(), logos = new File(d, "logos");
                logos.mkdirs();
                write(new File(d, "key"), key);
                JSONArray a = new JSONArray();
                for (Entry e : apps) {
                    File png = new File(logos, e.pkg + ".png");
                    // Логотипы перерисовываем после каждого обновления лаунчера: иначе в телефоне остаются старые цвета.
                    if (e.logo != null && (!png.exists() || png.lastModified() < updatedAt)) {
                        try (OutputStream o = new FileOutputStream(png)) { e.logo.compress(Bitmap.CompressFormat.PNG, 100, o); }
                    }
                    // v — версия картинки: телефон держит логотипы в кэше, новая версия — новый адрес.
                    a.put(new JSONObject().put("pkg", e.pkg).put("label", e.label).put("component", e.component == null ? "" : e.component)
                            .put("accent", String.format("#%06X", e.accent & 0xFFFFFF)).put("v", png.lastModified() / 1000));
                }
                write(new File(d, "apps.json"), a.toString());
                write(new File(d, "labels.json"), new JSONObject(labels).toString());
            } catch (Exception e) {
                Log.w(TAG, "publish: " + e);
            }
        }, "remote-publish").start();
    }

    /** Состояние для страницы: страна VPN и время таймера сна. */
    void state(String place, long sleepAt) {
        new Thread(() -> {
            try { write(new File(dir(), "state.json"), new JSONObject().put("place", place).put("sleepAt", sleepAt).toString()); }
            catch (Exception e) { Log.w(TAG, "state: " + e); }
        }, "remote-state").start();
    }

    /** Запустить сервер пульта от имени оболочки, если он не запущен; заодно убрать старый пересыльщик. */
    void ensureShell() {
        if (checking) return;
        checking = true;
        new Thread(() -> {
            try {
                write(new File(dir(), "key"), key);
                IosAdb adb = IosAdb.get(context);
                String listening = "netstat -tln 2>/dev/null | grep -c ':" + IosShell.PORT + " '";
                String apk = context.getApplicationInfo().sourceDir;
                // Сервер из старого APK (лаунчер обновили) — останавливаем, запустим новый.
                if (!adb.shell("cat /data/local/tmp/iosshell.apk 2>/dev/null").trim().equals(apk)) {
                    adb.shell("pkill -f 'com.gathin.tvhome.IosShel[l]'; sleep 0.5");
                }
                if (adb.shell(listening).trim().equals("0")) {
                    // Подоболочка с nohup и setsid переживает конец сеанса adb; пауза — чтобы процесс успел стартовать.
                    // Образцы pkill — с [скобкой]: иначе pkill находит в своей же командной строке оболочку и убивает её.
                    adb.shell("pkill -f 'n[c] -L -p " + IosShell.PORT + "'; sleep 0.3; "
                            + "(CLASSPATH=" + context.getApplicationInfo().sourceDir
                            + " nohup setsid app_process /system/bin com.gathin.tvhome.IosShell </dev/null >/data/local/tmp/iosshell.log 2>&1 &); sleep 1.5");
                }
                Log.i(TAG, "shell server " + (adb.shell(listening).trim().equals("0") ? "down" : "up"));
            } catch (Exception e) {
                Log.w(TAG, "shell: " + e);
            } finally {
                checking = false;
            }
        }, "remote-shell").start();
    }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new Exception("rename " + f);
    }

    static String localIp() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }
}
