package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Возможности пульта в телефоне, которым нужны права оболочки (через IosAdb), и настройки телевизора. */
final class IosTools {
    private static final String TAG = "IosTools";
    /** Отключать нельзя: без них телевизор не загрузится, не покажет настройки или клавиатуру. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "android", "com.gathin.tvhome", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
            "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.phone",
            "com.android.bluetooth", "com.android.networkstack", "com.google.android.networkstack", "su.happ.proxyutility",
            "com.android.externalstorage", "com.android.keychain", "com.android.wifi.resources", "com.android.se",
            "com.android.inputdevices", "com.android.location.fused", "com.android.tv.provision"));
    /** Можно смело отключить: оболочка Яндекса и служебные программы, которые нам не нужны. */
    private static final Set<String> BLOAT = new HashSet<>(Arrays.asList(
            "com.yandex.tv.home", "com.yandex.tv.input.efir", "com.yandex.tv.videoplayer", "com.yandex.devices.farm.companion",
            "com.yandex.tv.bugreportsender", "com.cvte.tv.burn", "com.yandex.launcher.updaterapp", "com.yandex.devices.appstore"));
    static final String DNS_HOST = "dns.adguard-dns.com";

    private final Context c;
    private final IosAdb adb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long rxAt, rx, tx;

    IosTools(Context c) {
        this.c = c.getApplicationContext();
        adb = IosAdb.get(c);
    }

    static boolean bloat(String pkg) { return BLOAT.contains(pkg); }

    static boolean keep(String pkg) {
        return KEEP.contains(pkg) || pkg.startsWith("com.android.providers.") || pkg.startsWith("com.android.inputmethod")
                || pkg.startsWith("com.google.android.inputmethod") || pkg.contains("tvserver") || pkg.startsWith("com.cvte.tv.api");
    }

    // ---------- Пульт для любых приложений ----------

    String key(String name) throws Exception {
        if (name == null || !name.matches("[A-Z_0-9]{1,30}")) return say("Нет такой кнопки");
        adb.shell("input keyevent KEYCODE_" + name);
        return "{}";
    }

    /** Латиница и цифры печатаются напрямую, остальное (кириллица) — через буфер обмена и «вставить». */
    String text(String t) throws Exception {
        if (t == null || t.isEmpty()) return say("Напиши текст");
        if (t.matches("[A-Za-z0-9 .,@_+:/?=&-]+")) {
            adb.shell("input text '" + t.replace(" ", "%s").replace("'", "") + "'");
        } else {
            CountDownLatch set = new CountDownLatch(1);
            main.post(() -> {
                ClipboardManager cb = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("text", t));
                set.countDown();
            });
            set.await(2, TimeUnit.SECONDS);
            adb.shell("input keyevent KEYCODE_PASTE");
        }
        return say("Напечатал");
    }

    // ---------- Монитор ----------

    String stats() throws Exception {
        JSONObject j = new JSONObject();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(m);
        j.put("ramTotal", m.totalMem >> 20).put("ramFree", m.availMem >> 20);
        StatFs data = new StatFs(Environment.getDataDirectory().getPath());
        j.put("diskTotal", data.getTotalBytes() >> 20).put("diskFree", data.getAvailableBytes() >> 20);
        long now = SystemClock.elapsedRealtime(), r = TrafficStats.getTotalRxBytes(), t = TrafficStats.getTotalTxBytes();
        if (rxAt > 0 && now > rxAt) {
            j.put("down", (r - rx) * 8 / 1000 / Math.max(1, now - rxAt)).put("up", (t - tx) * 8 / 1000 / Math.max(1, now - rxAt));
        }
        rxAt = now;
        rx = r;
        tx = t;
        try {
            String out = adb.shell("cat /proc/stat | head -1; sleep 0.5; cat /proc/stat | head -1; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null");
            String[] lines = out.trim().split("\n");
            if (lines.length >= 2) j.put("cpu", cpu(lines[0], lines[1]));
            if (lines.length >= 3) {
                long milli = Long.parseLong(lines[2].trim());
                j.put("temp", milli > 1000 ? milli / 1000 : milli);
            }
            j.put("adb", true);
        } catch (Exception e) {
            j.put("adb", false);
        }
        return j.toString();
    }

    static int cpu(String a, String b) {
        long[] x = jiffies(a), y = jiffies(b);
        long total = y[0] - x[0], idle = y[1] - x[1];
        return total <= 0 ? 0 : (int) Math.round(100.0 * (total - idle) / total);
    }

    private static long[] jiffies(String line) {
        String[] p = line.trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 1; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            total += v;
            if (i == 4 || i == 5) idle += v;
        }
        return new long[]{total, idle};
    }

    /** Кто сколько памяти занимает: первые десять. */
    String top() throws Exception {
        String out = adb.shell("dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p' | head -12");
        JSONArray a = new JSONArray();
        for (String line : out.split("\n")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\d,]+)K: ([\\w.:]+)").matcher(line);
            if (m.find()) a.put(new JSONObject().put("mb", Long.parseLong(m.group(1).replace(",", "")) / 1024).put("pkg", m.group(2)).put("label", label(m.group(2).split(":")[0])));
        }
        return a.toString();
    }

    // ---------- Приложения ----------

    String packages() throws Exception {
        Set<String> off = lines(adb.shell("pm list packages -d")), user = lines(adb.shell("pm list packages -3"));
        TreeMap<String, JSONObject> sorted = new TreeMap<>();
        for (String p : lines(adb.shell("pm list packages"))) {
            JSONObject o = new JSONObject().put("pkg", p).put("label", label(p)).put("off", off.contains(p))
                    .put("user", user.contains(p)).put("keep", keep(p)).put("bloat", BLOAT.contains(p));
            sorted.put((BLOAT.contains(p) ? "0" : user.contains(p) ? "1" : "2") + o.getString("label").toLowerCase(Locale.ROOT) + p, o);
        }
        return new JSONArray(sorted.values()).toString();
    }

    String packageAction(String p, String what) throws Exception {
        if (p == null || !p.matches("[\\w.]+")) return say("Нет такого приложения");
        if (keep(p) && !"enable".equals(what) && !"open".equals(what)) return say("Это нужно телевизору, не трогаю");
        String out;
        switch (what == null ? "" : what) {
            case "disable": out = adb.shell("pm disable-user --user 0 " + p); break;
            case "enable": out = adb.shell("pm enable " + p); break;
            case "stop": out = adb.shell("am force-stop " + p); break;
            case "clear": out = adb.shell("pm trim-caches 999G; am force-stop " + p); break;
            case "uninstall":
                if (!lines(adb.shell("pm list packages -3")).contains(p)) return say("Встроенное приложение можно только отключить");
                out = adb.shell("pm uninstall " + p);
                break;
            default: return say("Не понял");
        }
        Log.i(TAG, what + " " + p + ": " + out.trim());
        return say(out.toLowerCase(Locale.ROOT).contains("exception") || out.toLowerCase(Locale.ROOT).contains("failure") ? "Не получилось: " + out.trim() : "Готово");
    }

    /** APK из телефона: прямо в установщик, без временных файлов. */
    String install(InputStream body, long size) throws Exception {
        if (size <= 0) return say("Пустой файл");
        String out = new String(adb.exec("cmd package install -r -S " + size, body, size), "UTF-8").trim();
        return say(out.contains("Success") ? "Установлено" : "Не установилось: " + out);
    }

    // ---------- Файлы ----------

    static String folder(String d) { return "Movies".equals(d) ? "/sdcard/Movies" : "/sdcard/Download"; }

    static boolean safeName(String n) { return n != null && n.matches("[^/\\\\'\"`$\\n]{1,120}") && !n.startsWith("."); }

    String files(String d) throws Exception {
        JSONArray a = new JSONArray();
        String out = adb.shell("cd " + folder(d) + " 2>/dev/null && for f in *; do [ -f \"$f\" ] && echo \"$(stat -c %s \"$f\")\t$f\"; done");
        for (String line : out.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try { a.put(new JSONObject().put("size", Long.parseLong(line.substring(0, tab).trim())).put("name", line.substring(tab + 1))); }
            catch (NumberFormatException ignored) { }
        }
        return a.toString();
    }

    String upload(String d, String name, InputStream body, long size) throws Exception {
        if (!safeName(name)) return say("Плохое имя файла");
        String path = folder(d) + "/" + name;
        adb.exec("mkdir -p " + folder(d) + " && head -c " + size + " > '" + path + "'", body, size);
        return say("Файл на телевизоре: " + ("Movies".equals(d) ? "Фильмы" : "Загрузки"));
    }

    long size(String d, String name) throws Exception {
        if (!safeName(name)) throw new Exception("имя");
        return Long.parseLong(adb.shell("stat -c %s '" + folder(d) + "/" + name + "'").trim());
    }

    void download(String d, String name, java.io.OutputStream sink) throws Exception {
        if (!safeName(name)) throw new Exception("имя");
        adb.exec("cat '" + folder(d) + "/" + name + "'", null, 0, sink);
    }

    String remove(String d, String name) throws Exception {
        if (!safeName(name)) return say("Плохое имя файла");
        adb.shell("rm -f '" + folder(d) + "/" + name + "'");
        return say("Удалил");
    }

    // ---------- Телевизор ----------

    byte[] screenshot() throws Exception { return adb.exec("screencap -p"); }

    /** Настройки, которые пишутся без adb: у лаунчера есть WRITE_SECURE_SETTINGS. */
    String tweak(String what, String value) throws Exception {
        boolean on = "1".equals(value);
        switch (what == null ? "" : what) {
            case "anim": {
                String s = on ? "0.5" : "1.0";
                for (String k : new String[]{Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE})
                    if (!putGlobal(k, s)) adb.shell("settings put global " + k + " " + s);
                return say(on ? "Анимации системы быстрее" : "Анимации как обычно");
            }
            case "dns": {
                if (on) {
                    if (!putGlobal("private_dns_mode", "hostname") || !putGlobal("private_dns_specifier", DNS_HOST))
                        adb.shell("settings put global private_dns_mode hostname; settings put global private_dns_specifier " + DNS_HOST);
                } else if (!putGlobal("private_dns_mode", "opportunistic")) adb.shell("settings put global private_dns_mode opportunistic");
                return say(on ? "Реклама режется на уровне DNS" : "DNS как обычно");
            }
            case "trim": return trim() ? say("Кэш приложений очищен") : say("Не получилось: нет доступа к отладке");
            case "reboot": adb.shell("svc power reboot || reboot"); return say("Перезагружаю");
            case "devsettings": adb.shell("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"); return say("Открыл настройки разработчика на телевизоре");
            default: return say("Не понял");
        }
    }

    JSONObject state() throws Exception {
        String anim = Settings.Global.getString(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE);
        String dns = Settings.Global.getString(c.getContentResolver(), "private_dns_specifier");
        String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
        long sleepAt = IosSleep.at(c);
        boolean vpn = false;
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
        }
        return new JSONObject().put("anim", "0.5".equals(anim)).put("dns", "hostname".equals(mode) && DNS_HOST.equals(dns))
                .put("asking", adb.asking).put("vpn", vpn)
                .put("place", c.getSharedPreferences("ios_home_v4", 0).getString("vpn_place", ""))
                .put("sleep", sleepAt == 0 ? 0 : Math.max(1, (sleepAt - System.currentTimeMillis() + 59_999) / 60000));
    }

    static boolean adsBlocked(Context c) {
        return "hostname".equals(Settings.Global.getString(c.getContentResolver(), "private_dns_mode"))
                && DNS_HOST.equals(Settings.Global.getString(c.getContentResolver(), "private_dns_specifier"));
    }

    static boolean fastAnim(Context c) {
        return "0.5".equals(Settings.Global.getString(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE));
    }

    private boolean putGlobal(String k, String v) {
        try { return Settings.Global.putString(c.getContentResolver(), k, v); } catch (Exception e) { return false; }
    }

    /** Чистка кэша всех приложений: входы и данные не трогаются. */
    boolean trim() {
        try {
            adb.shell("pm trim-caches 999G");
            c.getSharedPreferences("ios_tools", 0).edit().putLong("trimmed", System.currentTimeMillis()).apply();
            Log.i(TAG, "caches trimmed");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "trim: " + e);
            return false;
        }
    }

    /** Раз в 15 дней, пока телевизор простаивает. */
    void trimIfDue() {
        SharedPreferences p = c.getSharedPreferences("ios_tools", 0);
        if (System.currentTimeMillis() - p.getLong("trimmed", 0) < 15L * 24 * 3600_000L) return;
        new Thread(this::trim, "trim").start();
    }

    // ---------- Wi-Fi ----------

    /** Строки для панели Wi-Fi: сеть, сигнал, скорость, диапазон, адрес. Имя сети знает только оболочка (без разрешения на геолокацию). */
    List<String> wifi() {
        List<String> out = new ArrayList<>();
        String ssid = "", rssi = "", link = "", freq = "";
        try {
            String st = adb.shell("cmd wifi status 2>/dev/null; dumpsys wifi | grep -m1 'mWifiInfo'");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("SSID: \"?([^\",]*)\"?, .*?RSSI: (-?\\d+).*?Link speed: (\\d+)Mbps.*?Frequency: (\\d+)MHz").matcher(st);
            if (m.find()) { ssid = m.group(1); rssi = m.group(2); link = m.group(3); freq = m.group(4); }
        } catch (Exception e) {
            Log.w(TAG, "wifi: " + e);
        }
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        android.net.wifi.WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
        if (wi != null) {
            if (rssi.isEmpty()) rssi = String.valueOf(wi.getRssi());
            if (link.isEmpty() && wi.getLinkSpeed() > 0) link = String.valueOf(wi.getLinkSpeed());
            if (freq.isEmpty() && wi.getFrequency() > 0) freq = String.valueOf(wi.getFrequency());
        }
        out.add("Сеть: " + (ssid.isEmpty() || ssid.contains("unknown") ? "подключено" : ssid));
        if (!rssi.isEmpty()) {
            int r = Integer.parseInt(rssi);
            out.add("Сигнал: " + (r >= -55 ? "отличный" : r >= -67 ? "хороший" : r >= -75 ? "средний" : "слабый"));
        }
        if (!freq.isEmpty()) out.add("Диапазон: " + (Integer.parseInt(freq) >= 4900 ? "5 ГГц" : "2,4 ГГц"));
        if (!link.isEmpty()) out.add("Скорость связи: " + link + " Мбит/с");
        String ip = IosRemote.localIp();
        if (ip != null) out.add("Адрес: " + ip);
        out.add("Блокировка рекламы: " + (adsBlocked(c) ? "включена" : "выключена"));
        return out;
    }

    // ---------- Мелочи ----------

    private String label(String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static Set<String> lines(String out) {
        Set<String> s = new HashSet<>();
        for (String l : out.split("\n")) if (l.startsWith("package:")) s.add(l.substring(8).trim());
        return s;
    }

    static String say(String s) { return "{\"say\":" + JSONObject.quote(s) + "}"; }
}
