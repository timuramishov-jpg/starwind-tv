package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Пульт в телефоне: маленький сервер в домашней сети. Телефон сканирует QR-код с телевизора и
 *  попадает на страницу: кнопки для любого приложения, ввод текста, приложения, монитор, файлы, снимок экрана.
 *  Каждый запрос несёт ключ из QR-кода: без него сервер отвечает 403. */
final class IosRemote {
    interface Commands {
        /** Выполняется в главном потоке; возвращает текст ответа (JSON или пустую строку). */
        String run(String what, Map<String, String> args);
    }

    static final int PORT = 8686;
    private static final String TAG = "IosRemote";
    private final Commands commands;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String key;
    private final Context context;
    private final IosTools tools;
    private ServerSocket server;
    private Thread thread;

    IosRemote(Context c, Commands commands) {
        this.commands = commands;
        context = c.getApplicationContext();
        tools = new IosTools(c);
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

    /** Адрес для QR-кода, null — телевизор не в сети. */
    String address() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/?k=" + key;
    }

    void start() {
        if (thread != null) return;
        thread = new Thread(() -> {
            try (ServerSocket s = new ServerSocket(PORT, 8)) {
                server = s;
                while (!Thread.currentThread().isInterrupted()) {
                    Socket client = s.accept();
                    new Thread(() -> serve(client), "remote-client").start();
                }
            } catch (Exception e) {
                Log.w(TAG, "server: " + e);
            }
        }, "remote");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(60000);
            InputStream in = new java.io.BufferedInputStream(c.getInputStream());
            String head = headers(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) return;
            long length = 0;
            for (String h : lines) if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try { length = Long.parseLong(h.substring(15).trim()); } catch (NumberFormatException ignored) { }
            }
            String target = parts[1], path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> args = parse(query);
            OutputStream out = c.getOutputStream();
            if (!key.equals(args.get("k"))) { reply(out, 403, "text/plain", "Отсканируй QR-код на телевизоре"); return; }
            if (path.equals("/")) { reply(out, 200, "text/html", page()); return; }
            String what = path.substring(1);
            if (tool(what, args, in, length, out)) return;
            final String[] result = {""};
            final Object lock = new Object();
            final boolean[] done = {false};
            main.post(() -> {
                String r;
                try { r = commands.run(what, args); } catch (Exception e) { r = IosTools.say("Ошибка: " + e.getMessage()); }
                synchronized (lock) { result[0] = r == null ? "" : r; done[0] = true; lock.notifyAll(); }
            });
            synchronized (lock) { if (!done[0]) lock.wait(4000); }
            reply(out, 200, "application/json", result[0].isEmpty() ? "{}" : result[0]);
        } catch (Exception e) {
            Log.w(TAG, "client: " + e);
        }
    }

    /** Заголовки запроса до пустой строки; тело остаётся в потоке. */
    private static String headers(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int last4 = 0, ch;
        while ((ch = in.read()) >= 0) {
            b.write(ch);
            last4 = (last4 << 8) | ch;
            if (last4 == 0x0d0a0d0a) return new String(b.toByteArray(), StandardCharsets.UTF_8);
            if (b.size() > 16384) return null;
        }
        return null;
    }

    /** То, что делается в потоке сервера: команды через adb и файлы. false — не наше. */
    private boolean tool(String what, Map<String, String> a, InputStream body, long length, OutputStream out) throws Exception {
        try {
            String json;
            switch (what) {
                case "key": json = tools.key(a.get("c")); break;
                case "text": json = tools.text(a.get("t")); break;
                case "stats": json = tools.stats(); break;
                case "top": json = tools.top(); break;
                case "state": json = tools.state().toString(); break;
                case "pkgs": json = tools.packages(); break;
                case "pkg": json = tools.packageAction(a.get("p"), a.get("a")); break;
                case "install": json = tools.install(body, length); break;
                case "files": json = tools.files(a.get("d")); break;
                case "upload": json = tools.upload(a.get("d"), a.get("n"), body, length); break;
                case "rm": json = tools.remove(a.get("d"), a.get("n")); break;
                case "tweak": json = tools.tweak(a.get("w"), a.get("v")); break;
                case "shot": {
                    byte[] png = tools.screenshot();
                    send(out, 200, "image/png", png.length, null);
                    out.write(png);
                    out.flush();
                    return true;
                }
                case "get": {
                    String name = a.get("n");
                    long size = tools.size(a.get("d"), name);
                    send(out, 200, "application/octet-stream", size,
                            "Content-Disposition: attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") + "\r\n");
                    tools.download(a.get("d"), name, out);
                    out.flush();
                    return true;
                }
                default: return false;
            }
            reply(out, 200, "application/json", json);
        } catch (Exception e) {
            Log.w(TAG, what + ": " + e);
            String why = IosAdb.get(context).asking ? "На телевизоре нажми «Разрешить отладку»"
                    : e instanceof java.net.ConnectException ? "Отладка на телевизоре выключена" : "Не получилось: " + e.getMessage();
            try { reply(out, 200, "application/json", IosTools.say(why)); } catch (Exception ignored) { }
        }
        return true;
    }

    private static Map<String, String> parse(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try { m.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8")); } catch (Exception ignored) { }
        }
        return m;
    }

    private static void reply(OutputStream out, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        send(out, code, type + "; charset=utf-8", b.length, null);
        out.write(b);
        out.flush();
    }

    private static void send(OutputStream out, int code, String type, long length, String extra) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + length + "\r\n" + (extra == null ? "" : extra)
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
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

    /** Страница пульта лежит в assets/remote.html: тёмная, в духе iOS, работает в любом браузере телефона. */
    private String page() {
        try (InputStream in = context.getAssets().open("remote.html")) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Страница пульта не нашлась";
        }
    }
}
