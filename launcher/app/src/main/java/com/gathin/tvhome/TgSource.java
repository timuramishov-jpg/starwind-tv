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
    private long requestedFrom = -1;
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

    private long stalledAt = -1, stalledTime;

    /** Качать с места, где читает плеер. Если оно далеко от того, откуда уже качаем, — переключаемся.
     *  Застряли на одном месте дольше 2,5 с — просим именно его: TDLib, докачав диапазон, уходит качать
     *  начало файла и не возвращается за последний неполный кусок (так ждали хвост с описанием видео минутами). */
    private synchronized void ensureDownloading(long position, long waitedMs) {
        long now = System.currentTimeMillis();
        boolean stalled = waitedMs > 2500 && (position != stalledAt || now - stalledTime > 8000);
        if (covered(position) && !stalled) return;
        if (stalled) { stalledAt = position; stalledTime = now; }
        // Другой поток ждёт кусок, который сейчас и качается, — пусть докачается (до 4 секунд), потом наша очередь.
        if (waitedMs < 4000 && !stalled) for (Map.Entry<Thread, Long> w : waiting.entrySet())
            if (w.getKey() != Thread.currentThread() && covered(w.getValue())) return;
        TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
        if (f instanceof TdApi.File && ((TdApi.File) f).local.downloadedSize > KEEP) {
            // Диск не резиновый: накачанное стираем, качаем заново с текущего места.
            Log.i(TAG, "trim cache at " + position);
            tg.call(new TdApi.DeleteFile(fileId), 10_000);
            closeRaf();
            forgetKnown();
        }
        // С пределом до конца файла: без него TDLib после конца перескакивает в начало.
        TdApi.Object d = tg.call(new TdApi.DownloadFile(fileId, 32, position, size - position, false), 10_000);
        Log.i(TAG, "file " + fileId + " download from " + position + " of " + size + " [" + Thread.currentThread().getName() + "]: " + (d instanceof TdApi.File ? "ok, prefix " + ((TdApi.File) d).local.downloadedPrefixSize + " offset " + ((TdApi.File) d).local.downloadOffset : d));
        requestedFrom = position;
    }

    private boolean covered(long position) {
        return requestedFrom >= 0 && position >= requestedFrom && position - requestedFrom < (8L << 20);
    }

    /** Плеер ушёл дальше — сдвигаем точку, от которой считаем «качаем отсюда». */
    private synchronized void ahead(long pos) {
        if (requestedFrom >= 0 && pos > requestedFrom) requestedFrom = Math.max(requestedFrom, pos - (4L << 20));
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
