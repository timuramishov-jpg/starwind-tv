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
            case "saver":
                c.startActivity(new Intent(c, IosSaverActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case "saver_style":
            case "saver_idle":
                int v = 0;
                try { v = Integer.parseInt(value); } catch (Exception ignored) { }
                c.getSharedPreferences("ios_saver", 0).edit().putInt(what.equals("saver_style") ? "style" : "idle", v).apply();
                IosRemote.prefs(c);
                break;
            case "keyboard":
                // Выбор с пульта: лаунчер после обновления вернёт именно её.
                c.getSharedPreferences("ios_home_v4", 0).edit().putBoolean("own_keyboard", "1".equals(value)).apply();
                break;
            default:
                Log.w("IosCommand", "unknown " + what);
        }
    }
}
