package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Таймер сна через будильник системы: срабатывает, даже если лаунчер выгружен из памяти,
 *  пока ты смотришь кино. За минуту — предупреждение, в конце экран гаснет (lockNow). */
public class IosSleep extends BroadcastReceiver {
    static final String WARN = "com.gathin.tvhome.SLEEP_WARN", NOW = "com.gathin.tvhome.SLEEP_NOW";

    static void schedule(Context c, long minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent warn = pending(c, WARN), now = pending(c, NOW);
        am.cancel(warn);
        am.cancel(now);
        c.getSharedPreferences("ios_sleep", 0).edit().putLong("at", minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L).apply();
        if (minutes == 0) return;
        long at = System.currentTimeMillis() + minutes * 60_000L;
        am.setExact(AlarmManager.RTC_WAKEUP, at - 60_000L, warn);
        am.setExact(AlarmManager.RTC_WAKEUP, at, now);
    }

    /** Когда сработает, 0 — таймер выключен. */
    static long at(Context c) {
        long at = c.getSharedPreferences("ios_sleep", 0).getLong("at", 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    private static PendingIntent pending(Context c, String action) {
        return PendingIntent.getBroadcast(c, action.hashCode(), new Intent(action).setClass(c, IosSleep.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context c, Intent i) {
        if (WARN.equals(i.getAction())) {
            Toast.makeText(c, "Телевизор выключится через минуту. Отменить: Домой → шестерёнка → Таймер сна", Toast.LENGTH_LONG).show();
            return;
        }
        if (!NOW.equals(i.getAction())) return;
        c.getSharedPreferences("ios_sleep", 0).edit().putLong("at", 0).apply();
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(c, IosAdmin.class);
        if (dpm != null && dpm.isAdminActive(admin)) dpm.lockNow();
        else Toast.makeText(c, "Таймер сна не может выключить экран: нужен tv deploy", Toast.LENGTH_LONG).show();
    }
}
