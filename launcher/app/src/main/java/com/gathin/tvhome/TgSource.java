package com.gathin.tvhome;

import android.media.MediaDataSource;
import android.util.Log;

import org.drinkless.tdlib.TdApi;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;

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
    /** Кто из потоков плеера сейчас ждёт байты и с какого места: плеер читает в несколько потоков (хвост с
     *  оглавлением и начало одновременно), а TDLib качает файл только с одного места — не дёргаем его туда-сюда. */
    private final java.util.Map<Thread, Long> waiting = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object fileLock = new Object();
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
            // Уже знаем, что этот кусок на диске, — читаем сразу: плеер делает тысячи мелких чтений, спрашивать TDLib на каждое долго.
            long ready = known(position);
            if (ready <= 0) {
                TdApi.Object r = tg.call(new TdApi.GetFileDownloadedPrefixSize(fileId, position), 10_000);
                ready = r instanceof TdApi.FileDownloadedPrefixSize ? ((TdApi.FileDownloadedPrefixSize) r).size : 0;
                if (ready > 0) remember(position, ready);
            }
            buffered = ready;
            if (ready > 0) {
                int n = (int) Math.min(length, ready);
                int got = -1;
                synchronized (fileLock) {  // один файл на все потоки плеера: seek и read не должны перемешаться
                    String path = raf != null ? rafPath : localPath();
                    if (path != null) {
                        if (raf == null) {
                            raf = new RandomAccessFile(path, "r");
                            rafPath = path;
                        }
                        raf.seek(position);
                        got = raf.read(buffer, offset, n);
                    }
                }
                if (got > 0) { waiting.remove(Thread.currentThread()); ahead(position + got); return got; }
            }
            waiting.put(Thread.currentThread(), position);
            ensureDownloading(position, System.currentTimeMillis() - waitedSince);
            synchronized (lock) { try { lock.wait(400); } catch (InterruptedException e) { throw new IOException(e); } }
            long waited = System.currentTimeMillis() - waitedSince;
            if (waited > 3000 && waited % 3000 < 450) {
                TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
                TdApi.LocalFile l = f instanceof TdApi.File ? ((TdApi.File) f).local : null;
                Log.i(TAG, "waiting at " + position + ", ready " + ready + (l == null ? "" : ", active " + l.isDownloadingActive
                        + " offset " + l.downloadOffset + " prefix " + l.downloadedPrefixSize + " total " + l.downloadedSize));
            }
            if (waited > 120_000) { waiting.remove(Thread.currentThread()); throw new IOException("Telegram не отдаёт видео"); }
        }
        throw new IOException("closed");
    }

    private final Object knownLock = new Object();
    private long knownFrom = -1, knownTo = -1;

    private long known(long position) {
        synchronized (knownLock) { return position >= knownFrom && position < knownTo ? knownTo - position : 0; }
    }

    private void remember(long from, long ready) {
        synchronized (knownLock) { knownFrom = from; knownTo = from + ready; }
    }

    private void forgetKnown() {
        synchronized (knownLock) { knownFrom = knownTo = -1; }
    }

    /** TDLib качает кусками по 512 КБ; окно — сколько просим за раз. */
    private static final long PART = 512L << 10, WINDOW = 24L << 20, EXTEND = 8L << 20;
    private long winFrom = -1, winTo = -1, stalledAt = -1, stalledTime;

    /** Качать с места, где читает плеер, ограниченным окном. Без предела TDLib раскидывает загрузку по всему файлу
     *  (в журнале: качает сотни мегабайт середины), а нужные плееру куски — хвост с описанием видео — ждут минутами.
     *  Застряли на месте дольше 3 с — отменяем и просим заново: зависшие запросы TDLib так сбрасываются. */
    private synchronized void ensureDownloading(long position, long waitedMs) {
        long now = System.currentTimeMillis();
        boolean inside = position >= winFrom && position < winTo;
        boolean stalled = inside && waitedMs > 5000 && (position != stalledAt || now - stalledTime > 6000);
        if (inside && !stalled) return;
        // Другой поток ждёт кусок из текущего окна — пусть докачается (до 4 секунд), потом наша очередь.
        if (!stalled && waitedMs < 4000) for (Map.Entry<Thread, Long> w : waiting.entrySet())
            if (w.getKey() != Thread.currentThread() && w.getValue() >= winFrom && w.getValue() < winTo) return;
        if (stalled) {
            stalledAt = position;
            stalledTime = now;
            tg.call(new TdApi.CancelDownloadFile(fileId, false), 5_000);
            Log.i(TAG, "file " + fileId + " stalled at " + position + ", restarting");
        }
        TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
        if (f instanceof TdApi.File && ((TdApi.File) f).local.downloadedSize > KEEP) {
            // Диск не резиновый: накачанное стираем, качаем заново с текущего места.
            Log.i(TAG, "trim cache at " + position);
            tg.call(new TdApi.DeleteFile(fileId), 10_000);
            closeRaf();
            forgetKnown();
        }
        request(position);
    }

    private void request(long position) {
        long off = position - position % PART, lim = Math.min(WINDOW, size - off);
        TdApi.Object d = tg.call(new TdApi.DownloadFile(fileId, 32, off, lim, false), 10_000);
        winFrom = off;
        winTo = off + lim;
        Log.i(TAG, "file " + fileId + " window " + off + "+" + (lim >> 20) + "MB of " + size + (d instanceof TdApi.File ? "" : ": " + d));
    }

    /** Плеер читает подряд и подходит к концу окна — заранее просим следующее, чтобы не было паузы. */
    private synchronized void ahead(long pos) {
        if (winTo < size && pos >= winFrom && pos < winTo && winTo - pos < EXTEND) request(pos);
    }

    private String localPath() {
        TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
        if (!(f instanceof TdApi.File)) return null;
        String p = ((TdApi.File) f).local.path;
        return p == null || p.isEmpty() ? null : p;
    }

    private void closeRaf() {
        synchronized (fileLock) { closeRafLocked(); }
    }

    private void closeRafLocked() {
        try { if (raf != null) raf.close(); } catch (IOException ignored) { }
        raf = null;
        rafPath = null;
    }

    @Override public void close() {
        closed = true;
        waiting.clear();
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
