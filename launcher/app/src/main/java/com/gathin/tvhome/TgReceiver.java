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
            case "feed": tg.feed(); break;
            case "probe": probe(tg, i); break;
            default: tg.start();
        }
    }

    /** Отладка подкачки: скачать кусок файла с заданного места и понаблюдать, сколько готово. */
    private static void probe(TgCore tg, Intent i) {
        final int file = Integer.parseInt(String.valueOf(i.getStringExtra("file")));
        final long off = Long.parseLong(String.valueOf(i.getStringExtra("off"))), lim = Long.parseLong(String.valueOf(i.getStringExtra("lim")));
        final long at = i.getStringExtra("at") == null ? off : Long.parseLong(i.getStringExtra("at"));
        new Thread(() -> {
            android.util.Log.i("TgProbe", "download " + file + " off " + off + " lim " + lim + ": "
                    + tg.call(new org.drinkless.tdlib.TdApi.DownloadFile(file, 32, off, lim, false), 10_000));
            for (int k = 0; k < 15; k++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                org.drinkless.tdlib.TdApi.Object r = tg.call(new org.drinkless.tdlib.TdApi.GetFileDownloadedPrefixSize(file, at), 5_000);
                org.drinkless.tdlib.TdApi.Object f = tg.call(new org.drinkless.tdlib.TdApi.GetFile(file), 5_000);
                org.drinkless.tdlib.TdApi.LocalFile l = f instanceof org.drinkless.tdlib.TdApi.File ? ((org.drinkless.tdlib.TdApi.File) f).local : null;
                android.util.Log.i("TgProbe", k + "s at " + at + " ready " + (r instanceof org.drinkless.tdlib.TdApi.FileDownloadedPrefixSize ? ((org.drinkless.tdlib.TdApi.FileDownloadedPrefixSize) r).size : -1)
                        + (l == null ? "" : " active " + l.isDownloadingActive + " offset " + l.downloadOffset + " prefix " + l.downloadedPrefixSize + " total " + l.downloadedSize));
            }
        }, "tg-probe").start();
    }
}
