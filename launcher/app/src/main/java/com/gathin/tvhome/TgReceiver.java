package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Поручения Telegram с пульта (сервер пульта шлёт их от имени оболочки, у неё есть DUMP): ключ приложения,
 * облачный пароль, выход. Живёт в процессе :tg, рядом с TDLib; ключ пульта проверяем, как везде.
 */
public final class TgReceiver extends BroadcastReceiver {
    static final String ACTION = "com.gathin.tvhome.TG";

    @Override public void onReceive(Context c, Intent i) {
        String key = c.getSharedPreferences("ios_remote", Context.MODE_MULTI_PROCESS).getString("key", "");
        if (key.isEmpty() || !key.equals(i.getStringExtra("k"))) return;
        TgCore tg = TgCore.get(c);
        String what = String.valueOf(i.getStringExtra("what"));
        switch (what) {
            case "key":
                try {
                    int id = Integer.parseInt(String.valueOf(i.getStringExtra("id")).trim());
                    String hash = String.valueOf(i.getStringExtra("hash")).trim();
                    if (id > 0 && hash.matches("[0-9a-fA-F]{32}")) tg.setKey(id, hash);
                } catch (NumberFormatException ignored) { }
                break;
            case "password": tg.password(String.valueOf(i.getStringExtra("p"))); break;
            case "logout": tg.logOut(); break;
            default: tg.start();
        }
    }
}
