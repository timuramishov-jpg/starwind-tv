package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Принимает ключи сервисов из adb: в файле приложения их нет.
 * Слать может только shell (разрешение DUMP): am broadcast -n com.gathin.tvhome/.IosConfig --es yt_api_key ...
 */
public final class IosConfig extends BroadcastReceiver {
    static final String PREFS = "ios_keys";
    static final String[] KEYS = {"yt_client_id", "yt_client_secret", "yt_api_key", "kino_key", "twitch_client_id"};

    @Override public void onReceive(Context c, Intent i) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        int n = 0;
        for (String k : KEYS) {
            String v = i.getStringExtra(k);
            if (v != null) { e.putString(k, v); n++; }
        }
        e.apply();
        Log.i("IosConfig", "keys " + n);
        setResultData("ok " + n);
    }

    static String get(Context c, String key) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "");
    }
}
