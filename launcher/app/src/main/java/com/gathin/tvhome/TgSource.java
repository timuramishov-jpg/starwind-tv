package com.gathin.tvhome;

import android.media.MediaDataSource;
import android.util.Log;

import org.drinkless.tdlib.TdApi;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Видео из Telegram для плеера, без скачивания целиком: плеер просит кусок с любого места — просим TDLib качать
 * с этого места и отдаём байты, как только они легли на диск. Перемотка — просто новое место.
 * Место на телевизоре — 1,7 ГБ, а запись стрима бывает больше: накачали много — файл стираем и качаем с текущего места.
 */
final class TgSource extends MediaDataSource {
    private static final String TAG = "TgSource";
    private static final long KEEP = 700L << 20;
    private final TgCore tg;
    private final int fileId;
    private final long size;
    private final Object lock = new Object();
    private final Runnable wake = () -> { synchronized (lock) { lock.notifyAll(); } };
    private volatile boolean closed;
    private long requestedFrom = -1;
    private RandomAccessFile raf;
    private String rafPath;
    /** Сколько байт докачано подряд от точки, где сейчас читает плеер: для полоски загрузки. */
    volatile long buffered;

    TgSource(TgCore tg, int fileId, long size) {
        this.tg = tg;
        this.fileId = fileId;
        this.size = size;
        tg.watchFile(fileId, wake);
    }

    @Override public long getSize() { return size; }

    @Override public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
        if (closed) throw new IOException("closed");
        if (position >= size) return -1;
        length = (int) Math.min(length, size - position);
        long waitedSince = System.currentTimeMillis();
        while (!closed) {
            TdApi.Object r = tg.call(new TdApi.GetFileDownloadedPrefixSize(fileId, position), 10_000);
            long ready = r instanceof TdApi.FileDownloadedPrefixSize ? ((TdApi.FileDownloadedPrefixSize) r).size : 0;
            buffered = ready;
            if (ready > 0) {
                int n = (int) Math.min(length, ready);
                String path = raf != null ? rafPath : localPath();
                if (path != null) {
                    if (raf == null) {
                        raf = new RandomAccessFile(path, "r");
                        rafPath = path;
                    }
                    raf.seek(position);
                    int got = raf.read(buffer, offset, n);
                    if (got > 0) { ahead(position + got); return got; }
                }
            }
            ensureDownloading(position);
            synchronized (lock) { try { lock.wait(400); } catch (InterruptedException e) { throw new IOException(e); } }
            long waited = System.currentTimeMillis() - waitedSince;
            if (waited > 3000 && waited % 3000 < 450) {
                TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
                TdApi.LocalFile l = f instanceof TdApi.File ? ((TdApi.File) f).local : null;
                Log.i(TAG, "waiting at " + position + ", ready " + ready + (l == null ? "" : ", active " + l.isDownloadingActive
                        + " offset " + l.downloadOffset + " prefix " + l.downloadedPrefixSize + " total " + l.downloadedSize));
            }
            if (waited > 120_000) throw new IOException("Telegram не отдаёт видео");
        }
        throw new IOException("closed");
    }

    /** Качать с места, где читает плеер. Если оно далеко от того, откуда уже качаем, — переключаемся. */
    private void ensureDownloading(long position) {
        if (requestedFrom >= 0 && position >= requestedFrom && position - requestedFrom < (8L << 20)) return;
        TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
        if (f instanceof TdApi.File && ((TdApi.File) f).local.downloadedSize > KEEP) {
            // Диск не резиновый: накачанное стираем, качаем заново с текущего места.
            Log.i(TAG, "trim cache at " + position);
            tg.call(new TdApi.DeleteFile(fileId), 10_000);
            closeRaf();
        }
        TdApi.Object d = tg.call(new TdApi.DownloadFile(fileId, 32, position, 0, false), 10_000);
        Log.i(TAG, "download from " + position + " of " + size + ": " + (d instanceof TdApi.File ? "ok, prefix " + ((TdApi.File) d).local.downloadedPrefixSize + " offset " + ((TdApi.File) d).local.downloadOffset : d));
        requestedFrom = position;
    }

    /** Плеер ушёл дальше — сдвигаем точку, от которой считаем «качаем отсюда». */
    private void ahead(long pos) {
        if (requestedFrom >= 0 && pos > requestedFrom) requestedFrom = Math.max(requestedFrom, pos - (4L << 20));
    }

    private String localPath() {
        TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
        if (!(f instanceof TdApi.File)) return null;
        String p = ((TdApi.File) f).local.path;
        return p == null || p.isEmpty() ? null : p;
    }

    private void closeRaf() {
        try { if (raf != null) raf.close(); } catch (IOException ignored) { }
        raf = null;
        rafPath = null;
    }

    @Override public void close() {
        closed = true;
        tg.unwatchFile(fileId, wake);
        synchronized (lock) { lock.notifyAll(); }
        closeRaf();
    }

    /** Посмотрели — стираем, чтобы видео не копились на диске телевизора. */
    void forget() {
        new Thread(() -> {
            tg.call(new TdApi.CancelDownloadFile(fileId, false), 5_000);
            tg.call(new TdApi.DeleteFile(fileId), 10_000);
        }, "tg-forget").start();
    }
}
