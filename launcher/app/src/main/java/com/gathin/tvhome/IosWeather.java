package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Погода для шапки: Open-Meteo без ключа. Город по адресу в обход VPN (ipwho.is), иначе Москва.
 *  Раз в 30 минут, последнее значение помнится. */
final class IosWeather {
    interface Sink { void done(String line); }

    private static final String TAG = "IosWeather";
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long at;

    IosWeather(Context c) { prefs = c.getSharedPreferences("ios_weather", 0); }

    /** Последняя строка «+3°, облачно», пустая — ещё не знаем. */
    String last() { return prefs.getString("line", ""); }

    void refresh(Sink sink) {
        long now = System.currentTimeMillis();
        if (now - at < 30 * 60_000L && !last().isEmpty()) return;
        at = now;
        new Thread(() -> {
            try {
                double lat = Double.longBitsToDouble(prefs.getLong("lat", Double.doubleToLongBits(55.7558)));
                double lon = Double.longBitsToDouble(prefs.getLong("lon", Double.doubleToLongBits(37.6173)));
                if (!prefs.getBoolean("located", false)) {
                    try {
                        JSONObject g = get("https://ipwho.is/?fields=latitude,longitude,city,success");
                        if (g.optBoolean("success") && g.has("latitude")) {
                            lat = g.getDouble("latitude");
                            lon = g.getDouble("longitude");
                            prefs.edit().putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon))
                                    .putString("city", g.optString("city")).putBoolean("located", true).apply();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "where: " + e);
                    }
                }
                JSONObject w = get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code&timezone=auto", lat, lon));
                JSONObject cur = w.getJSONObject("current");
                long t = Math.round(cur.getDouble("temperature_2m"));
                String line = (t > 0 ? "+" : t < 0 ? "−" : "") + Math.abs(t) + "°, " + word(cur.optInt("weather_code"));
                prefs.edit().putString("line", line).apply();
                main.post(() -> sink.done(line));
            } catch (Exception e) {
                Log.w(TAG, "weather: " + e);
            }
        }, "weather").start();
    }

    /** Коды погоды WMO словами. */
    static String word(int code) {
        if (code == 0) return "ясно";
        if (code <= 2) return "переменная облачность";
        if (code == 3) return "облачно";
        if (code <= 48) return "туман";
        if (code <= 57) return "морось";
        if (code <= 67) return "дождь";
        if (code <= 77) return "снег";
        if (code <= 82) return "ливень";
        if (code <= 86) return "снегопад";
        return "гроза";
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }
}
