package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Заставки по макетам «Заставки Starwind»: квадратный циферблат с календарём, прямоугольный циферблат,
 *  большие цифры внахлёст, карта дня и ночи, виджеты, афиши кино, фото дня.
 *  Рисуется в координатах 1920×1080 и масштабируется под экран. Один стиль на всё:
 *  оранжевый #FF9F0A, красный #FF453A, белый #F5F5F7, приглушённый серый, SF Pro. */
final class IosSaver extends View {
    static final String[] NAMES = {"Циферблат и календарь", "Прямоугольный циферблат", "Большие цифры",
            "Карта мира", "Виджеты", "Афиши кино", "Фото дня"};

    private static final int ORANGE = 0xFFFF9F0A, RED = 0xFFFF453A, INK = 0xFFF5F5F7, SUB = 0x99F5F5F7, DIM = 0x52F5F5F7;
    private static final String TAG = "IosSaver";
    private static final String[] MONTHS = {"ЯНВАРЬ", "ФЕВРАЛЬ", "МАРТ", "АПРЕЛЬ", "МАЙ", "ИЮНЬ", "ИЮЛЬ", "АВГУСТ", "СЕНТЯБРЬ", "ОКТЯБРЬ", "НОЯБРЬ", "ДЕКАБРЬ"};
    private static final String[] DAYS = {"ВС", "ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ"};
    private static final String[] LAND = {
            "............#.###########...................................",
            "........###.#.##...#####..................########.##.......",
            ".##############.##..###.......#####.#######################.",
            ".##.#########...#.............#.######################.#....",
            ".#.....############.............###################...#.....",
            "........##########.#........#######################.........",
            "........#########..........##.###..###############.#........",
            "........########...........#.....###############.#..........",
            ".........######............####.################............",
            "..........##..............######################............",
            "...........##...#.........########.###..##..###.............",
            "..............#...........##########.....#..##..#...........",
            "................###.......###########....#......#...........",
            "...............#####..........######........#..#............",
            "...............#######........#####..........#....##........",
            "................#######........####.................#.......",
            "................######........#####.#............##.........",
            ".................#####.........###.............######.......",
            ".................###...........###.............######.......",
            ".................###...........##..............##.###.......",
            "................###......................................#..",
            "................##..........................................",
            "................#...........................................",
            "............................................................"};

    private final int style;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface semibold, medium, bold, expanded, rounded;
    private final long started = SystemClock.uptimeMillis();
    private final List<Bitmap> covers = new ArrayList<>();
    private final List<Bitmap> photos = new ArrayList<>();
    private final List<String> places = new ArrayList<>();
    private Bitmap continueCover;
    private String[] continueInfo;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            // Плавным стилям — 30 кадров, часам — раз в секунду, ровно на смене секунды.
            boolean smooth = style == 2 || style == 5 || style == 6;
            main.postDelayed(this, smooth ? 33 : 1000 - System.currentTimeMillis() % 1000);
        }
    };

    IosSaver(Context c, int style) {
        super(c);
        context = c;
        this.style = Math.max(0, Math.min(NAMES.length - 1, style));
        semibold = font(c, R.font.sf_semibold, Typeface.DEFAULT_BOLD);
        medium = font(c, R.font.sf_medium, Typeface.DEFAULT);
        bold = semibold;
        expanded = asset(c, "sf_expanded.ttf", null, medium);
        rounded = asset(c, "sf_rounded.ttf", "'wght' 800", semibold);
        setBackgroundColor(0xFF000000);
        load();
    }

    /** Какая заставка сейчас: номер из настроек, -1 — по очереди. */
    static int chosen(Context c) {
        SharedPreferences s = c.getSharedPreferences("ios_saver", 0);
        int pick = s.getInt("style", -1);
        if (pick >= 0) return pick;
        int next = (s.getInt("turn", -1) + 1) % NAMES.length;
        s.edit().putInt("turn", next).apply();
        return next;
    }

    private static Typeface font(Context c, int res, Typeface fallback) {
        try { return c.getResources().getFont(res); } catch (Exception e) { return fallback; }
    }

    private static Typeface asset(Context c, String name, String variation, Typeface fallback) {
        try {
            Typeface.Builder b = new Typeface.Builder(c.getAssets(), name);
            if (variation != null) b.setFontVariationSettings(variation);
            Typeface t = b.build();
            return t == null ? fallback : t;
        } catch (Exception e) {
            return fallback;
        }
    }

    void start() { running = true; main.post(tick); }

    void stop() { running = false; main.removeCallbacks(tick); }

    // ---------- Данные ----------

    private void load() {
        if (style == 4) {
            continueInfo = IosFeed.lastOpened(context);
            if (continueInfo != null && !continueInfo[2].isEmpty())
                new Thread(() -> {
                    Bitmap b = IosFeed.savedPicture(context, continueInfo[2], 440);
                    main.post(() -> { continueCover = b; invalidate(); });
                }, "saver").start();
        }
        if (style == 5) new Thread(() -> {
            List<String> urls = new ArrayList<>(IosFeed.savedPictures(context, IosFeed.KINOPOISK));
            urls.addAll(IosFeed.savedPictures(context, IosFeed.ZONA));
            for (String u : urls) {
                if (covers.size() >= 40) break;
                Bitmap b = IosFeed.savedPicture(context, u, 200);
                if (b != null) main.post(() -> covers.add(b));
            }
        }, "saver").start();
        if (style == 6) new Thread(this::loadPhotos, "saver").start();
    }

    /** Фото дня: восемь последних картинок дня Bing, с подписями мест. Кэш на сутки. */
    private void loadPhotos() {
        File dir = new File(context.getCacheDir(), "saver");
        dir.mkdirs();
        try {
            JSONArray images = new JSONObject(text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=ru-RU")).getJSONArray("images");
            for (int i = 0; i < images.length(); i++) {
                JSONObject im = images.getJSONObject(i);
                String url = "https://www.bing.com" + im.getString("urlbase") + "_1920x1080.jpg";
                File f = new File(dir, "p" + Integer.toHexString(url.hashCode()) + ".jpg");
                if (!f.exists()) try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes(url)); }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                String place = im.optString("copyright").replaceAll("\\s*\\(©.*$", "").trim();
                if (b != null) main.post(() -> { photos.add(b); places.add(place); });
                if (i >= 3) break;
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "photos: " + e);
        }
    }

    private static byte[] bytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    private static String text(String url) throws Exception { return new String(bytes(url), StandardCharsets.UTF_8); }

    // ---------- Рисование ----------

    @Override protected void onDraw(Canvas c) {
        float k = getWidth() / 1920f;
        c.save();
        c.scale(k, k);
        c.translate(0, (getHeight() / k - 1080) / 2);
        Calendar now = Calendar.getInstance();
        switch (style) {
            case 0: square(c, now); break;
            case 1: rect(c, now); break;
            case 2: overlap(c, now); break;
            case 3: map(c, now); break;
            case 4: widgets(c, now); break;
            case 5: posters(c, now); break;
            default: photo(c, now); break;
        }
        c.restore();
    }

    private Paint fill(int color) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private Paint type(Typeface t, float size, int color) {
        Paint q = fill(color);
        q.setTypeface(t);
        q.setTextSize(size);
        q.setLetterSpacing(size >= 100 ? -0.02f : 0f);
        return q;
    }

    private static String hhmm(Calendar t) {
        return String.format(Locale.ROOT, "%02d:%02d", t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE));
    }

    private static String dateLine(Calendar t) {
        String s = String.format(new Locale("ru"), "%1$tA, %1$te %1$tB", t);
        return s.substring(0, 1).toUpperCase(new Locale("ru")) + s.substring(1);
    }

    /** Риски по периметру прямоугольника: луч из центра до края. */
    private void tick(Canvas c, float cx, float cy, float hw, float hh, float deg, float len, float w, int color) {
        double th = Math.toRadians(deg);
        float dx = (float) Math.sin(th), dy = (float) -Math.cos(th);
        float t = Math.min(Math.abs(dx) > 1e-6 ? hw / Math.abs(dx) : 1e9f, Math.abs(dy) > 1e-6 ? hh / Math.abs(dy) : 1e9f);
        Paint q = fill(color);
        q.setStrokeWidth(w);
        q.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx + dx * t, cy + dy * t, cx + dx * (t - len), cy + dy * (t - len), q);
    }

    /** Стрелка: тонкая ножка у центра и толстая лопасть дальше, обе скруглённые. */
    private void hand(Canvas c, float cx, float cy, float deg, float stem, float from, float to, float w) {
        c.save();
        c.rotate(deg, cx, cy);
        Paint q = fill(INK);
        q.setStrokeCap(Paint.Cap.ROUND);
        q.setStrokeWidth(w * 0.32f);
        c.drawLine(cx, cy, cx, cy - stem, q);
        q.setStrokeWidth(w);
        c.drawLine(cx, cy - from, cx, cy - to, q);
        c.restore();
    }

    private void seconds(Canvas c, float cx, float cy, float deg, float tail, float length, float w) {
        c.save();
        c.rotate(deg, cx, cy);
        Paint q = fill(ORANGE);
        q.setStrokeCap(Paint.Cap.ROUND);
        q.setStrokeWidth(w);
        c.drawLine(cx, cy + tail, cx, cy - length, q);
        c.restore();
        c.drawCircle(cx, cy, w * 3, fill(INK));
        c.drawCircle(cx, cy, w * 1.7f, fill(ORANGE));
        c.drawCircle(cx, cy, w * 0.7f, fill(0xFF000000));
    }

    private static float[] angles(Calendar t) {
        float s = t.get(Calendar.SECOND), m = t.get(Calendar.MINUTE) + s / 60f, h = t.get(Calendar.HOUR) + m / 60f;
        return new float[]{h * 30, m * 6, s * 6};
    }

    // 1. Квадратный циферблат и календарь
    private void square(Canvas c, Calendar t) {
        float cx = 540, cy = 540, half = 364;
        for (int m = 0; m < 60; m++) {
            boolean hour = m % 5 == 0;
            tick(c, cx, cy, half, half, m * 6, hour ? (m % 15 == 0 ? 68 : 124) : 36, hour ? 8 : 6, hour ? INK : DIM);
        }
        Paint n = type(semibold, 108, INK);
        n.setTextAlign(Paint.Align.CENTER);
        float base = 38;
        c.drawText("12", cx, cy - 222 + base, n);
        c.drawText("3", cx + 200, cy + base, n);
        c.drawText("6", cx, cy + 222 + base, n);
        c.drawText("9", cx - 200, cy + base, n);
        float[] a = angles(t);
        hand(c, cx, cy, a[0], 48, 40, 224, 26);
        hand(c, cx, cy, a[1], 48, 40, 340, 26);
        seconds(c, cx, cy, a[2], 64, 356, 6);
        calendar(c, t, 990, 158);
    }

    private void calendar(Canvas c, Calendar t, float left, float top) {
        float cell = 114, width = cell * 7;
        Paint title = type(bold, 60, RED);
        title.setLetterSpacing(0.02f);
        c.drawText(MONTHS[t.get(Calendar.MONTH)], left + 16, top + 50, title);
        Paint head = type(semibold, 40, INK);
        head.setTextAlign(Paint.Align.CENTER);
        for (int d = 0; d < 7; d++) {
            head.setColor(d >= 5 ? SUB : INK);
            c.drawText(DAYS[(d + 1) % 7], left + cell * d + cell / 2, top + 140, head);
        }
        Calendar first = (Calendar) t.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7, days = t.getActualMaximum(Calendar.DAY_OF_MONTH), today = t.get(Calendar.DAY_OF_MONTH);
        Paint num = type(semibold, 50, INK);
        num.setTextAlign(Paint.Align.CENTER);
        for (int d = 1; d <= days; d++) {
            int i = offset + d - 1, col = i % 7, row = i / 7;
            float x = left + cell * col + cell / 2, y = top + 230 + row * 100;
            if (d == today) {
                c.drawCircle(x, y - 18, 46, fill(RED));
                num = type(semibold, 50, INK);
                num.setTextAlign(Paint.Align.CENTER);
            } else num.setColor(col >= 5 ? SUB : INK);
            c.drawText(String.valueOf(d), x, y, num);
        }
    }

    // 2. Прямоугольный циферблат во весь экран
    private void rect(Canvas c, Calendar t) {
        float cx = 960, cy = 540, hw = 900, hh = 492;
        for (int m = 0; m < 60; m++) {
            double th = Math.toRadians(m * 6);
            float dx = (float) Math.sin(th), dy = (float) -Math.cos(th);
            float d = Math.min(Math.abs(dx) > 1e-6 ? hw / Math.abs(dx) : 1e9f, Math.abs(dy) > 1e-6 ? hh / Math.abs(dy) : 1e9f);
            float len = 52, w = 6;
            int col = 0x47F5F5F7;
            if (m % 5 == 0) { len = d * 0.36f; w = 10; col = 0xB8F5F5F7; }
            if (m % 15 == 0) { len = (m == 15 || m == 45) ? 128 : 60; col = 0xCCF5F5F7; }
            tick(c, cx, cy, hw, hh, m * 6, len, w, col);
        }
        Paint n = type(expanded, 128, INK);
        n.setLetterSpacing(-0.01f);
        n.setTextAlign(Paint.Align.CENTER);
        c.drawText("12", cx, cy - hh * 0.7f + 46, n);
        c.drawText("6", cx, cy + hh * 0.7f + 46, n);
        c.drawText("3", cx + hw * 0.68f, cy + 46, n);
        c.drawText("9", cx - hw * 0.68f, cy + 46, n);
        Paint day = type(semibold, 52, RED);
        String dow = DAYS[t.get(Calendar.DAY_OF_WEEK) - 1];
        c.drawText(dow, 1220, cy + 18, day);
        day.setColor(INK);
        c.drawText(" " + t.get(Calendar.DAY_OF_MONTH), 1220 + day.measureText(dow), cy + 18, day);
        float[] a = angles(t);
        hand(c, cx, cy, a[0], 72, 64, 300, 32);
        hand(c, cx, cy, a[1], 72, 64, 472, 32);
        seconds(c, cx, cy, a[2], 64, 480, 6);
    }

    // 3. Большие цифры внахлёст: округлые, каждая со своим наклоном, медленно покачиваются, наложение «экран»
    private void overlap(Canvas c, Calendar t) {
        String s = hhmm(t);
        char[] d = {s.charAt(0), s.charAt(1), s.charAt(3), s.charAt(4)};
        float[] cxs = {390, 740, 1180, 1530}, base = {-11, 9, -7, 13}, swing = {5, -6, 7, -5}, period = {8, 10, 9, 11};
        int[] colors = {0xFF0A63C2, 0xFF52A3EE, 0xFF0A63C2, 0xFF52A3EE};
        float sec = (SystemClock.uptimeMillis() - started) / 1000f;
        Paint q = type(rounded, 860, 0);
        q.setLetterSpacing(0);
        q.setTextAlign(Paint.Align.CENTER);
        q.setBlendMode(BlendMode.SCREEN);
        for (int i = 0; i < 4; i++) {
            float phase = (float) (0.5 - 0.5 * Math.cos(sec / period[i] * Math.PI));
            c.save();
            c.rotate(base[i] + swing[i] * phase, cxs[i], 652);
            q.setColor(colors[i]);
            c.drawText(String.valueOf(d[i]), cxs[i], 832, q);
            c.restore();
        }
        float wob = (float) Math.sin(sec / 7 * Math.PI);
        for (float[] dot : new float[][]{{966, 442}, {954, 662}}) {
            Paint g = fill(0);
            g.setShader(new LinearGradient(dot[0] - 62, dot[1] - 62, dot[0] + 62, dot[1] + 62,
                    new int[]{0xFFDDE9F7, 0xFFBFC3C8, 0xFFC9D9EA}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(dot[0] + 3 * wob, dot[1] - 4 * wob, 62, g);
        }
    }

    // 4. Карта мира из точек: день ярче ночи, линия рассвета и заката, Москва оранжевой точкой
    private void map(Canvas c, Calendar t) {
        long now = System.currentTimeMillis();
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(now);
        int doy = utc.get(Calendar.DAY_OF_YEAR);
        double hours = utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0;
        double dec = Math.toRadians(-23.44 * Math.cos(Math.toRadians(360.0 / 365 * (doy + 10))));
        double sublon = (12 - hours) * 15;
        float x0 = 80, x1 = 1840, y0 = 80, y1 = 880;
        double lat0 = 78, lat1 = -56, lon0 = -170, lon1 = 190;
        int rows = LAND.length, cols = LAND[0].length();
        for (int r = 0; r < rows; r++) for (int col = 0; col < cols; col++) {
            if (LAND[r].charAt(col) != '#') continue;
            double lat = lat0 - r * (lat0 - lat1) / (rows - 1), lon = lon0 + col * (lon1 - lon0) / (cols - 1);
            double la = Math.toRadians(lat), h = Math.toRadians(lon - sublon);
            boolean day = Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(h) > 0;
            c.drawCircle(x0 + col * (x1 - x0) / (cols - 1), y0 + r * (y1 - y0) / (rows - 1), 9, fill(day ? 0xE0F5F5F7 : 0x4DF5F5F7));
        }
        Path line = new Path();
        for (int i = 0; i <= 120; i++) {
            double lon = lon0 + i * (lon1 - lon0) / 120, h = Math.toRadians(lon - sublon);
            double lat = Math.toDegrees(Math.atan(-Math.cos(dec) * Math.cos(h) / Math.sin(dec)));
            float x = (float) (x0 + (lon - lon0) / (lon1 - lon0) * (x1 - x0));
            float y = (float) Math.max(y0 - 40, Math.min(y1 + 40, y0 + (lat0 - lat) / (lat0 - lat1) * (y1 - y0)));
            if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
        }
        Paint q = fill(0x73F5F5F7);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(5);
        q.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(line, q);
        float mx = (float) (x0 + (37.6 - lon0) / (lon1 - lon0) * (x1 - x0)), my = (float) (y0 + (lat0 - 55.75) / (lat0 - lat1) * (y1 - y0));
        c.drawCircle(mx, my, 15, fill(ORANGE));
        c.drawText("Москва, " + dateLine(t).toLowerCase(new Locale("ru")), 92, 1080 - 80 - 224 - 12, type(semibold, 40, SUB));
        c.drawText(hhmm(t), 88, 1080 - 80, type(semibold, 224, INK));
    }

    // 5. Виджеты
    private void widgets(Canvas c, Calendar t) {
        Paint bg = fill(0);
        bg.setShader(new RadialGradient(1632, 108, 1500, new int[]{0xFF26324A, 0xFF10141D, 0xFF07080B}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 1080, bg);
        c.drawText(dateLine(t), 102, 128, type(semibold, 40, SUB));
        c.drawText(hhmm(t), 96, 128 + 12 + 230, type(semibold, 256, INK));
        float top = 1080 - 88 - 316, x = 96;
        // Погода
        String[] w = IosWeather.details(context);
        RectF box = new RectF(x, top, x + 316, top + 316);
        Paint g = fill(0);
        g.setShader(new LinearGradient(0, top, 0, top + 316, 0xFF3A5A8C, 0xFF26406B, Shader.TileMode.CLAMP));
        c.drawRoundRect(box, 52, 52, g);
        c.drawText(w[4], x + 32, top + 56, type(semibold, 28, INK));
        c.drawText(w[0].isEmpty() ? "—" : w[0].replace("+", ""), x + 28, top + 150, type(medium, 88, INK));
        c.drawText(w[1].isEmpty() ? "" : cap(w[1]), x + 32, top + 236, type(semibold, 26, INK));
        if (!w[2].isEmpty()) c.drawText("Макс.: " + w[2].replace("+", "") + ", мин.: " + w[3].replace("+", ""), x + 32, top + 276, type(medium, 26, 0xBFF5F5F7));
        // Продолжить просмотр
        x += 316 + 28;
        box = new RectF(x, top, x + 660, top + 316);
        c.drawRoundRect(box, 52, 52, fill(0x14FFFFFF));
        c.drawText("Продолжить просмотр", x + 36, top + 56, type(semibold, 26, SUB));
        RectF cover = new RectF(x + 36, top + 84, x + 336, top + 284);
        if (continueCover != null) {
            c.save();
            Path clip = new Path();
            clip.addRoundRect(cover, 24, 24, Path.Direction.CW);
            c.clipPath(clip);
            drawCover(c, continueCover, cover);
            c.restore();
        } else c.drawRoundRect(cover, 24, 24, fill(0xFF2B2F38));
        if (continueInfo != null) {
            List<String> lines = wrap(continueInfo[0], type(semibold, 30, INK), 270, 3);
            for (int i = 0; i < lines.size(); i++) c.drawText(lines.get(i), x + 364, top + 130 + i * 40, type(semibold, 30, INK));
            c.drawText(continueInfo[1], x + 364, top + 150 + lines.size() * 40, type(medium, 26, SUB));
        } else c.drawText("Пока ничего не открывал", x + 364, top + 190, type(medium, 28, SUB));
        // VPN
        x += 660 + 28;
        box = new RectF(x, top, x + 316, top + 316);
        c.drawRoundRect(box, 52, 52, fill(0x14FFFFFF));
        boolean vpn = vpnOn();
        c.drawCircle(x + 66, top + 66, 34, fill(vpn ? 0xFF30D158 : 0xFF3A3A3F));
        SharedPreferences home = context.getSharedPreferences("ios_home_v4", 0);
        c.drawText("VPN", x + 32, top + 196, type(semibold, 26, SUB));
        String place = home.getString("vpn_place", "");
        c.drawText(!vpn ? "Выключен" : place.isEmpty() ? "Включён" : place.split(",")[0], x + 32, top + 236, type(semibold, 34, INK));
        String ping = lastPing(home);
        if (vpn && !ping.isEmpty()) c.drawText(ping, x + 32, top + 276, type(medium, 26, SUB));
    }

    private static String cap(String s) { return s.substring(0, 1).toUpperCase(new Locale("ru")) + s.substring(1); }

    private boolean vpnOn() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (android.net.Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private static String lastPing(SharedPreferences home) {
        String[] parts = home.getString("vpn_hist", "").split(",");
        String last = parts[parts.length - 1];
        int colon = last.indexOf(':');
        return colon > 0 ? last.substring(colon + 1) + " мс" : "";
    }

    private static List<String> wrap(String s, Paint q, float width, int max) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String w : s.split(" ")) {
            String next = line.isEmpty() ? w : line + " " + w;
            if (q.measureText(next) <= width || line.isEmpty()) line = next;
            else { out.add(line); line = w; if (out.size() == max) break; }
        }
        if (out.size() < max && !line.isEmpty()) out.add(line);
        if (out.size() == max) {
            String last = out.get(max - 1);
            while (last.length() > 1 && q.measureText(last + "…") > width) last = last.substring(0, last.length() - 1);
            out.set(max - 1, last + "…");
        }
        return out;
    }

    private void drawCover(Canvas c, Bitmap b, RectF r) {
        float k = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k;
        c.drawBitmap(b, null, new RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), fill(0xFFFFFFFF));
    }

    // 6. Афиши кино: наклонная мозаика вертикальных афиш медленно плывёт
    private void posters(Canvas c, Calendar t) {
        float sec = (SystemClock.uptimeMillis() - started) / 1000f;
        float cw = 220, ch = 330, gap = 24, shift = (sec * 6) % (ch + gap);
        c.save();
        c.rotate(-8, 960, 540);
        int n = 0;
        for (int row = -2; row < 6; row++) for (int col = -1; col < 10; col++) {
            float x = -120 + col * (cw + gap) + (row % 2 == 0 ? 0 : cw / 2), y = -300 + row * (ch + gap) - shift;
            RectF r = new RectF(x, y, x + cw, y + ch);
            if (covers.isEmpty()) c.drawRoundRect(r, 20, 20, fill(0xFF1C1C1F));
            else {
                Bitmap b = covers.get(n++ % covers.size());
                c.save();
                Path clip = new Path();
                clip.addRoundRect(r, 20, 20, Path.Direction.CW);
                c.clipPath(clip);
                drawCover(c, b, r);
                c.restore();
            }
        }
        c.restore();
        Paint scrim = fill(0);
        scrim.setShader(new LinearGradient(0, 1080, 0, 0, new int[]{0xD9000000, 0x59000000, 0x26000000}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 1080, scrim);
        c.drawText(dateLine(t), 102, 1080 - 88 - 240 - 12, type(semibold, 40, SUB));
        c.drawText(hhmm(t), 96, 1080 - 88, type(semibold, 240, INK));
        Paint note = type(semibold, 32, SUB);
        note.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Новинки Кинопоиска и Зоны", 1920 - 96, 1080 - 104, note);
    }

    // 7. Фото дня: снимок во весь экран медленно наплывает, часы маленькие в углу
    private void photo(Canvas c, Calendar t) {
        if (!photos.isEmpty()) {
            float sec = (SystemClock.uptimeMillis() - started) / 1000f;
            int i = (int) (sec / 60) % photos.size();
            Bitmap b = photos.get(i);
            float zoom = 1f + 0.06f * ((sec % 60) / 60f);
            Matrix m = new Matrix();
            float k = Math.max(1920f / b.getWidth(), 1080f / b.getHeight()) * zoom;
            m.setScale(k, k);
            m.postTranslate((1920 - b.getWidth() * k) / 2, (1080 - b.getHeight() * k) / 2);
            c.drawBitmap(b, m, fill(0xFFFFFFFF));
            String place = places.get(i);
            Paint corner = fill(0);
            corner.setShader(new RadialGradient(0, 1080, 720, 0x73000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 760, 720, 1080, corner);
            String[] parts = place.split(",\\s*", 2);
            c.drawText(parts[0], 80, 1080 - 104, type(semibold, 30, INK));
            if (parts.length > 1) c.drawText(parts[1], 80, 1080 - 64, type(medium, 26, 0xB3F5F5F7));
        } else c.drawColor(0xFF2A3540);
        Paint corner = fill(0);
        corner.setShader(new RadialGradient(1920, 0, 720, 0x73000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(1200, 0, 1920, 320, corner);
        Paint clock = type(semibold, 96, INK);
        clock.setTextAlign(Paint.Align.RIGHT);
        c.drawText(hhmm(t), 1920 - 80, 64 + 88, clock);
        String w = IosWeather.details(context)[0];
        Paint date = type(semibold, 30, 0xCCF5F5F7);
        date.setTextAlign(Paint.Align.RIGHT);
        String d = String.format(new Locale("ru"), "%1$ta, %1$te %1$tB", t);
        c.drawText(cap(d) + (w.isEmpty() ? "" : " · " + w), 1920 - 80, 64 + 88 + 48, date);
    }
}
