package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Память телевизора: 925 МБ на всё. Когда фоновые приложения (Рутуб, ВК, Зона, браузер) съедают её, система
 * начинает выгружать всё подряд, и лаунчер подвисает. Возвращаешься на главный экран при нехватке памяти —
 * закрываем фоновые приложения, кроме того, из которого только что вышел, VPN и служб телевизора.
 */
final class IosMemory {
    private IosMemory() { }

    private static final String TAG = "IosMemory";
    /** Порог: меньше стольких мегабайт свободно — пора чистить. */
    private static final long LOW_MB = 320;
    private static long lastAuto;

    /** Кинопоиск после выгрузки спрашивает «кто смотрит»: его не трогаем. */
    static Set<String> keep(Context c) {
        return new HashSet<>(Arrays.asList(c.getPackageName(), IosHome.HAPP, IosHome.SOTA, IosFeed.KINOPOISK, "com.android.systemui",
                "com.android.providers.tv", "com.android.bluetooth", "ru.yandex.androidkeyboard.tv", "com.mediatek.tvinput",
                "com.mediatek.tv.service", "com.cvte.tv.api.impl", "com.cvte.tv.globalui", "com.yandex.tv.services.platform"));
    }

    static long freeMb(Context c) {
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);
        return m.availMem / (1024 * 1024);
    }

    /** Закрыть фоновые процессы приложений; except — не трогать. Возвращает, сколько пакетов обошли. */
    static int killBackground(Context c, Set<String> except) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> keep = keep(c);
        if (except != null) keep.addAll(except);
        int n = 0;
        for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
            if (keep.contains(ai.packageName) || ai.packageName.startsWith("com.android.") && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            try { am.killBackgroundProcesses(ai.packageName); n++; }
            catch (RuntimeException e) { Log.w(TAG, "kill " + ai.packageName + ": " + e); }
        }
        return n;
    }

    /** На главном экране, в фоне: если памяти мало — закрываем приложения из «недавних», кроме того, из которого
     *  только что вышли. killBackgroundProcesses здесь почти ничего не освобождает, поэтому — am kill от имени оболочки:
     *  он закрывает только то, что сейчас не на экране и не проигрывает. Не чаще раза в 2 минуты. */
    static void autoClean(Context context) {
        Context c = context.getApplicationContext();
        if (SystemClock.elapsedRealtime() - lastAuto < 120_000) return;
        new Thread(() -> {
            try {
                long before = freeMb(c);
                if (before >= LOW_MB) return;
                lastAuto = SystemClock.elapsedRealtime();
                IosAdb adb = IosAdb.get(c);
                Matcher m = Pattern.compile("type=standard[^\\n]*?A=\\d+:([\\w.]+)").matcher(adb.shell("dumpsys activity recents | grep 'Recent #'"));
                Set<String> keep = keep(c);
                String last = null;
                StringBuilder cmd = new StringBuilder();
                while (m.find()) {
                    String pkg = m.group(1);
                    if (pkg.equals(c.getPackageName())) continue;
                    if (last == null) { last = pkg; continue; }
                    if (keep.contains(pkg)) continue;
                    cmd.append("am kill ").append(pkg).append("; ");
                }
                if (cmd.length() > 0) adb.shell(cmd.toString());
                Thread.sleep(1500);
                Log.i(TAG, "auto: " + before + " → " + freeMb(c) + " MB free, kept " + last + ", " + cmd);
            } catch (Exception e) {
                Log.w(TAG, "auto: " + e);
            }
        }, "memory").start();
    }
}
