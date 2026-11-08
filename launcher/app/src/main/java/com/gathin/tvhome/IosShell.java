package com.gathin.tvhome;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Сервер пульта в телефоне, который живёт отдельно от лаунчера: запускается от имени оболочки телевизора
 * (app_process, как scrcpy), поэтому
 *  - его не убивает нехватка памяти, когда лаунчер выгружен из-за тяжёлого приложения;
 *  - кнопки вводятся напрямую, без «input keyevent» и без задержки в полсекунды;
 *  - его ответы VPN не забирает в туннель, и телефон в домашней сети его видит.
 * Лаунчер кладёт для него в Android/data/…/files/remote ключ, список приложений с логотипами и своё состояние.
 * Всё, что требует логики лаунчера (поиск, VPN, таймер сна, буфер обмена), уходит лаунчеру широковещательным IosCommand.
 */
public final class IosShell {
    static final int PORT = 8687;
    static final String PKG = "com.gathin.tvhome";
    static final String DIR = "/sdcard/Android/data/" + PKG + "/files/remote";
    private static final Pattern NAME = Pattern.compile("[^/\\\\'\"`$\\n]{1,120}");

    private static String apk;
    private static Object input;
    private static Method inject;
    private static long rxAt, rx, tx;
    private static long[] cpuWas;

    public static void main(String[] args) throws Exception {
        apk = System.getProperty("java.class.path");
        Looper.prepareMainLooper();
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        input = im.getDeclaredMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        ServerSocket server = new ServerSocket(PORT);
        // Из какого APK запущен: лаунчер после обновления сверяет и перезапускает старый сервер.
        try (OutputStream o = new FileOutputStream("/data/local/tmp/iosshell.apk")) { o.write(String.valueOf(apk).getBytes(StandardCharsets.UTF_8)); }
        // Лаунчер обновили: старый APK исчез — уходим, лаунчер запустит сервер из нового.
        Thread watch = new Thread(() -> {
            while (true) {
                SystemClock.sleep(10_000);
                if (apk == null || !new File(apk).exists()) System.exit(0);
            }
        });
        watch.setDaemon(true);
        watch.start();
        System.out.println("IosShell on " + PORT);
        while (true) {
            Socket s = server.accept();
            new Thread(() -> serve(s)).start();
        }
    }

    // ---------- HTTP ----------

    private static void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            String head = headers(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) return;
            long length = 0;
            for (String h : lines) if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try { length = Long.parseLong(h.substring(15).trim()); } catch (NumberFormatException ignored) { }
            }
            String path = parts[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            Map<String, String> a = parse(query);
            for (String h : lines) if (h.toLowerCase(Locale.ROOT).startsWith("range:")) a.put("_range", h.substring(6).trim());
            OutputStream out = c.getOutputStream();
            // Быстрая команда iOS «Starwind в Фото» (подписана на Маке): сохраняет снимки и записи экрана в Фото.
            if (path.equals("/photos.shortcut")) {
                byte[] b = asset("photos.shortcut");
                head(out, 200, "application/octet-stream", b.length, "Content-Disposition: attachment; filename*=UTF-8''Starwind%20%D0%B2%20%D0%A4%D0%BE%D1%82%D0%BE.shortcut\r\n");
                out.write(b);
                out.flush();
                return;
            }
            if (path.equals("/icon.png")) { bytes(out, 200, "image/png", asset("remote-icon.png"), true); return; }
            String key = read(DIR + "/key").trim();
            if (key.isEmpty() || !key.equals(a.get("k"))) { text(out, 403, "text/plain", "Отсканируй QR-код на телевизоре"); return; }
            if (path.equals("/")) { bytes(out, 200, "text/html; charset=utf-8", asset("remote.html"), false); return; }
            handle(path.substring(1), a, in, length, out);
        } catch (Exception e) {
            System.err.println("client: " + e);
        }
    }

    private static void handle(String what, Map<String, String> a, InputStream body, long length, OutputStream out) throws Exception {
        String json;
        switch (what) {
            case "key": json = key(a.get("c")); break;
            case "text": json = type(a.get("t")); break;
            case "type": {
                // Открыта наша клавиатура: текст уходит прямо в поле, с кириллицей.
                sh("am broadcast -a " + IosKeyboard.TYPE + " -p " + PKG + " --es k " + quote(read(DIR + "/key").trim())
                        + " --es t " + quote(a.get("t") == null ? "" : a.get("t")) + ("1".equals(a.get("done")) ? " --ez done true" : ""));
                json = "{}";
                break;
            }
            case "home": press(KeyEvent.KEYCODE_HOME); json = "{}"; break;
            case "power": {
                // Как кнопка питания пульта. KEYCODE_SLEEP эта прошивка не слушает, а POWER уводит телевизор в сон.
                // Во сне он отключает Wi-Fi, поэтому включить с телефона нельзя — только пультом.
                boolean on = "1".equals(a.get("on"));
                press(on ? KeyEvent.KEYCODE_WAKEUP : KeyEvent.KEYCODE_POWER);
                json = say(on ? "Включаю телевизор" : "Выключаю телевизор");
                break;
            }
            case "vol": {
                String d = a.get("d");
                press("up".equals(d) ? KeyEvent.KEYCODE_VOLUME_UP : "down".equals(d) ? KeyEvent.KEYCODE_VOLUME_DOWN : KeyEvent.KEYCODE_VOLUME_MUTE);
                json = "{}";
                break;
            }
            case "apps": json = read(DIR + "/apps.json"); if (json.isEmpty()) json = "[]"; break;
            case "app": json = openApp(a.get("p")); break;
            case "logo": {
                String p = a.get("p");
                File f = new File(DIR + "/logos/" + p + ".png");
                if (p == null || !p.matches("[\\w.]+") || !f.exists()) { text(out, 404, "text/plain", ""); return; }
                bytes(out, 200, "image/png", readBytes(f), true);
                return;
            }
            case "open": json = openLink(a.get("u")); break;
            case "search": {
                String s = a.get("q");
                if (s == null || s.trim().isEmpty()) { json = say("Напиши, что искать"); break; }
                sh("am start -n " + PKG + "/.IosHome --es search " + quote(s.trim()));
                json = say("Ищу на телевизоре");
                break;
            }
            case "ytsearch": {
                // У YouTube своя клавиатура, ввод в неё не доходит. Зато ссылка на результаты поиска открывает их сразу.
                String q = a.get("q"), yt = youTube();
                if (q == null || q.trim().isEmpty()) { json = say("Напиши, что искать"); break; }
                if (yt == null) { json = say("YouTube не открыт"); break; }
                sh("am start -a android.intent.action.VIEW -d " + quote("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q.trim(), "UTF-8"))
                        + " -n " + yt);
                json = say("Ищу на YouTube");
                break;
            }
            case "tg": {
                // Telegram: ключ приложения, облачный пароль, выход, открыть экран. Всё — поручением процессу :tg.
                String tgWhat = String.valueOf(a.get("a"));
                StringBuilder cmd = new StringBuilder("am broadcast -n " + PKG + "/.TgReceiver --es k " + quote(read(DIR + "/key").trim()) + " --es what " + quote(tgWhat));
                if ("key".equals(tgWhat)) {
                    String id = orEmpty(a.get("id"), "").trim(), hash = orEmpty(a.get("hash"), "").trim();
                    if (!id.matches("\\d{3,12}") || !hash.matches("[0-9a-fA-F]{32}")) { json = say("api_id — число, api_hash — 32 знака из букв a–f и цифр"); break; }
                    cmd.append(" --es id ").append(id).append(" --es hash ").append(hash);
                } else if ("password".equals(tgWhat)) {
                    String p = a.get("p");
                    if (p == null || p.isEmpty()) { json = say("Введи пароль"); break; }
                    cmd.append(" --es p ").append(quote(p));
                } else if ("open".equals(tgWhat)) {
                    sh("am start -n " + PKG + "/.TgActivity");
                    json = say("Открываю Telegram");
                    break;
                }
                sh(cmd.toString());
                json = say("key".equals(tgWhat) ? "Ключ на телевизоре" : "password".equals(tgWhat) ? "Проверяю пароль" : "logout".equals(tgWhat) ? "Выхожу из Telegram" : "Готово");
                break;
            }
            case "vpn": {
                boolean on = "1".equals(a.get("on"));
                command("vpn", on ? "1" : "0");
                sh("am start -a android.intent.action.VIEW -d happ://" + (on ? "connect" : "disconnect") + "_without_ui"
                        + " -n su.happ.proxyutility/.feature.deeplink.DeeplinkInterceptorActivity");
                json = say(on ? "Включаю VPN" : "Выключаю VPN");
                break;
            }
            case "saver": command("saver", ""); json = say("Включаю заставку"); break;
            case "saverset": {
                String v = orEmpty(a.get("v"), "0");
                if (!v.matches("-?\\d{1,3}")) { json = say("Не понял"); break; }
                command("idle".equals(a.get("w")) ? "saver_idle" : "saver_style", v);
                json = "{}";
                break;
            }
            case "keyboard": {
                boolean own = "1".equals(a.get("on"));
                sh("ime enable " + PKG + "/.IosKeyboard; ime set " + (own ? PKG + "/.IosKeyboard"
                        : "ru.yandex.androidkeyboard.tv/ru.yandex.androidkeyboard.TvYandexKeyboardService"));
                command("keyboard", own ? "1" : "0");
                json = say(own ? "Клавиатура Starwind" : "Клавиатура Яндекса");
                break;
            }
            case "free": {
                long before = memAvailable();
                sh("am kill-all");
                Thread.sleep(1500);
                long freed = Math.max(0, memAvailable() - before);
                json = say(freed > 10 ? "Освободил " + freed + " МБ памяти" : "Память уже свободна");
                break;
            }
            case "play": {
                // Фильм из «Загрузок» или «Фильмов» — сразу на экран, в плеере телевизора.
                String n = orEmpty(a.get("n"), "");
                File f = new File(folder(a.get("d")), n);
                if (n.isEmpty() || n.contains("/") || !f.isFile()) { json = say("Нет такого файла"); break; }
                sh("am start -a android.intent.action.VIEW -d " + quote("file://" + f.getPath()) + " -t video/*");
                json = say("Включаю на телевизоре");
                break;
            }
            case "sleep": command("sleep", a.get("m")); json = say("0".equals(a.get("m")) ? "Таймер сна выключен" : "Выключу через " + a.get("m") + " мин"); break;
            case "stats": json = stats(); break;
            case "top": json = top(); break;
            case "state": json = state(); break;
            case "pkgs": json = packages(); break;
            case "pkg": json = packageAction(a.get("p"), a.get("a")); break;
            case "install": {
                String r = run("pm install -r -S " + length, body, length).trim();
                json = say(r.contains("Success") ? "Установлено" : "Не установилось: " + r);
                break;
            }
            case "files": json = files(a.get("d")); break;
            case "upload": json = upload(a.get("d"), a.get("n"), body, length); break;
            case "rm": {
                String n = a.get("n");
                if (n == null || !NAME.matcher(n).matches() || n.startsWith(".")) { json = say("Плохое имя файла"); break; }
                new File(folder(a.get("d")), n).delete();
                json = say("Удалил");
                break;
            }
            case "get": {
                String n = a.get("n");
                File f = new File(folder(a.get("d")), n == null ? "" : n);
                if (n == null || !NAME.matcher(n).matches() || n.startsWith(".") || !f.isFile()) { text(out, 404, "text/plain", ""); return; }
                head(out, 200, "application/octet-stream", f.length(),
                        "Content-Disposition: attachment; filename*=UTF-8''" + URLEncoder.encode(n, "UTF-8").replace("+", "%20") + "\r\n");
                try (InputStream i = new FileInputStream(f)) { copy(i, out, Long.MAX_VALUE); }
                out.flush();
                return;
            }
            case "rec": {
                // Запись экрана: системный screenrecord (ищем по точному имени: у MediaTek есть служба vendor…screenrecord@1.0-service), до 3 минут, 720p. Остановили — видео можно открыть на телефоне.
                String rec = orEmpty(a.get("a"), "state");
                if ("start".equals(rec)) {
                    sh("rm -f " + REC + "; (nohup screenrecord --size 1280x720 --bit-rate 6000000 --time-limit 180 " + REC + " </dev/null >/dev/null 2>&1 &)");
                    json = say("Записываю экран — до 3 минут");
                } else if ("stop".equals(rec)) {
                    sh("pkill -INT -x screenrecord");
                    Thread.sleep(1200);
                    json = new JSONObject().put("say", "Запись готова").put("ready", new File(REC).length() > 0).toString();
                } else json = new JSONObject().put("on", recording())
                        .put("ready", new File(REC).length() > 0).toString();
                break;
            }
            case "video": {
                // Запись экрана для телефона: с поддержкой Range — без неё Safari на iPhone не показывает видео.
                File f = new File(REC);
                if (!f.isFile()) { text(out, 404, "text/plain", ""); return; }
                long len = f.length(), from = 0, to = len - 1;
                String r = a.get("_range");
                Matcher m = r == null ? null : Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(r);
                boolean part = m != null && m.find();
                if (part) {
                    if (!m.group(1).isEmpty()) from = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) to = Math.min(len - 1, Long.parseLong(m.group(2)));
                    if (m.group(1).isEmpty() && !m.group(2).isEmpty()) { from = Math.max(0, len - Long.parseLong(m.group(2))); to = len - 1; }
                }
                head(out, part ? 206 : 200, "video/mp4", to - from + 1, "Accept-Ranges: bytes\r\n"
                        + (part ? "Content-Range: bytes " + from + "-" + to + "/" + len + "\r\n" : ""));
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                    raf.seek(from);
                    byte[] buf = new byte[65536];
                    long left = to - from + 1;
                    while (left > 0) {
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n <= 0) break;
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
                out.flush();
                return;
            }
            case "twkey": {
                // Twitch: Client ID своего приложения с dev.twitch.tv — в ключи лаунчера (как tv keys), затем вход кодом на экране.
                String id = orEmpty(a.get("id"), "").trim().toLowerCase(java.util.Locale.ROOT);
                if (!id.matches("[a-z0-9]{20,40}")) { json = say("Client ID — 30 латинских букв и цифр с dev.twitch.tv"); break; }
                sh("am broadcast -n " + PKG + "/.IosConfig --es twitch_client_id " + id);
                sh("am start -n " + PKG + "/.IosHome --ez twitch_account true");
                json = say("Ключ на телевизоре — там появится код входа");
                break;
            }
            case "account": {
                // Аккаунты на телевизоре: экран управления открывается на самом телевизоре.
                String which = orEmpty(a.get("a"), "");
                if ("yandex".equals(which)) sh("am start -a com.yandex.tv.passport.MANAGE_ACCOUNTS -p com.yandex.tv.services");
                else if ("youtube".equals(which)) sh("am start -n " + PKG + "/.IosHome --ez youtube_account true");
                else if ("twitch".equals(which)) sh("am start -n " + PKG + "/.IosHome --ez twitch_account true");
                json = say("Открываю на телевизоре");
                break;
            }
            case "view": {
                // Экран телевизора для пульта: маленький JPEG (960×540, ~60 КБ), чтобы обновлялся раз в секунду.
                byte[] png = runBytes("screencap -p");
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                if (b == null) { json = say("Экран не снялся"); break; }
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(b, 960, 540, true);
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, jpg);
                b.recycle();
                small.recycle();
                bytes(out, 200, "image/jpeg", jpg.toByteArray(), false);
                return;
            }
            case "shot": {
                byte[] png = runBytes("screencap -p");
                bytes(out, 200, "image/png", png, false);
                return;
            }
            case "tweak": json = tweak(a.get("w"), "1".equals(a.get("v"))); break;
            default: json = say("Не понял");
        }
        text(out, 200, "application/json; charset=utf-8", json);
    }

    // ---------- Кнопки и текст: напрямую в систему ввода ----------

    private static void press(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        send(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private static void send(KeyEvent e) throws Exception { inject.invoke(input, e, 0); }





    private static String key(String name) throws Exception {
        if (name == null || !name.matches("[A-Z_0-9]{1,30}")) return say("Нет такой кнопки");
        int code = KeyEvent.keyCodeFromString("KEYCODE_" + name);
        if (code == KeyEvent.KEYCODE_UNKNOWN) return say("Нет такой кнопки");
        press(code);
        return "{}";
    }

    /** Текст в поле на экране. Наша клавиатура открыта — вставляем целиком через неё. Иначе латиница — по буквам,
     *  а кириллица — одним «многосимвольным» событием клавиатуры: его понимают и приложения со своей клавиатурой (YouTube). */
    private static String type(String t) throws Exception {
        if (t == null || t.isEmpty()) return say("Напиши текст");
        if ("1".equals(read(DIR + "/typing").trim())) {
            sh("am broadcast -a " + IosKeyboard.TYPE + " -p " + PKG + " --es k " + quote(read(DIR + "/key").trim()) + " --es t " + quote(t));
            return "{}";
        }
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(t.toCharArray());
        if (events != null) for (KeyEvent e : events) send(e);
        else send(new KeyEvent(SystemClock.uptimeMillis(), t, KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
        return "{}";
    }

    /** YouTube для телевизора (у Яндекса своя сборка Cobalt): пакет → экран, который принимает ссылки youtube.com. */
    private static final String[][] YOUTUBE = {{"com.yandex.tv.ytplayer", "com.yandex.tv.ytplayer/dev.cobalt.app.MainActivity"},
            {"com.google.android.youtube.tv", "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"}};
    private static String front = "";
    private static long frontAt;

    private static synchronized String front() {
        if (SystemClock.uptimeMillis() - frontAt < 1500) return front;
        frontAt = SystemClock.uptimeMillis();
        try {
            Matcher m = Pattern.compile("mResumedActivity: ActivityRecord\\{\\S+ \\S+ ([\\w.]+)/").matcher(sh("dumpsys activity activities | grep -m1 mResumedActivity"));
            front = m.find() ? m.group(1) : "";
        } catch (Exception e) { front = ""; }
        return front;
    }

    /** Открытый сейчас YouTube (экран для ссылки) или null. */
    private static String youTube() {
        String f = front();
        for (String[] y : YOUTUBE) if (y[0].equals(f)) return y[1];
        return null;
    }

    /** ime — на телевизоре открыта наша клавиатура, ждут текст. */
    private static String typingMode() {
        return "1".equals(read(DIR + "/typing").trim()) ? "ime" : "";
    }

    // ---------- Приложения и ссылки ----------

    private static String openApp(String pkg) throws Exception {
        JSONArray apps = new JSONArray(orEmpty(read(DIR + "/apps.json"), "[]"));
        for (int i = 0; i < apps.length(); i++) {
            JSONObject o = apps.getJSONObject(i);
            if (!o.optString("pkg").equals(pkg)) continue;
            String comp = o.optString("component");
            if (comp.isEmpty()) sh("monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1");
            else sh("am start -n " + comp);
            return say("Открываю " + o.optString("label"));
        }
        return say("Нет такого приложения");
    }

    private static String openLink(String u) throws Exception {
        if (u == null || u.isEmpty()) return say("Вставь ссылку");
        if (!u.startsWith("http") && !u.startsWith("tg:")) u = "https://" + u;
        String host = u.replaceFirst("^https?://", "").split("/")[0];
        // Пост в Telegram — в свой клиент (TDLib, только через VPN), а не в браузер.
        if (host.equals("t.me") || host.equals("telegram.me") || host.endsWith(".t.me") || u.startsWith("tg:")) {
            String r = sh("am start -n " + PKG + "/.TgActivity -a android.intent.action.VIEW -d " + quote(u));
            return say(r.contains("Error") ? "Telegram на телевизоре не открылся" : "Открываю пост в Telegram");
        }
        // Ссылка — в приложение сервиса, если оно стоит (YouTube на Яндекс ТВ — своя сборка Cobalt), иначе куда решит система.
        String target = "";
        if (host.contains("youtu")) {
            for (String[] y : YOUTUBE) if (sh("pm path " + y[0]).contains("package:")) { target = " -n " + y[1]; break; }
        } else {
            String pkg = host.contains("rutube") ? "ru.rutube.app.tv" : host.contains("vk.com") || host.contains("vkvideo") ? "com.vk.tv" : "";
            if (!pkg.isEmpty() && sh("pm path " + pkg).contains("package:")) target = " -p " + pkg;
        }
        String r = sh("am start -a android.intent.action.VIEW -d " + quote(u) + target);
        return say(r.contains("Error") ? "Телевизор не знает, чем открыть эту ссылку" : "Открываю на телевизоре");
    }

    private static final String REC = "/sdcard/Movies/tv-rec.mp4";

    /** Поручение лаунчеру: он проснётся ради него, даже если был выгружен. */
    private static void command(String what, String value) throws Exception {
        sh("am broadcast -n " + PKG + "/.IosCommand --es k " + quote(read(DIR + "/key").trim())
                + " --es what " + what + " --es value " + quote(value == null ? "" : value));
    }

    // ---------- Монитор ----------

    private static String stats() throws Exception {
        JSONObject j = new JSONObject();
        long[] cpu = cpu();
        if (cpuWas != null && cpu[0] > cpuWas[0]) j.put("cpu", Math.round(100.0 * ((cpu[0] - cpuWas[0]) - (cpu[1] - cpuWas[1])) / (cpu[0] - cpuWas[0])));
        cpuWas = cpu;
        Map<String, Long> mem = new HashMap<>();
        for (String l : read("/proc/meminfo").split("\n")) {
            String[] p = l.split("[:\\s]+");
            if (p.length >= 2) try { mem.put(p[0], Long.parseLong(p[1])); } catch (NumberFormatException ignored) { }
        }
        j.put("ramTotal", mem.getOrDefault("MemTotal", 0L) / 1024).put("ramFree", mem.getOrDefault("MemAvailable", 0L) / 1024);
        android.os.StatFs data = new android.os.StatFs("/data");
        j.put("diskTotal", data.getTotalBytes() >> 20).put("diskFree", data.getAvailableBytes() >> 20);
        String temp = read("/sys/class/thermal/thermal_zone0/temp").trim();
        if (!temp.isEmpty()) try { long m = Long.parseLong(temp); j.put("temp", m > 1000 ? m / 1000 : m); } catch (NumberFormatException ignored) { }
        long r = 0, t = 0;
        for (String l : read("/proc/net/dev").split("\n")) {
            if (!l.contains("wlan") && !l.contains("eth")) continue;
            String[] p = l.trim().split("[:\\s]+");
            if (p.length > 9) { r += Long.parseLong(p[1]); t += Long.parseLong(p[9]); }
        }
        long now = SystemClock.elapsedRealtime();
        if (rxAt > 0 && now > rxAt) j.put("down", (r - rx) * 8 / Math.max(1, now - rxAt)).put("up", (t - tx) * 8 / Math.max(1, now - rxAt));
        rxAt = now;
        rx = r;
        tx = t;
        return j.toString();
    }

    private static long[] cpu() {
        String[] p = read("/proc/stat").split("\n")[0].trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 1; i < p.length; i++) {
            try {
                long v = Long.parseLong(p[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;
            } catch (NumberFormatException ignored) { }
        }
        return new long[]{total, idle};
    }

    private static String top() throws Exception {
        JSONObject labels = new JSONObject(orEmpty(read(DIR + "/labels.json"), "{}"));
        JSONArray a = new JSONArray();
        boolean on = false;
        for (String line : sh("dumpsys meminfo").split("\n")) {
            if (line.contains("Total PSS by process")) { on = true; continue; }
            if (on && line.trim().isEmpty()) break;
            Matcher m = Pattern.compile("([\\d,]+)K: ([\\w.:]+)").matcher(line);
            if (on && m.find() && a.length() < 10) {
                String p = m.group(2).split(":")[0];
                a.put(new JSONObject().put("mb", Long.parseLong(m.group(1).replace(",", "")) / 1024).put("pkg", m.group(2)).put("label", labels.optString(p, p)));
            }
        }
        return a.toString();
    }

    private static String state() throws Exception {
        JSONObject s = new JSONObject(orEmpty(read(DIR + "/state.json"), "{}"));
        String anim = sh("settings get global animator_duration_scale").trim();
        String mode = sh("settings get global private_dns_mode").trim(), host = sh("settings get global private_dns_specifier").trim();
        long sleepAt = s.optLong("sleepAt");
        return new JSONObject().put("anim", "0.5".equals(anim)).put("dns", "hostname".equals(mode) && IosTools.DNS_HOST.equals(host))
                .put("vpn", new File("/sys/class/net/tun0").exists()).put("place", s.optString("place")).put("youtube", s.optBoolean("youtube")).put("twitch", s.optString("twitch", "nokey"))
                .put("typing", typingMode())
                .put("yt", youTube() != null)
                .put("tg", new JSONObject(orEmpty(read(DIR + "/tg.json"), "{\"state\":\"closed\"}")))
                .put("keyboard", sh("settings get secure default_input_method").trim().startsWith(PKG))
                .put("saver", new JSONObject(orEmpty(read(DIR + "/prefs.json"), "{}")))
                .put("sleep", sleepAt <= System.currentTimeMillis() ? 0 : (sleepAt - System.currentTimeMillis() + 59_999) / 60000).toString();
    }

    // ---------- Приложения на телевизоре ----------

    private static Set<String> list(String flags) throws Exception {
        Set<String> s = new HashSet<>();
        for (String l : sh("pm list packages " + flags).split("\n")) if (l.startsWith("package:")) s.add(l.substring(8).trim());
        return s;
    }

    private static String packages() throws Exception {
        JSONObject labels = new JSONObject(orEmpty(read(DIR + "/labels.json"), "{}"));
        Set<String> off = list("-d"), user = list("-3");
        TreeMap<String, JSONObject> sorted = new TreeMap<>();
        for (String p : list("")) {
            boolean bloat = IosTools.bloat(p);
            String label = labels.optString(p, p);
            JSONObject o = new JSONObject().put("pkg", p).put("label", label).put("off", off.contains(p))
                    .put("user", user.contains(p)).put("keep", IosTools.keep(p)).put("bloat", bloat);
            sorted.put((bloat ? "0" : user.contains(p) ? "1" : "2") + label.toLowerCase(Locale.ROOT) + p, o);
        }
        return new JSONArray(sorted.values()).toString();
    }

    private static String packageAction(String p, String what) throws Exception {
        if (p == null || !p.matches("[\\w.]+")) return say("Нет такого приложения");
        if (IosTools.keep(p) && !"enable".equals(what)) return say("Это нужно телевизору, не трогаю");
        String out;
        switch (what == null ? "" : what) {
            case "disable": out = sh("pm disable-user --user 0 " + p); break;
            case "enable": out = sh("pm enable " + p); break;
            case "stop": out = sh("am force-stop " + p); break;
            case "uninstall":
                if (!list("-3").contains(p)) return say("Встроенное приложение можно только отключить");
                out = sh("pm uninstall " + p);
                break;
            default: return say("Не понял");
        }
        String low = out.toLowerCase(Locale.ROOT);
        return say(low.contains("exception") || low.contains("failure") ? "Не получилось: " + out.trim() : "Готово");
    }

    // ---------- Файлы ----------

    private static boolean recording() throws Exception {
        return sh("pgrep -x screenrecord >/dev/null && echo yes").trim().equals("yes");
    }

    private static long memAvailable() throws Exception {
        for (String l : read("/proc/meminfo").split("\n"))
            if (l.startsWith("MemAvailable:")) return Long.parseLong(l.replaceAll("\\D+", "")) / 1024;
        return 0;
    }

    private static File folder(String d) { return new File("Movies".equals(d) ? "/sdcard/Movies" : "/sdcard/Download"); }

    private static String files(String d) throws Exception {
        JSONArray a = new JSONArray();
        File[] list = folder(d).listFiles();
        if (list != null) for (File f : list) if (f.isFile() && !f.getName().startsWith(".")) a.put(new JSONObject().put("name", f.getName()).put("size", f.length()));
        return a.toString();
    }

    private static String upload(String d, String n, InputStream body, long size) throws Exception {
        if (n == null || !NAME.matcher(n).matches() || n.startsWith(".")) return say("Плохое имя файла");
        File dir = folder(d);
        dir.mkdirs();
        try (OutputStream o = new FileOutputStream(new File(dir, n))) { copy(body, o, size); }
        return say("Файл на телевизоре: " + ("Movies".equals(d) ? "Фильмы" : "Загрузки"));
    }

    // ---------- Настройки ----------

    private static String tweak(String what, boolean on) throws Exception {
        switch (what == null ? "" : what) {
            case "anim": {
                String s = on ? "0.5" : "1.0";
                sh("settings put global window_animation_scale " + s + "; settings put global transition_animation_scale " + s
                        + "; settings put global animator_duration_scale " + s);
                return say(on ? "Анимации системы быстрее" : "Анимации как обычно");
            }
            case "dns":
                sh(on ? "settings put global private_dns_mode hostname; settings put global private_dns_specifier " + IosTools.DNS_HOST
                        : "settings put global private_dns_mode opportunistic");
                return say(on ? "Реклама режется на уровне DNS" : "DNS как обычно");
            case "trim": sh("pm trim-caches 999G"); return say("Кэш приложений очищен");
            case "reboot": sh("svc power reboot || reboot"); return say("Перезагружаю");
            case "devsettings": sh("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS"); return say("Открыл настройки разработчика на телевизоре");
            default: return say("Не понял");
        }
    }

    // ---------- Мелочи ----------

    static String sh(String cmd) throws Exception { return new String(runBytes(cmd), StandardCharsets.UTF_8); }

    private static byte[] runBytes(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        copy(p.getInputStream(), o, Long.MAX_VALUE);
        p.waitFor();
        return o.toByteArray();
    }

    private static String run(String cmd, InputStream in, long size) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        try (OutputStream o = p.getOutputStream()) { copy(in, o, size); }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        copy(p.getInputStream(), o, Long.MAX_VALUE);
        p.waitFor();
        return o.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out, long max) throws Exception {
        byte[] b = new byte[65536];
        long left = max;
        int n;
        while (left > 0 && (n = in.read(b, 0, (int) Math.min(b.length, left))) > 0) {
            out.write(b, 0, n);
            left -= n;
        }
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private static String orEmpty(String s, String fallback) { return s == null || s.trim().isEmpty() ? fallback : s; }

    static String read(String path) {
        try { return new String(readBytes(new File(path)), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
    }

    private static byte[] readBytes(File f) throws Exception {
        try (InputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            copy(i, o, Long.MAX_VALUE);
            return o.toByteArray();
        }
    }

    private static byte[] asset(String name) throws Exception {
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry e = z.getEntry("assets/" + name);
            if (e == null) return new byte[0];
            try (InputStream i = z.getInputStream(e)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                copy(i, o, Long.MAX_VALUE);
                return o.toByteArray();
            }
        }
    }

    static String say(String s) { return "{\"say\":" + JSONObject.quote(s) + "}"; }

    private static Map<String, String> parse(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            try { m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); } catch (Exception ignored) { }
        }
        return m;
    }

    private static String headers(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int last4 = 0, ch;
        while ((ch = in.read()) >= 0) {
            b.write(ch);
            last4 = (last4 << 8) | ch;
            if (last4 == 0x0d0a0d0a) return new String(b.toByteArray(), StandardCharsets.UTF_8);
            if (b.size() > 16384) return null;
        }
        return null;
    }

    private static void head(OutputStream out, int code, String type, long length, String extra) throws Exception {
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 206 ? " Partial Content" : code == 404 ? " Not Found" : " Forbidden") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + length + "\r\n" + (extra == null ? "" : extra) + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(OutputStream out, int code, String type, byte[] b, boolean cache) throws Exception {
        head(out, code, type, b.length, cache ? "Cache-Control: max-age=86400\r\n" : "Cache-Control: no-store\r\n");
        out.write(b);
        out.flush();
    }

    private static void text(OutputStream out, int code, String type, String s) throws Exception {
        bytes(out, code, type, s.getBytes(StandardCharsets.UTF_8), false);
    }
}
