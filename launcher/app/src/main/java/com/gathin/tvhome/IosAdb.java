package com.gathin.tvhome;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Свой adb внутри лаунчера: подключается к отладке телевизора по 127.0.0.1:5555 и получает права оболочки.
 * Так пульт в телефоне управляет любым приложением, чистит кэш, ставит APK и делает снимок экрана.
 * Первый раз телевизор спросит «Разрешить отладку?» — отметить «Всегда» и согласиться.
 * Протокол adb: сообщения по 24 байта заголовка, вход по RSA-ключу. Один поток за раз.
 */
final class IosAdb {
    interface Status { void asking(); }

    private static final String TAG = "IosAdb";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX = 256 * 1024;
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static IosAdb instance;

    private final File dir;
    private KeyPair keys;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int theirMax = 4096;
    private int nextId = 1;
    /** Телевизор показал окно «Разрешить отладку?» и ждёт ответа. */
    volatile boolean asking;

    static synchronized IosAdb get(Context c) {
        if (instance == null) instance = new IosAdb(c.getApplicationContext());
        return instance;
    }

    private IosAdb(Context c) { dir = c.getFilesDir(); }

    // ---------- Команды ----------

    /** Вывод команды оболочки целиком (текст). Ошибка связи — исключение. */
    String shell(String cmd) throws Exception {
        return new String(exec(cmd, null, 0), StandardCharsets.UTF_8);
    }

    /** Вывод как есть (байты): для снимка экрана. */
    byte[] exec(String cmd) throws Exception { return exec(cmd, null, 0); }

    /** Команда со входом: тело запроса уходит команде в stdin (установка APK, загрузка файла). */
    byte[] exec(String cmd, InputStream input, long size) throws Exception {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        exec(cmd, input, size, got);
        return got.toByteArray();
    }

    /** Вывод сразу в поток: большой файл не держим в памяти. */
    synchronized void exec(String cmd, InputStream input, long size, OutputStream sink) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                connect();
                stream("exec:" + cmd, input, size, sink);
                return;
            } catch (Exception e) {
                close();
                // Вход дал только первую попытку: если поток не начался, повторяем один раз.
                // Связь могла устареть (телевизор спал): повторяем один раз, если ещё ничего не ушло и не пришло.
                boolean clean = input == null && sink instanceof ByteArrayOutputStream && ((ByteArrayOutputStream) sink).size() == 0;
                if (attempt > 0 || !clean) throw e;
            }
        }
    }

    synchronized boolean ready() {
        try { connect(); return true; } catch (Exception e) { Log.w(TAG, "connect: " + e); close(); return false; }
    }

    // ---------- Протокол ----------

    private void stream(String service, InputStream input, long size, OutputStream got) throws Exception {
        int local = nextId++;
        send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        socket.setSoTimeout(30000);
        // Ждём OKAY: канал открыт.
        while (true) {
            int[] h = new int[6];
            byte[] data = read(h);
            if (h[0] == OKAY && h[2] == local) { remote = h[1]; break; }
            if (h[0] == CLSE && h[2] == local) throw new Exception("служба не открылась: " + service);
            if (h[0] == WRTE && h[2] == local) { remote = h[1]; got.write(data); send(OKAY, local, remote, null); break; }
        }
        if (input != null) {
            byte[] chunk = new byte[Math.min(theirMax, MAX)];
            long left = size;
            while (left > 0) {
                int n = input.read(chunk, 0, (int) Math.min(chunk.length, left));
                if (n < 0) break;
                left -= n;
                byte[] part = new byte[n];
                System.arraycopy(chunk, 0, part, 0, n);
                send(WRTE, local, remote, part);
                // Следующий кусок — только после OKAY; попутный вывод сохраняем.
                while (true) {
                    int[] h = new int[6];
                    byte[] data = read(h);
                    if (h[2] != local) continue;
                    if (h[0] == OKAY) break;
                    if (h[0] == WRTE) { got.write(data); send(OKAY, local, remote, null); }
                    if (h[0] == CLSE) return;
                }
            }
        }
        socket.setSoTimeout(120000);
        while (true) {
            int[] h = new int[6];
            byte[] data = read(h);
            if (h[2] != local) continue;
            if (h[0] == WRTE) { got.write(data); send(OKAY, local, remote, null); }
            else if (h[0] == CLSE) { send(CLSE, local, remote, null); return; }
        }
    }

    private void connect() throws Exception {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        loadKeys();
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(10000);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
        send(CNXN, VERSION, MAX, "host::starwind\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            int[] h = new int[6];
            byte[] data = read(h);
            if (h[0] == CNXN) { theirMax = h[2]; asking = false; Log.i(TAG, "connected"); return; }
            if (h[0] != AUTH || h[1] != 1) continue;
            if (!signed) {
                signed = true;
                send(AUTH, 2, 0, sign(data));
            } else {
                // Ключ телевизору незнаком: показываем его, человек соглашается на экране.
                send(AUTH, 3, 0, publicKey());
                asking = true;
                s.setSoTimeout(90000);
            }
        }
    }

    void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
    }

    private void send(int cmd, int a0, int a1, byte[] data) throws Exception {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
        if (len > 0) b.put(data);
        out.write(b.array());
        out.flush();
    }

    /** Читает сообщение: h = команда, arg0, arg1; возвращает данные. */
    private byte[] read(int[] h) throws Exception {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        h[0] = b.getInt();
        h[1] = b.getInt();
        h[2] = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1 << 20) throw new Exception("плохой ответ adb");
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }

    // ---------- Ключ ----------

    private void loadKeys() throws Exception {
        if (keys != null) return;
        File pri = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            keys = new KeyPair(f.generatePublic(new X509EncodedKeySpec(bytes(pub))), f.generatePrivate(new PKCS8EncodedKeySpec(bytes(pri))));
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        try (OutputStream o = new FileOutputStream(pri)) { o.write(keys.getPrivate().getEncoded()); }
        try (OutputStream o = new FileOutputStream(pub)) { o.write(keys.getPublic().getEncoded()); }
    }

    /** Подпись токена, как у adb: PKCS#1 v1.5 поверх «SHA-1», которым считается сам токен. */
    private byte[] sign(byte[] token) throws Exception {
        int k = ((RSAPrivateKey) keys.getPrivate()).getModulus().bitLength() / 8;
        byte[] block = new byte[k];
        int pad = k - 3 - SHA1_INFO.length - token.length;
        block[0] = 0;
        block[1] = 1;
        for (int i = 0; i < pad; i++) block[2 + i] = (byte) 0xff;
        block[2 + pad] = 0;
        System.arraycopy(SHA1_INFO, 0, block, 3 + pad, SHA1_INFO.length);
        System.arraycopy(token, 0, block, 3 + pad + SHA1_INFO.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
        return c.doFinal(block);
    }

    /** Открытый ключ в формате adb (android_pubkey) + имя. */
    private byte[] publicKey() {
        RSAPublicKey k = (RSAPublicKey) keys.getPublic();
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        int words = 64;
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        int n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).intValue();
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv);
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(k.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " starwind@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    private static byte[] bytes(File f) throws Exception {
        try (InputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
}
