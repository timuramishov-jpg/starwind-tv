package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Поручения от сервера пульта (IosShell): то, что знает только лаунчер. Без ключа из QR-кода — ничего не делает. */
public final class IosCommand extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String key = c.getSharedPreferences("ios_remote", 0).getString("key", "");
        if (key.isEmpty() || !key.equals(i.getStringExtra("k"))) return;
        String what = String.valueOf(i.getStringExtra("what")), value = String.valueOf(i.getStringExtra("value"));
        switch (what) {
            case "vpn":
                // Выключил с телефона — лаунчер не включит VPN обратно сам.
                c.getSharedPreferences("ios_home_v4", 0).edit().putBoolean("vpn_user_off", !"1".equals(value)).apply();
                break;
            case "sleep":
                long m = 0;
                try { m = Long.parseLong(value); } catch (Exception ignored) { }
                IosSleep.schedule(c, m);
                break;
            case "clip":
                ClipboardManager cb = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("text", value));
                break;
            default:
                Log.w("IosCommand", "unknown " + what);
        }
    }
}
