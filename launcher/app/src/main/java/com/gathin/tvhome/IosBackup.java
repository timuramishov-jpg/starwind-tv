package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Настройки лаунчера на случай переустановки. Копия лежит в Android/data/<пакет>/files/backup, откуда её
 *  забирает tv deploy; положенное в files/restore лаунчер забирает при запуске раньше, чем прочтёт настройки. */
final class IosBackup {
    private static final String TAG = "IosBackup";

    private IosBackup() { }

    private static File prefsDir(Context c) { return new File(c.getApplicationInfo().dataDir, "shared_prefs"); }

    /** Вызывать первым делом в onCreate: до первого getSharedPreferences. */
    static void restore(Context c) {
        try {
            File from = c.getExternalFilesDir("restore");
            File[] files = from == null ? null : from.listFiles((d, n) -> n.endsWith(".xml"));
            if (files == null || files.length == 0) return;
            File to = prefsDir(c);
            to.mkdirs();
            for (File f : files) {
                copy(f, new File(to, f.getName()));
                f.delete();
            }
            Log.i(TAG, "restored " + files.length);
        } catch (Exception e) {
            Log.w(TAG, "restore: " + e);
        }
    }

    /** Копия настроек; в фоне, раз в выход с экрана. */
    static void save(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                File to = app.getExternalFilesDir("backup");
                File[] files = prefsDir(app).listFiles((d, n) -> n.endsWith(".xml"));
                if (to == null || files == null) return;
                for (File f : files) copy(f, new File(to, f.getName()));
            } catch (Exception e) {
                Log.w(TAG, "save: " + e);
            }
        }, "backup").start();
    }

    private static void copy(File from, File to) throws Exception {
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (!tmp.renameTo(to)) throw new Exception("rename " + to);
    }
}
