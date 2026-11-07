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

/** Погода для шапки: Open-Meteo без ключа, город Москва.
 *  Раз в 30 минут, последнее значение помнится. */
final class IosWeather {
    interface Sink { void done(String line); }

    private static final String TAG = "IosWeather";
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long at;

    IosWeather(Context c) { prefs = c.getSharedPreferences("ios_weather", 0); }

    /** Для заставки: температура, слово, макс., мин., город — что знаем. */
    static String[] details(Context c) {
        SharedPreferences p = c.getSharedPreferences("ios_weather", 0);
        return new String[]{p.getString("temp", ""), p.getString("word", ""), p.getString("hi", ""), p.getString("lo", ""), "Москва"};
    }

    /** Последняя строка «+3°, облачно», пустая — ещё не знаем. */
    String last() { return prefs.getString("line", ""); }

    void refresh(Sink sink) {
        long now = System.currentTimeMillis();
        if (now - at < 30 * 60_000L && !last().isEmpty()) return;
        at = now;
        new Thread(() -> {
            try {
                // Город всегда Москва: по адресу его не определить, пока работает VPN.
                double lat = 55.7558, lon = 37.6173;
                JSONObject w = get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code"
                                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto", lat, lon));
                JSONObject cur = w.getJSONObject("current");
                String line = IosText.degrees(cur.getDouble("temperature_2m")) + ", " + word(cur.optInt("weather_code"));
                SharedPreferences.Editor e = prefs.edit().putString("line", line)
                        .putString("temp", IosText.degrees(cur.getDouble("temperature_2m")))
                        .putString("word", word(cur.optInt("weather_code")));
                JSONObject daily = w.optJSONObject("daily");
                if (daily != null && daily.optJSONArray("temperature_2m_max") != null) {
                    e.putString("hi", IosText.degrees(daily.getJSONArray("temperature_2m_max").getDouble(0)));
                    e.putString("lo", IosText.degrees(daily.getJSONArray("temperature_2m_min").getDouble(0)));
                }
                e.apply();
                main.post(() -> sink.done(line));
            } catch (Exception e) {
                Log.w(TAG, "weather: " + e);
            }
        }, "weather").start();
    }

    static String word(int code) { return IosText.weather(code); }

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
