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
        Thread w = new Thread(this::watchdog, "tg-watchdog");
        w.setDaemon(true);
        w.start();
    }

    private volatile long readPos = -1;

    /** Сторож подкачки: держит скачанным запас впереди места просмотра. TDLib иногда долго (10–30 с) не отдаёт
     *  первый кусок нового диапазона, а перезапуск не помогает — поэтому следующий диапазон просим заранее,
     *  пока плеер доигрывает запас. */
    private void watchdog() {
        long lastPos = -1, lastEdge = -1, lastTotal = -1, still = 0;
        while (!closed) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long pos = readPos;
            // Смотрим, только когда плеер читает подряд: после перемотки он ещё дочитывает начало файла,
            // и если гоняться за каждым чтением, окно прыгает туда-сюда.
            boolean steady = pos >= 0 && lastPos >= 0 && pos >= lastPos && pos - lastPos < (32L << 20);
            lastPos = pos;
            if (!steady) continue;
            TdApi.Object r = tg.call(new TdApi.GetFileDownloadedPrefixSize(fileId, pos), 5_000);
            TdApi.Object f = tg.call(new TdApi.GetFile(fileId), 5_000);
            if (!(r instanceof TdApi.FileDownloadedPrefixSize) || !(f instanceof TdApi.File)) continue;
            long edge = pos + ((TdApi.FileDownloadedPrefixSize) r).size, total = ((TdApi.File) f).local.downloadedSize;
            if (edge >= size) continue;
            // Загрузка совсем встала (ни одного нового байта 4 с, а впереди дыра) — перезапускаем: так TDLib оживает.
            still = edge == lastEdge && total == lastTotal ? still + 1 : 0;
            lastEdge = edge;
            lastTotal = total;
            synchronized (this) {
                if (still >= 4) {
                    Log.i(TAG, "file " + fileId + " download stopped at " + edge + ", restarting");
                    tg.call(new TdApi.CancelDownloadFile(fileId, false), 5_000);
                    request(edge);
                    still = 0;
                } else if (edge - pos < AHEAD && !(edge >= winFrom && edge < winTo) && waiting.isEmpty()) request(edge);
            }
        }
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
                if (got > 0) { waiting.remove(Thread.currentThread()); readPos = position + got; return got; }
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

    /** TDLib качает кусками по 512 КБ; окно — сколько просим за раз; запас — сколько держим скачанным впереди. */
    private static final long PART = 512L << 10, WINDOW = 64L << 20, AHEAD = 48L << 20;
    private long winFrom = -1, winTo = -1, stalledAt = -1, stalledTime;

    /** Плеер упёрся в нескачанное: просим окно с этого места (если его уже качаем — ждём; раз в 15 с просим снова).
     *  Без предела TDLib раскидывает загрузку по всему файлу, и нужный плееру кусок ждёт минутами. */
    private synchronized void ensureDownloading(long position, long waitedMs) {
        long now = System.currentTimeMillis();
        boolean inside = position >= winFrom && position < winTo;
        boolean stalled = inside && waitedMs > 15000 && now - stalledTime > 15000;
        if (inside && !stalled) return;
        // Другой поток ждёт кусок из текущего окна — пусть докачается (до 4 секунд), потом наша очередь.
        if (!stalled && waitedMs < 4000) for (Map.Entry<Thread, Long> w : waiting.entrySet())
            if (w.getKey() != Thread.currentThread() && w.getValue() >= winFrom && w.getValue() < winTo) return;
        if (stalled) stalledTime = now;
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

    /** Перемотка ещё набирается (кнопку жмут), а место примерно известно: начинаем качать заранее,
     *  пока плеер не попросил, — первая порция нового места у TDLib приходит небыстро. */
    synchronized void prefetchAt(long position) {
        position = Math.max(0, Math.min(size - 1, position));
        if (position >= winFrom && position < winTo) return;
        request(Math.max(0, position - (2L << 20)));
    }

    private void request(long position) {
        long off = position - position % PART, lim = Math.min(WINDOW, size - off);
        TdApi.Object d = tg.call(new TdApi.DownloadFile(fileId, 32, off, lim, false), 10_000);
        winFrom = off;
        winTo = off + lim;
        Log.i(TAG, "file " + fileId + " window " + off + "+" + (lim >> 20) + "MB of " + size + (d instanceof TdApi.File ? "" : ": " + d));
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
