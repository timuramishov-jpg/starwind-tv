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
 *  цвета IosPalette, шрифт SF Pro. Влево и вправо на пульте листают заставки.
 *  Каждая кисть (Paint) — новая: общая кисть таскала выравнивание из прошлого кадра, и тексты уезжали за край. */
final class IosSaver extends View {
    static final String[] NAMES = {"Циферблат и календарь", "Прямоугольный циферблат", "Большие цифры",
            "Карта мира", "Виджеты", "Афиши кино", "Фото дня"};

    // Цвета — только из IosPalette.
    private static final int ORANGE = IosPalette.ORANGE, RED = IosPalette.RED, INK = IosPalette.INK,
            SUB = IosPalette.alpha(IosPalette.INK, 0.6f), DIM = IosPalette.alpha(IosPalette.INK, 0.32f);
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

    private int style;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Typeface semibold, medium, bold, expanded, rounded;
    private long started = SystemClock.uptimeMillis();
    private final boolean[] loaded = new boolean[NAMES.length];
    /** Афиши показываются, когда загружены все: иначе мозаика перетасовывается на глазах. */
    private boolean coversReady;
    private long coversAt;
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
            // Плавным стилям — 30 кадров, через час — 10: движение то же, процессор отдыхает.
            // Часам — раз в секунду, ровно на смене секунды.
            boolean smooth = style == 2 || style == 5 || style == 6;
            boolean calm = SystemClock.uptimeMillis() - started > 60 * 60_000L;
            main.postDelayed(this, smooth ? (calm ? 100 : 33) : 1000 - System.currentTimeMillis() % 1000);
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
        setBackgroundColor(IosPalette.BLACK);
        load();
    }

    int style() { return style; }

    static int stepFor(int key) {
        return key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : key == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
    }

    /** Листание пультом. Если в настройках выбрана одна заставка — запоминаем новую; «по очереди» не трогаем. */
    static void remember(Context c, IosSaver saver, int step) {
        saver.step(step);
        SharedPreferences s = c.getSharedPreferences("ios_saver", 0);
        if (s.getInt("style", -1) >= 0) s.edit().putInt("style", saver.style()).apply();
        else s.edit().putInt("turn", saver.style()).apply();
    }

    /** Листание пультом: следующая или предыдущая заставка, без перезапуска окна. */
    void step(int delta) {
        style = (style + delta + NAMES.length) % NAMES.length;
        started = SystemClock.uptimeMillis();
        load();
        invalidate();
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
        if (loaded[style]) return;
        loaded[style] = true;
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
            List<Bitmap> got = new ArrayList<>();
            for (String u : urls) {
                if (got.size() >= 40) break;
                Bitmap b = IosFeed.savedPicture(context, u, 200);
                if (b != null) got.add(b);
            }
            main.post(() -> { covers.addAll(got); coversReady = true; coversAt = SystemClock.uptimeMillis(); });
        }, "saver").start();
        if (style == 6) new Thread(this::loadPhotos, "saver").start();
    }

    /** Фото дня: последние картинки дня Bing с подписями мест, только места, без людей. Кэш на сутки. */
    private void loadPhotos() {
        File dir = new File(context.getCacheDir(), "saver");
        dir.mkdirs();
        int got = 0;
        for (int page : new int[]{0, 7}) {
            if (got >= 4) break;
            try {
                JSONArray images = new JSONObject(text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=" + page + "&n=8&mkt=ru-RU")).getJSONArray("images");
                for (int i = 0; i < images.length() && got < 4; i++) {
                    JSONObject im = images.getJSONObject(i);
                    String place = im.optString("copyright").replaceAll("\\s*\\(©.*$", "").trim();
                    if (IosText.hasPeople(place + " " + im.optString("title"))) continue;
                    String url = "https://www.bing.com" + im.getString("urlbase") + "_1920x1080.jpg";
                    File f = new File(dir, "p" + Integer.toHexString(url.hashCode()) + ".jpg");
                    if (!f.exists()) try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes(url)); }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                    if (b == null) continue;
                    got++;
                    main.post(() -> { photos.add(b); places.add(place); });
                }
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "photos: " + e);
            }
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

    private static Paint fill(int color) {
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        q.setColor(color);
        return q;
    }

    /** Кисть с градиентом: цвет непрозрачный, иначе градиент умножается на нулевую прозрачность и пропадает. */
    private static Paint shaded(Shader sh) {
        Paint q = fill(IosPalette.BLACK);
        q.setShader(sh);
        return q;
    }

    /** Текст не шире width: лишнее заменяется на «…». */
    private static String fit(String text, Paint q, float width) {
        if (text == null) return "";
        if (q.measureText(text) <= width) return text;
        String t = text;
        while (t.length() > 1 && q.measureText(t + "…") > width) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    private static Paint type(Typeface t, float size, int color) {
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
        c.drawCircle(cx, cy, w * 0.7f, fill(IosPalette.BLACK));
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
        title.setTextAlign(Paint.Align.LEFT);
        // Левый край названия месяца — по левому краю сетки дней, как в календаре iOS.
        c.drawText(MONTHS[t.get(Calendar.MONTH)], left + 22, top + 50, title);
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
            int col = IosPalette.alpha(INK, 0.28f);
            if (m % 5 == 0) { len = d * 0.36f; w = 10; col = IosPalette.alpha(INK, 0.72f); }
            if (m % 15 == 0) { len = (m == 15 || m == 45) ? 128 : 60; col = IosPalette.alpha(INK, 0.8f); }
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
        int[] colors = {IosPalette.alpha(IosPalette.BLUE, 0.7f), IosPalette.BLUE, IosPalette.alpha(IosPalette.BLUE, 0.7f), IosPalette.BLUE};
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
            Paint g = shaded(new LinearGradient(dot[0] - 62, dot[1] - 62, dot[0] + 62, dot[1] + 62,
                    new int[]{IosPalette.INK, IosPalette.SECONDARY, IosPalette.INK}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
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
        // Карта занимает верх, часы и дата — свою полосу снизу, без наложения.
        float x0 = 80, x1 = 1840, y0 = 70, y1 = 700;
        double lat0 = 78, lat1 = -56, lon0 = -170, lon1 = 190;
        int rows = LAND.length, cols = LAND[0].length();
        for (int r = 0; r < rows; r++) for (int col = 0; col < cols; col++) {
            if (LAND[r].charAt(col) != '#') continue;
            double lat = lat0 - r * (lat0 - lat1) / (rows - 1), lon = lon0 + col * (lon1 - lon0) / (cols - 1);
            double la = Math.toRadians(lat), h = Math.toRadians(lon - sublon);
            boolean day = Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(h) > 0;
            c.drawCircle(x0 + col * (x1 - x0) / (cols - 1), y0 + r * (y1 - y0) / (rows - 1), 9, fill(day ? IosPalette.alpha(INK, 0.88f) : IosPalette.alpha(INK, 0.3f)));
        }
        Path line = new Path();
        for (int i = 0; i <= 120; i++) {
            double lon = lon0 + i * (lon1 - lon0) / 120, h = Math.toRadians(lon - sublon);
            double lat = Math.toDegrees(Math.atan(-Math.cos(dec) * Math.cos(h) / Math.sin(dec)));
            float x = (float) (x0 + (lon - lon0) / (lon1 - lon0) * (x1 - x0));
            float y = (float) Math.max(y0 - 20, Math.min(y1 + 20, y0 + (lat0 - lat) / (lat0 - lat1) * (y1 - y0)));
            if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
        }
        Paint q = fill(IosPalette.alpha(INK, 0.45f));
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(5);
        q.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(line, q);
        float mx = (float) (x0 + (37.6 - lon0) / (lon1 - lon0) * (x1 - x0)), my = (float) (y0 + (lat0 - 55.75) / (lat0 - lat1) * (y1 - y0));
        c.drawCircle(mx, my, 15, fill(ORANGE));
        c.drawText("Москва, " + dateLine(t).toLowerCase(new Locale("ru")), 92, 1080 - 72 - 190 - 16, type(semibold, 36, SUB));
        c.drawText(hhmm(t), 88, 1080 - 72, type(semibold, 190, INK));
    }

    // 5. Виджеты: дата и большие часы, внизу три карточки одного вида — погода, продолжить просмотр, VPN.
    private void widgets(Canvas c, Calendar t) {
        c.drawText(dateLine(t), 102, 128, type(semibold, 40, SUB));
        c.drawText(hhmm(t), 96, 128 + 12 + 230, type(semibold, 256, INK));
        float top = 1080 - 88 - 316, x = 96, pad = 36;
        // Погода
        String[] w = IosWeather.details(context);
        RectF box = new RectF(x, top, x + 316, top + 316);
        c.drawRoundRect(box, 52, 52, fill(IosPalette.GRAY6));
        Paint city = type(semibold, 28, SUB);
        c.drawText(fit(w[4], city, 316 - 2 * pad), x + pad, top + 60, city);
        int icon = IosWeather.icon(context);
        if (icon != 0) drawIcon(c, icon, x + 316 - pad - 64, top + 28, 64);
        Paint deg = type(medium, 96, INK);
        c.drawText(w[0].isEmpty() ? "—" : w[0], x + pad - 4, top + 172, deg);
        Paint word = type(semibold, 28, INK);
        c.drawText(fit(w[1].isEmpty() ? "" : cap(w[1]), word, 316 - 2 * pad), x + pad, top + 228, word);
        if (!w[2].isEmpty()) {
            Paint range = type(medium, 26, SUB);
            c.drawText(fit("↑" + w[2] + "  ↓" + w[3], range, 316 - 2 * pad), x + pad, top + 272, range);
        }
        // Продолжить просмотр
        x += 316 + 28;
        // Средняя карточка тянется: три карточки занимают всю ширину между полями.
        float wide = 1920 - 96 - 316 - 28 - x;
        box = new RectF(x, top, x + wide, top + 316);
        c.drawRoundRect(box, 52, 52, fill(IosPalette.GRAY6));
        Paint head = type(semibold, 28, SUB);
        c.drawText("Продолжить просмотр", x + pad, top + 60, head);
        RectF cover = new RectF(x + pad, top + 88, x + pad + 300, top + 88 + 190);
        if (continueCover != null) {
            c.save();
            Path clip = new Path();
            clip.addRoundRect(cover, 24, 24, Path.Direction.CW);
            c.clipPath(clip);
            drawCover(c, continueCover, cover);
            c.restore();
        } else c.drawRoundRect(cover, 24, 24, fill(IosPalette.GRAY5));
        float tx = cover.right + 28, tw = x + wide - pad - tx;
        if (continueInfo != null) {
            Paint name = type(semibold, 30, INK);
            List<String> lines = wrap(continueInfo[0], name, tw, 3);
            for (int i = 0; i < lines.size(); i++) c.drawText(lines.get(i), tx, top + 124 + i * 40, name);
            Paint when = type(medium, 26, SUB);
            c.drawText(fit(continueInfo[1], when, tw), tx, top + 144 + lines.size() * 40, when);
        } else {
            Paint none = type(medium, 28, SUB);
            List<String> lines = wrap("Пока ничего не открывал", none, tw, 2);
            for (int i = 0; i < lines.size(); i++) c.drawText(lines.get(i), tx, top + 170 + i * 38, none);
        }
        // VPN
        x += wide + 28;
        box = new RectF(x, top, x + 316, top + 316);
        c.drawRoundRect(box, 52, 52, fill(IosPalette.GRAY6));
        boolean vpn = vpnOn();
        c.drawCircle(x + pad + 30, top + pad + 30, 30, fill(vpn ? IosPalette.GREEN : IosPalette.GRAY4));
        SharedPreferences home = context.getSharedPreferences("ios_home_v4", 0);
        c.drawText("VPN", x + pad, top + 196, type(semibold, 28, SUB));
        String place = home.getString("vpn_place", "");
        Paint state = type(semibold, 34, INK);
        c.drawText(fit(!vpn ? "Выключен" : place.isEmpty() ? "Включён" : place.split(",")[0], state, 316 - 2 * pad), x + pad, top + 238, state);
        String ping = lastPing(home);
        if (vpn && !ping.isEmpty()) c.drawText(ping, x + pad, top + 278, type(medium, 26, SUB));
    }

    /** Значок SF Symbols из ресурсов, перекрашенный в основной цвет текста. */
    private void drawIcon(Canvas c, int res, float x, float y, float size) {
        android.graphics.drawable.Drawable d = context.getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        d.setTint(INK);
        d.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size));
        d.draw(c);
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
        c.drawBitmap(b, null, new RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2), fill(IosPalette.INK));
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
            if (!coversReady || covers.isEmpty()) c.drawRoundRect(r, 20, 20, fill(IosPalette.GRAY6));
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
        // Афиши проявляются разом, когда загружены все.
        if (coversReady) {
            float a = Math.min(1f, (SystemClock.uptimeMillis() - coversAt) / 1200f);
            if (a < 1f) c.drawRect(0, 0, 1920, 1080, fill(IosPalette.alpha(IosPalette.BLACK, 1f - a)));
        }
        c.drawRect(0, 0, 1920, 1080, shaded(new LinearGradient(0, 1080, 0, 0,
                new int[]{IosPalette.SCRIM_STRONG, IosPalette.alpha(IosPalette.BLACK, 0.35f), IosPalette.alpha(IosPalette.BLACK, 0.15f)},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP)));
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
            c.drawBitmap(b, m, fill(IosPalette.INK));
            String place = places.get(i);
            c.drawRect(0, 760, 960, 1080, shaded(new RadialGradient(0, 1080, 900, IosPalette.alpha(IosPalette.BLACK, 0.5f), 0, Shader.TileMode.CLAMP)));
            String[] parts = place.split(",\\s*", 2);
            Paint name = type(semibold, 30, INK);
            name.setTextAlign(Paint.Align.LEFT);
            c.drawText(fit(parts[0], name, 840), 80, 1080 - 104, name);
            if (parts.length > 1) {
                Paint where = type(medium, 26, IosPalette.alpha(INK, 0.7f));
                c.drawText(fit(parts[1], where, 840), 80, 1080 - 64, where);
            }
        } else c.drawColor(IosPalette.GRAY6);
        c.drawRect(1100, 0, 1920, 360, shaded(new RadialGradient(1920, 0, 820, IosPalette.alpha(IosPalette.BLACK, 0.5f), 0, Shader.TileMode.CLAMP)));
        Paint clock = type(semibold, 96, INK);
        clock.setTextAlign(Paint.Align.RIGHT);
        c.drawText(hhmm(t), 1920 - 80, 64 + 88, clock);
        String w = IosWeather.details(context)[0];
        Paint date = type(semibold, 30, IosPalette.alpha(INK, 0.8f));
        date.setTextAlign(Paint.Align.RIGHT);
        String d = String.format(new Locale("ru"), "%1$ta, %1$te %1$tB", t);
        c.drawText(cap(d) + (w.isEmpty() ? "" : " · " + w), 1920 - 80, 64 + 88 + 48, date);
    }
}
