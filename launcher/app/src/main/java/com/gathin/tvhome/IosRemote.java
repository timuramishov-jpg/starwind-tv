package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 *  попадает на страницу, где можно отправить ссылку, искать, менять громкость и открыть приложение.
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
    private ServerSocket server;
    private Thread thread;

    IosRemote(Context c, Commands commands) {
        this.commands = commands;
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
            c.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* заголовки не нужны */ }
            String target = parts[1], path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> args = parse(query);
            OutputStream out = c.getOutputStream();
            if (!key.equals(args.get("k"))) { reply(out, 403, "text/plain", "Отсканируй QR-код на телевизоре"); return; }
            if (path.equals("/")) { reply(out, 200, "text/html", page()); return; }
            String what = path.substring(1);
            final String[] result = {""};
            final Object lock = new Object();
            final boolean[] done = {false};
            main.post(() -> {
                String r;
                try { r = commands.run(what, args); } catch (Exception e) { r = "{\"error\":\"" + e.getMessage() + "\"}"; }
                synchronized (lock) { result[0] = r == null ? "" : r; done[0] = true; lock.notifyAll(); }
            });
            synchronized (lock) { if (!done[0]) lock.wait(4000); }
            reply(out, 200, "application/json", result[0].isEmpty() ? "{}" : result[0]);
        } catch (Exception e) {
            Log.w(TAG, "client: " + e);
        }
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
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden") + "\r\nContent-Type: " + type
                + "; charset=utf-8\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String localIp() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || n.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Страница пульта: тёмная, в духе iOS, работает в любом браузере телефона. */
    private String page() {
        return "<!doctype html><html lang=ru><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<meta name=theme-color content='#000'><title>Пульт Starwind</title><style>"
                + ":root{color-scheme:dark}*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
                + "body{margin:0;background:#000;color:#f5f5f7;font:17px/1.35 -apple-system,BlinkMacSystemFont,'SF Pro Text',system-ui,sans-serif;padding:20px 16px 40px}"
                + "h1{font-size:34px;font-weight:700;letter-spacing:-.4px;margin:8px 4px 18px}"
                + "h2{font-size:13px;font-weight:600;color:#8e8e93;margin:26px 16px 8px}"
                + ".g{background:#1c1c1e;border-radius:14px;overflow:hidden}"
                + ".row{display:flex;gap:10px;padding:10px}"
                + "input{flex:1;min-width:0;font:inherit;color:inherit;background:#2c2c2e;border:0;border-radius:10px;padding:12px 14px;outline:none}"
                + "button{font:inherit;font-weight:600;border:0;border-radius:10px;padding:12px 16px;background:#0a84ff;color:#fff}"
                + "button.s{background:#2c2c2e;color:#f5f5f7}button:active{opacity:.6}"
                + ".grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;padding:10px}"
                + ".grid button{padding:16px 6px}"
                + ".apps button{display:block;width:100%;text-align:left;background:none;border-radius:0;border-top:1px solid #2c2c2e;padding:14px 16px;font-weight:400;color:#f5f5f7}"
                + ".apps button:first-child{border-top:0}"
                + "#t{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#2c2c2e;padding:10px 18px;border-radius:999px;font-size:15px;opacity:0;transition:opacity .2s}"
                + "</style></head><body><h1>Пульт</h1>"
                + "<h2>ОТКРЫТЬ НА ТЕЛЕВИЗОРЕ</h2><div class=g><div class=row><input id=u placeholder='Ссылка YouTube, ВК, Рутуб…' inputmode=url>"
                + "<button onclick=\"go('open',{u:val('u')})\">Открыть</button></div></div>"
                + "<h2>ПОИСК</h2><div class=g><div class=row><input id=q placeholder='Фильм, ролик, канал' enterkeyhint=search>"
                + "<button onclick=\"go('search',{q:val('q')})\">Найти</button></div></div>"
                + "<h2>ГРОМКОСТЬ</h2><div class=g><div class=grid>"
                + "<button class=s onclick=\"go('vol',{d:'down'})\">−</button><button class=s onclick=\"go('vol',{d:'mute'})\">Без звука</button>"
                + "<button class=s onclick=\"go('vol',{d:'up'})\">+</button></div></div>"
                + "<h2>VPN И СОН</h2><div class=g><div class=grid>"
                + "<button class=s onclick=\"go('vpn',{on:1})\">VPN вкл</button><button class=s onclick=\"go('vpn',{on:0})\">VPN выкл</button>"
                + "<button class=s onclick=\"go('home',{})\">Домой</button>"
                + "<button class=s onclick=\"go('sleep',{m:30})\">Сон 30</button><button class=s onclick=\"go('sleep',{m:60})\">Сон 60</button>"
                + "<button class=s onclick=\"go('sleep',{m:0})\">Без сна</button></div></div>"
                + "<h2>ПРИЛОЖЕНИЯ</h2><div class='g apps' id=a></div><div id=t></div>"
                + "<script>const k=new URLSearchParams(location.search).get('k');"
                + "function val(i){return document.getElementById(i).value.trim()}"
                + "function toast(s){const t=document.getElementById('t');t.textContent=s;t.style.opacity=1;clearTimeout(t.h);t.h=setTimeout(()=>t.style.opacity=0,1600)}"
                + "async function go(w,a){a.k=k;try{const r=await fetch('/'+w+'?'+new URLSearchParams(a));const j=await r.json();toast(j.say||'Готово')}catch(e){toast('Телевизор не ответил')}}"
                + "document.getElementById('q').addEventListener('keydown',e=>{if(e.key==='Enter')go('search',{q:val('q')})});"
                + "fetch('/apps?k='+k).then(r=>r.json()).then(l=>{const a=document.getElementById('a');"
                + "l.forEach(x=>{const b=document.createElement('button');b.textContent=x.label;b.onclick=()=>go('app',{p:x.pkg});a.appendChild(b)})});"
                + "</script></body></html>";
    }
}
