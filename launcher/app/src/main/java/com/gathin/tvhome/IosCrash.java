package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Падения лаунчера пишутся в файл, который видно по adb без root:
 *  /sdcard/Android/data/com.gathin.tvhome/files/crash.txt (tv report его забирает). */
final class IosCrash {
    private IosCrash() { }

    static void install(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        File out = new File(dir, "crash.txt");
        Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                if (out.length() > 256 * 1024) out.delete();
                try (PrintWriter w = new PrintWriter(new FileWriter(out, true))) {
                    w.println("== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " поток " + t.getName());
                    e.printStackTrace(w);
                }
            } catch (Exception ignored) {
                Log.w("IosCrash", "не записалось: " + ignored);
            }
            if (next != null) next.uncaughtException(t, e);
        });
    }
}
