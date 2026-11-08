package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.EditText;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Домашний экран: чёрный фон, вертикальный док, лента сервиса и ряд остальных приложений.
 * Серый логотип загорается цветом сервиса, и свет расходится по экрану. Фокус считается здесь, а не системой.
 */
public class IosHome extends Activity {
    // Все цвета — из IosPalette: одна система на весь телевизор.
    static final int INK = IosPalette.INK, INK_2 = IosPalette.SECONDARY, ICON_REST = IosPalette.GRAY1, GROUND = IosPalette.BLACK,
            PANEL = IosPalette.GRAY6, PILL = IosPalette.GRAY6, ROW_ON = IosPalette.INK, ROW_ON_TEXT = IosPalette.ON_LIGHT, SCRIM = IosPalette.SCRIM;
    static final float DOCK_CX = 95, DOCK_CELL = 116, DOCK_LOGO = 96, DOCK_REST = 0.875f, MAIN_X = 236,
            TITLE_Y = 170, PILL_Y = 300, CARDS_Y = 300, CARD_W = 880, CARD_H = 495, CARD_GAP = IosPalette.S4, EDGE_X = IosPalette.EDGE,
            HEADER_Y = 1180, APPS_Y = 1280, APPS_SCROLL = 1090;
    static final int MAX_DOCK = 7;
    static final int DOCK = 0, CARDS = 1, APPS = 2, TOP = 3;
    private static final String[] DEFAULT_DOCK = {"com.yandex.tv.ytplayer", "com.vk.tv", "ru.rutube.app.tv", "ru.kinopoisk.yandex.tv", "su.happ.proxyutility"};
    private static final String[] SKIP = {"com.cvte.", "com.mediatek.", "com.android.", "com.gathin.tvhome",
            "com.tim.adbmenu", "com.yandex.tv.home", "com.yandex.tv.settings", "com.yandex.tv.setupwizard",
            "com.yandex.tv.services", "com.yandex.io.", "com.yandex.webview", "ru.yandex.androidkeyboard",
            "com.yandex.devices.", "org.chromium", "com.yandex.tv.live", "com.yandex.tv.input"};
    private static final Map<String, int[]> BRAND = new HashMap<>();
    private static final Map<String, Integer> WORD = new HashMap<>();
    private static final int ZONA_PINK = IosPalette.PINK;
    static {
        WORD.put("com.yandex.tv.ytplayer", R.drawable.word_youtube);
        WORD.put("com.vk.tv", R.drawable.word_vk);
        WORD.put("su.happ.proxyutility", R.drawable.word_happ);
        WORD.put("ru.zonaplayer.app", R.drawable.word_zona);
        WORD.put("org.interhive.sota.connect", R.drawable.word_sota);
        BRAND.put("org.interhive.sota.connect", new int[]{R.drawable.logo_sota, IosPalette.YELLOW});
        BRAND.put("ru.zonaplayer.app", new int[]{R.drawable.logo_zona, ZONA_PINK});
        WORD.put("ru.rutube.app.tv", R.drawable.word_rutube);
        WORD.put("ru.rutube.app", R.drawable.word_rutube);
        WORD.put("ru.kinopoisk.yandex.tv", R.drawable.word_kino);
        BRAND.put("com.yandex.tv.ytplayer", new int[]{R.drawable.logo_youtube, IosPalette.RED});
        BRAND.put("com.vk.tv", new int[]{R.drawable.logo_vk, IosPalette.BLUE});
        BRAND.put("ru.rutube.app.tv", new int[]{R.drawable.logo_rutube, IosPalette.INDIGO});
        BRAND.put("ru.rutube.app", new int[]{R.drawable.logo_rutube, IosPalette.INDIGO});
        BRAND.put("su.happ.proxyutility", new int[]{R.drawable.logo_happ, IosPalette.GREEN});
        BRAND.put("me.zhanghai.android.files", new int[]{R.drawable.logo_files, IosPalette.BLUE});
        BRAND.put("ru.vk.store.tv", new int[]{R.drawable.logo_rustore, IosPalette.BLUE});
        BRAND.put("ru.kinopoisk.yandex.tv", new int[]{R.drawable.logo_kino, IosPalette.ORANGE});
        BRAND.put("org.thunderdog.challegram", new int[]{R.drawable.logo_telegram, IosPalette.BLUE});
        BRAND.put(IosFeed.TELEGRAM, new int[]{R.drawable.logo_telegram, IosPalette.BLUE});
        BRAND.put("com.yandex.browser.tv", new int[]{R.drawable.logo_browser, IosPalette.RED});
        BRAND.put("cm.aptoidetv.pt", new int[]{R.drawable.logo_aptoide, IosPalette.ORANGE});
        BRAND.put("com.moovi.tv.moovitv", new int[]{R.drawable.logo_moovi, IosPalette.RED});
    }

    /** Один пункт ленты. Источники данных подключаются отдельно; без них лента пустая, а не выдуманная. */
    static final class FeedItem {
        String title, subtitle;
        Bitmap image;
        String[] urls;
        Runnable onImage;
        boolean fetching;
        boolean poster;
        /** Вышло меньше суток назад: в ленте метка «новое». */
        boolean fresh;
        /** Когда вышло (мс), 0 — неизвестно. */
        long published;
        Intent open;
        Runnable action, onFail;
        String duration;
    }

    static final class App {
        String pkg, label;
        ComponentName component;
        boolean system;
        IosIcons.Logo logo;
    }

    private interface Action { void run(); }

    final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, App> apps = new HashMap<>(), cache = new HashMap<>();
    final List<App> dock = new ArrayList<>(), others = new ArrayList<>();
    final List<View> dockViews = new ArrayList<>(), appViews = new ArrayList<>(), cardViews = new ArrayList<>(),
            topViews = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);

    SharedPreferences prefs;
    FrameLayout root, dockLayer, content, cardsLayer, appsLayer, overlay;
    private TextView clock, date, temp, title, subtitle, action;
    private ImageView weatherIcon;
    private boolean restored;
    /** Три длительности на весь экран, кривая одна: ease. */
    static final int FAST = 160, NORMAL = 260, SLOW = 420;
    private static final int CARD_CLIP = 20, CLEAN = IosPalette.TEAL;
    static final int MENU_W = 620, MENU_ROW = 78, POSTER_W = 330;
    private final Map<String, Integer> cardMemory = new HashMap<>();
    private final List<ObjectAnimator> drift = new ArrayList<>();
    private ImageView glow;
    private ConnectivityManager.NetworkCallback vpnWatch;
    private boolean dirty = true, lastVpn, wasAway;
    private int ticks;
    private ImageView wordmark;
    private TextView caption, captionSub;
    final Map<View, TextView[]> cardLabels = new HashMap<>();
    /** Подписи карточек: в покое, у соседей выбранной. */
    static final float LABEL_REST = 0.66f, LABEL_DIM = 0.55f;
    IosFeed feed;
    final Map<String, String> feedShown = new HashMap<>();
    private ImageView washA, washB, spot;
    private int washNow;
    private float s;
    private int screenW;
    Typeface regular, medium, semibold;
    IosArt pics;
    int zone = DOCK, index = 0, selected = 0;
    private float scroll = 0;
    private boolean moving, washOnA, okDown, okLong;
    private long okDownAt;
    View focused;
    /** Что открыто в боковой панели: settings, wifi, search или пусто. */
    IosTools tools;
    private long lastInput = SystemClock.uptimeMillis();
    private boolean dimming;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateClock();
            ticks++;
            if (ticks % 12 == 0 && zone != CARDS && zone != APPS && !overlayOpen()) rotateFeeds();
            if (ticks % 4 == 0) loadAllFeeds();
            if (ticks % 20 == 0 && vpnOn()) vpn.checkSpeed();
            checkIdle();
            handler.postDelayed(this, 15000);
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getData() != null) cache.remove(i.getData().getSchemeSpecificPart());
            reload();
        }
    };

    final IosVpn vpn = new IosVpn(this);
    final IosSearch search = new IosSearch(this);
    boolean superDispatch(KeyEvent e) { return super.dispatchKeyEvent(e); }
    final IosInputs inputs = new IosInputs(this);
    final IosCards cards = new IosCards(this);
    final IosMenu menu = new IosMenu(this);
    final IosSettings settings = new IosSettings(this);
    int px(float v) { return Math.round(v * s); }
    boolean overlayOpen() { return overlay.getVisibility() == View.VISIBLE; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Настройки после переустановки: раньше всех, кто их читает.
        IosBackup.restore(this);
        IosCrash.install(this);
        prefs = getSharedPreferences("ios_home_v4", MODE_PRIVATE);
        feed = new IosFeed(this);
        screenW = getResources().getDisplayMetrics().widthPixels;
        s = screenW / 1920f;
        regular = font(R.font.sf_regular);
        medium = font(R.font.sf_medium);
        semibold = font(R.font.sf_semibold);
        pics = new IosArt(s, medium, semibold);
        root = new FrameLayout(this);
        // Корень берёт фокус, когда он уходит из поля поиска: кнопки пульта считаем сами.
        root.setFocusable(true);
        root.setBackgroundColor(GROUND);
        root.setClipChildren(false);
        setContentView(root);
        washA = wash();
        washB = wash();
        addScrims();
        spot = spotView();
        glow = wash();
        glow.setTranslationX(px(120));
        glow.setTranslationY(px(180));
        // Без аппаратных слоёв: каждый — полноэкранная текстура на 8–9 МБ видеопамяти, а рисовать 256-точечный
        // градиент заново дешевле. Четыре слоя стоили лаунчеру треть его памяти.
        for (ImageView v : new ImageView[]{washA, washB}) {
            swing(v, "translationX", v.getTranslationX(), 110, 23000);
            swing(v, "translationY", v.getTranslationY(), 70, 17000);
            swing(v, "scaleX", 1f, 0.08f, 29000);
            swing(v, "scaleY", 1f, 0.06f, 37000);
        }
        swing(glow, "translationX", glow.getTranslationX(), 240, 31000);
        swing(glow, "translationY", glow.getTranslationY(), 130, 26000);
        View grain = new View(this);
        grain.setBackground(grain());
        root.addView(grain, new FrameLayout.LayoutParams(-1, -1));
        buildChrome();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        tools = new IosTools(this);
        // Свой adb: один раз телевизор спросит «Разрешить отладку?» — пусть спросит сразу, пока человек у экрана.
        new Thread(() -> IosAdb.get(this).ready(), "adb").start();
        remote = new IosRemote(this);
        weather = new IosWeather(this);
        inputs.watch();
        // VPN отслеживается событиями системы, а не опросом.
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            vpnWatch = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { handler.post(IosHome.this::vpnChanged); }
                @Override public void onLost(Network n) { handler.postDelayed(IosHome.this::vpnChanged, 400); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpnWatch);
        }
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packages);

        inputs.unwatch();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && vpnWatch != null) cm.unregisterNetworkCallback(vpnWatch);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        // Сервер пульта живёт отдельно от лаунчера; проверяем, что он запущен, и обновляем для него данные.
        if (remote != null) { remote.ensureShell(); publishRemote(); }
        wake();
        root.setAlpha(1f);
        untrim();
        handler.post(ticker);
        for (ObjectAnimator a : drift) { if (a.isPaused()) a.resume(); else if (!a.isStarted()) a.start(); }
        if (wasAway) {
            wasAway = false;
            rotateFeeds();
        }
        if (dirty) {
            dirty = false;
            reload();
        }
        vpn.refreshVpn();
        IosMemory.autoClean(this);
        loadAllFeeds();
        autoVpn();
        if (!prefs.getBoolean("hint_seen", false)) handler.postDelayed(this::showHint, 2500);
    }

    /** Один раз после установки: что умеет экран и где что спрятано. */
    private void showHint() {
        if (overlayOpen() || prefs.getBoolean("hint_seen", false)) return;
        prefs.edit().putBoolean("hint_seen", true).apply();
        List<String> lines = Arrays.asList(
                "Вверх, вниз в ленте: сервисы",
                "Долгое ОК: двигать иконку",
                "Долгое ОК на YouTube: вход",
                "Метла: освободить память",
                "Понятно");
        List<Runnable> close = new ArrayList<>();
        for (int k = 0; k < lines.size(); k++) close.add(menu::close);
        menu.show("Подсказки", lines, close);
        menu.index = lines.size() - 1;
        menu.paint();
    }

    private void rotateFeeds() {
        for (App a : new ArrayList<>(dock)) if (IosFeed.supports(a.pkg)) {
            feed.rotate(a.pkg);
            cardMemory.remove(a.pkg);
            feedShown.remove(a.pkg);
        }
        if (!dock.isEmpty() && IosFeed.supports(dock.get(selected).pkg) && zone != CARDS && zone != APPS && !overlayOpen()) {
            int at = index;
            int z = zone;
            showSelected();
            if (z == DOCK) moveTo(DOCK, at);
        }
    }

    private void vpnChanged() {
        boolean on = vpnOn();
        if (on == lastVpn) return;
        lastVpn = on;
        feed.expire();
        vpn.refreshVpn();
        IosMemory.autoClean(this);
        loadAllFeeds();
    }

    private void loadAllFeeds() {
        for (App a : new ArrayList<>(dock)) if (IosFeed.supports(a.pkg)) loadFeed(a.pkg);
    }

    /** VPN включается один раз за включение телевизора. Выключенный вручную больше не трогаем. */
    private void autoVpn() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        if (prefs.getInt("vpn_boot", -1) == boot) return;
        prefs.edit().putInt("vpn_boot", boot).apply();
        // VPN держим включённым, только если ты сам его не выключал.
        for (long delay : new long[]{3000, 10000, 22000})
            handler.postDelayed(() -> { if (!vpnOn() && !prefs.getBoolean("vpn_user_off", false)) vpn.vpnCommand(true); }, delay);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        for (ObjectAnimator a : drift) a.pause();
        wasAway = true;
        IosBackup.save(this);
        super.onPause();
    }

    /** Лаунчер ушёл с экрана: отдаём обложки и кэш картинок, чтобы системе не пришлось выгружать нас целиком.
     *  При возврате карточки рядом с выбранной заполняются с диска за доли секунды. */
    private boolean trimmed;

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || trimmed) return;
        trimmed = true;
        for (View c : cardViews) if (c instanceof FrameLayout && c.getTag() instanceof FeedItem) cards.empty((FrameLayout) c, (FeedItem) c.getTag());
        feed.trim();
        Log.i("IosHome", "trim " + level);
    }

    private void untrim() {
        if (!trimmed) return;
        trimmed = false;
        if (zone == CARDS || !cardViews.isEmpty()) cards.lazy(zone == CARDS ? index : 0);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Поиск с телефона: сервер пульта открывает лаунчер с запросом.
        if (intent.getBooleanExtra("settings", false)) {
            handler.postDelayed(() -> { if (!overlayOpen() || !"settings".equals(menu.kind)) settings.show(); }, 250);
            return;
        }
        // Пульт: «Аккаунты → YouTube» — вход или выход здесь, на телевизоре.
        if (intent.getBooleanExtra("youtube_account", false)) {
            intent.removeExtra("youtube_account");
            handler.postDelayed(() -> { if (feed.signedIn()) { feed.signOut(); reload(); note("Вышел из YouTube"); } else startYouTubeSignIn(); }, 300);
            return;
        }
        String q = intent.getStringExtra("search");
        if (q != null && !q.trim().isEmpty()) { handler.postDelayed(() -> search.run(q.trim()), 300); return; }
        menu.close();
        moving = false;
        if (dirty || dock.isEmpty()) {
            zone = DOCK;
            index = 0;
            selected = 0;
            dirty = true;
            return;
        }
        selected = 0;
        showSelected();
        moveTo(DOCK, 0);
    }

    private Typeface font(int res) {
        try { return getResources().getFont(res); } catch (RuntimeException e) { return Typeface.DEFAULT; }
    }

    // ---------- Свет ----------

    private Bitmap radial(int[] stops, float[] at) {
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(128, 128, 128, stops, at, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, 256, 256, p);
        return b;
    }

    private ImageView wash() {
        ImageView v = new ImageView(this);
        v.setImageBitmap(radial(new int[]{IosPalette.alpha(INK, 1f), IosPalette.alpha(INK, 0.6f), IosPalette.alpha(INK, 0.2f), IosPalette.alpha(INK, 0f)}, new float[]{0f, 0.34f, 0.66f, 1f}));
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(1700), px(1300));
        v.setTranslationX(px(640));
        v.setTranslationY(px(-360));
        root.addView(v, lp);
        return v;
    }

    private ImageView spotView() {
        ImageView v = new ImageView(this);
        v.setImageBitmap(radial(new int[]{IosPalette.alpha(INK, 0.8f), IosPalette.alpha(INK, 0.33f), IosPalette.alpha(INK, 0f)}, new float[]{0f, 0.48f, 1f}));
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setAlpha(0f);
        root.addView(v, new FrameLayout.LayoutParams(px(300), px(300)));
        return v;
    }

    private void addScrims() {
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{GROUND, IosPalette.alpha(GROUND, 0.85f), IosPalette.alpha(GROUND, 0.25f), IosPalette.alpha(GROUND, 0f)}));
        root.addView(left, new FrameLayout.LayoutParams(px(1540), -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{GROUND, IosPalette.alpha(GROUND, 0.6f), IosPalette.alpha(GROUND, 0f)}));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, px(640));
        lp.gravity = Gravity.BOTTOM;
        root.addView(bottom, lp);
    }

    private Drawable grain() {
        int n = 128;
        int[] px = new int[n * n];
        Random r = new Random(11);
        for (int i = 0; i < px.length; i++) {
            int v = r.nextInt(2) * 255;
            px[i] = Color.argb(r.nextInt(7), v, v, v);
        }
        BitmapDrawable d = new BitmapDrawable(getResources(), Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888));
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        return d;
    }

    /** Плавное покачивание фона: только сдвиг и масштаб, без перерисовки. */
    private void swing(View v, String property, float center, float reach, long ms) {
        float d = property.startsWith("scale") ? reach : px(reach);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, property, center - d, center + d);
        a.setDuration(ms);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.setCurrentPlayTime(ms / 2);
        drift.add(a);
    }

    private void washOff() {
        washNow = 0;
        washA.animate().alpha(0f).setDuration(SLOW).start();
        washB.animate().alpha(0f).setDuration(SLOW).start();
        glow.animate().alpha(0f).setDuration(SLOW).start();
    }

    void washColor(int color) {
        if (color == washNow) return;
        washNow = color;
        ImageView on = washOnA ? washB : washA, off = washOnA ? washA : washB;
        washOnA = !washOnA;
        on.setImageTintList(ColorStateList.valueOf(color));
        on.animate().alpha(0.82f).setDuration(SLOW).setInterpolator(ease).start();
        off.animate().alpha(0f).setDuration(SLOW).start();
        glow.setImageTintList(ColorStateList.valueOf(color));
        glow.animate().alpha(0.26f).setDuration(SLOW).start();
    }

    private void spotAt(int color, float cx, float cy, float size) {
        spot.setImageTintList(ColorStateList.valueOf(color));
        float k = size / 300f;
        spot.setScaleX(k);
        spot.setScaleY(k);
        spot.setTranslationX(px(cx - 150));
        spot.setTranslationY(px(cy - 150));
        spot.animate().alpha(0.7f).setDuration(FAST).start();
    }

    // ---------- Каркас ----------

    TextView text(float size, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w <= 0 ? -2 : px(w), h <= 0 ? -2 : px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        return lp;
    }

    private FrameLayout layer() {
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        return f;
    }

    private void buildChrome() {
        content = layer();
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        title = text(IosPalette.DISPLAY, semibold, INK);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLetterSpacing(-0.025f);
        content.addView(title, at(MAIN_X, TITLE_Y, 1300, 0));
        wordmark = new ImageView(this);
        wordmark.setScaleType(ImageView.ScaleType.FIT_START);
        wordmark.setAdjustViewBounds(false);
        wordmark.setVisibility(View.GONE);
        content.addView(wordmark, at(MAIN_X, TITLE_Y + 8, 1100, 76));
        subtitle = text(IosPalette.BODY, regular, INK_2);
        content.addView(subtitle, at(MAIN_X, TITLE_Y + 118, 1100, 0));
        caption = text(IosPalette.HEADLINE, medium, INK);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(caption, at(MAIN_X, CARDS_Y + CARD_H + 30, 1180, 0));
        captionSub = text(IosPalette.CALLOUT, regular, INK_2);
        captionSub.setSingleLine(true);
        captionSub.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(captionSub, at(MAIN_X, CARDS_Y + CARD_H + 80, 1180, 0));
        // Уехавшие влево карточки обрезаются у дока, а фон и свечение остаются нетронутыми.
        FrameLayout cardsClip = new FrameLayout(this);
        cardsClip.setClipChildren(true);
        cardsClip.setClipBounds(new Rect(0, 0, px(1920 - MAIN_X + CARD_CLIP), px(1080)));
        content.addView(cardsClip, at(MAIN_X - CARD_CLIP, 0, 1920 - MAIN_X + CARD_CLIP, 1080));
        cardsLayer = layer();
        // Слой ленты шире всех карточек сразу: 15 широких карточек занимают почти 14 тысяч точек, дальние иначе обрезались.
        cardsClip.addView(cardsLayer, at(-(MAIN_X - CARD_CLIP), 0, MAIN_X + IosFeed.MAX_ITEMS * (CARD_W + CARD_GAP) + 1920, 1080));
        action = text(IosPalette.BODY, semibold, ROW_ON_TEXT);
        action.setText("Открыть");
        action.setGravity(Gravity.CENTER);
        action.setVisibility(View.GONE);
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView head = text(IosPalette.HEADLINE, medium, INK);
        head.setText("Приложения");
        section.addView(head);
        content.addView(section, at(MAIN_X, HEADER_Y, 1200, 56));
        appsLayer = layer();
        content.addView(appsLayer, new FrameLayout.LayoutParams(-1, -1));

        dockLayer = layer();
        root.addView(dockLayer, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(IosPalette.HEADLINE, medium, INK);
        date = text(IosPalette.CALLOUT, regular, INK_2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(20);
        status.addView(clock);
        status.addView(date, lp);
        // Погода: значок SF Symbols и температура, без слов.
        weatherIcon = new ImageView(this);
        weatherIcon.setImageTintList(ColorStateList.valueOf(INK_2));
        weatherIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(IosPalette.S4), px(IosPalette.S4));
        ip.leftMargin = px(IosPalette.S3);
        status.addView(weatherIcon, ip);
        temp = text(IosPalette.CALLOUT, regular, INK_2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = px(IosPalette.S1);
        status.addView(temp, tp);
        root.addView(status, at(EDGE_X, 36, 0, 0));
        addButton(1544, R.drawable.top_search, "Поиск", search::ask);
        addButton(1612, R.drawable.top_remote, "Пульт в телефоне", this::showRemoteCode);
        addButton(1680, R.drawable.top_input, "Источники", inputs::show);
        addButton(1748, R.drawable.top_wifi, "Wi-Fi", settings::showWifi);
        addButton(1816, R.drawable.top_settings, "Настройки", settings::show);

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(SCRIM);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addButton(float x, int icon, String name, Action action) {
        FrameLayout b = new FrameLayout(this);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(INK_2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(IosPalette.S4), px(IosPalette.S4));
        lp.gravity = Gravity.CENTER;
        b.addView(i, lp);
        b.setTag(new Object[]{name, action, i});
        root.addView(b, at(x, 26, 60, 60));
        topViews.add(b);
    }

    private void paintButton(View b, boolean on) {
        ImageView i = (ImageView) ((Object[]) b.getTag())[2];
        i.setImageTintList(ColorStateList.valueOf(on ? INK : INK_2));
        b.animate().scaleX(on ? 1.25f : 1f).scaleY(on ? 1.25f : 1f).setDuration(FAST).start();
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        String d = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now);
        String day = d.isEmpty() ? d : d.substring(0, 1).toUpperCase(Locale.getDefault()) + d.substring(1);
        date.setText(day);
        int icon = weather == null ? 0 : weather.icon();
        weatherIcon.setVisibility(icon == 0 ? View.GONE : View.VISIBLE);
        if (icon != 0) weatherIcon.setImageResource(icon);
        temp.setText(weather == null ? "" : weather.temp());
        if (weather != null) weather.refresh(line -> updateClock());
    }

    // ---------- Приложения ----------

    private static boolean skipped(String pkg) {
        for (String p : SKIP) if (pkg.startsWith(p)) return true;
        return false;
    }

    List<String> stored(String key) {
        String v = prefs.getString(key, "");
        return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(v.split(",")));
    }

    void store(String key, List<String> values) {
        prefs.edit().putString(key, TextUtils.join(",", values)).apply();
    }

    private IosIcons.Logo logoFor(String pkg, ResolveInfo ri, PackageManager pm) {
        int[] brand = BRAND.get(pkg);
        if (brand != null) return IosIcons.fromMask(BitmapFactory.decodeResource(getResources(), brand[0]), brand[1]);
        Drawable banner = null;
        try { banner = ri.activityInfo.loadBanner(pm); } catch (RuntimeException ignored) { }
        return IosIcons.logo(ri.loadIcon(pm), banner);
    }

    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        // Свой клиент Telegram — как отдельный сервис в доке, когда в нём есть ключ (вошли с пульта).
        if (getSharedPreferences("tg", MODE_MULTI_PROCESS).getInt("api_id", 0) != 0) {
            App t = cache.get(IosFeed.TELEGRAM);
            if (t == null) {
                t = new App();
                t.pkg = IosFeed.TELEGRAM;
                t.label = "Telegram";
                t.component = new ComponentName(getPackageName(), TgActivity.class.getName());
                t.system = true;
                int[] brand = BRAND.get(IosFeed.TELEGRAM);
                t.logo = IosIcons.fromMask(BitmapFactory.decodeResource(getResources(), brand[0]), brand[1]);
                cache.put(t.pkg, t);
            }
            apps.put(t.pkg, t);
        }
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (skipped(pkg) || apps.containsKey(pkg)) continue;
                App a = cache.get(pkg);
                if (a == null) {
                    a = new App();
                    a.pkg = pkg;
                    a.label = String.valueOf(ri.loadLabel(pm)).replace(".com", "");
                    a.component = new ComponentName(pkg, ri.activityInfo.name);
                    a.system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    a.logo = logoFor(pkg, ri, pm);
                    cache.put(pkg, a);
                }
                apps.put(pkg, a);
            }
        }
    }

    void reload() {
        loadApps();
        hidden.clear();
        hidden.addAll(stored("hidden"));
        List<String> order = new ArrayList<>();
        if (prefs.contains("dock")) order.addAll(stored("dock"));
        else {
            order.addAll(Arrays.asList(DEFAULT_DOCK));
        }
        // Один раз ставим Telegram в док — между Кинопоиском и Зоной (или в конец).
        if (apps.containsKey(IosFeed.TELEGRAM) && !prefs.getBoolean("tg_docked", false)) {
            prefs.edit().putBoolean("tg_docked", true).apply();
            if (!order.contains(IosFeed.TELEGRAM)) {
                int at = order.indexOf(IosFeed.KINOPOISK);
                order.add(at >= 0 ? at + 1 : order.size(), IosFeed.TELEGRAM);
                if (prefs.contains("dock")) store("dock", order);
            }
        }
        String zona = null;
        if (apps.containsKey("ru.zonaplayer.app")) zona = "ru.zonaplayer.app";
        if (zona != null && !order.contains(zona) && !prefs.getBoolean("zona_placed", false)) {
            int at = order.indexOf("ru.kinopoisk.yandex.tv");
            order.add(at < 0 ? order.size() : at + 1, zona);
            prefs.edit().putBoolean("zona_placed", true).apply();
            if (prefs.contains("dock")) store("dock", order);
        }
        if (apps.containsKey(SOTA) && !order.contains(SOTA) && !prefs.getBoolean("sota_placed", false)) {
            int at = order.indexOf(HAPP);
            order.add(at < 0 ? order.size() : at + 1, SOTA);
            prefs.edit().putBoolean("sota_placed", true).apply();
            if (prefs.contains("dock")) store("dock", order);
        }
        dock.clear();
        for (String p : order) if (apps.containsKey(p) && !hidden.contains(p) && dock.size() < MAX_DOCK) dock.add(apps.get(p));
        others.clear();
        Set<String> used = new HashSet<>();
        for (App a : dock) used.add(a.pkg);
        for (String p : stored("order")) {
            App a = apps.get(p);
            if (a != null && !hidden.contains(p) && used.add(p)) others.add(a);
        }
        List<App> rest = new ArrayList<>();
        for (App a : apps.values()) if (!hidden.contains(a.pkg) && !used.contains(a.pkg)) rest.add(a);
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        others.addAll(rest);
        // Система выгрузила лаунчер (нехватка памяти) — возвращаемся к тому сервису, где были, а не к первому.
        if (!restored) {
            restored = true;
            String at = prefs.getString("dock_at", "");
            for (int i = 0; i < dock.size(); i++) if (at.equals(dock.get(i).pkg)) selected = i;
        }
        selected = dock.isEmpty() ? 0 : Math.min(selected, dock.size() - 1);
        render();
        root.post(() -> {
            for (App a : new ArrayList<>(dock)) if (IosFeed.supports(a.pkg)) loadFeed(a.pkg);
        });
    }

    void saveLayout() {
        List<String> d = new ArrayList<>(), g = new ArrayList<>();
        for (App a : dock) d.add(a.pkg);
        for (App a : others) g.add(a.pkg);
        store("dock", d);
        store("order", g);
    }


    private final Map<Integer, Float> wordInk = new HashMap<>();
    private float wordRef;

    /** Площадь краски логотипа в шапке, как он ляжет в рамку 1100×76. */
    private float wordArea(int res) {
        Float got = wordInk.get(res);
        if (got != null) return got;
        Bitmap b = BitmapFactory.decodeResource(getResources(), res);
        float area = 0;
        if (b != null) {
            float fit = Math.min(1100f / b.getWidth(), 76f / b.getHeight());
            area = IosArt.inkShare(b) * b.getWidth() * fit * b.getHeight() * fit;
            b.recycle();
        }
        wordInk.put(res, area);
        return area;
    }

    private ImageView image(Bitmap b) {
        ImageView i = new ImageView(this);
        i.setImageBitmap(b);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return i;
    }

    private View logoView(App a) {
        FrameLayout box = new FrameLayout(this);
        box.addView(image(a.logo.mono), new FrameLayout.LayoutParams(-1, -1));
        ImageView color = image(a.logo.color);
        color.setAlpha(0f);
        box.addView(color, new FrameLayout.LayoutParams(-1, -1));
        box.setTag(a);
        return box;
    }

    private View iconTile(int icon, int color, Action action) {
        FrameLayout box = new FrameLayout(this);
        int pad = px(12);
        for (int tint : new int[]{ICON_REST, color}) {
            ImageView v = new ImageView(this);
            v.setImageResource(icon);
            v.setImageTintList(ColorStateList.valueOf(tint));
            v.setPadding(pad, pad, pad, pad);
            box.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        box.getChildAt(1).setAlpha(0f);
        box.setTag(new Object[]{"tile", action, color});
        return box;
    }

    /** Очистка: закрывает фоновые процессы всех приложений, кроме VPN и системных служб, и показывает, сколько памяти вернулось. */
    private void cleanUp() {
        menu.show("Очистка памяти", Collections.singletonList("Закрываю фоновые приложения"), Collections.singletonList(menu::close));
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        int n = IosMemory.killBackground(this, null);
        String problem = n == 0 ? "none" : null;
        feed.trim();
        System.gc();
        final int count = n;
        final String failed = problem;
        handler.postDelayed(() -> {
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            long freed = Math.max(0, after.availMem - before.availMem) / (1024 * 1024);
            Log.i("IosHome", "clean " + count + " packages, freed " + freed + " MB, problem " + failed);
            List<String> lines = new ArrayList<>();
            lines.add(failed != null && count == 0 ? "Не удалось: нет разрешения" : "Освобождено " + freed + " МБ");
            lines.add("Свободно " + after.availMem / (1024 * 1024) + " из " + after.totalMem / (1024 * 1024) + " МБ");
            lines.add("Готово");
            List<Runnable> close = new ArrayList<>();
            for (int k = 0; k < lines.size(); k++) close.add(menu::close);
            menu.show("Очистка памяти", lines, close);
            menu.index = lines.size() - 1;
            menu.paint();
        }, 1800);
    }

    /** Последняя кнопка дока: наведение прокручивает к общему списку приложений. */
    private View gridButton() {
        FrameLayout box = new FrameLayout(this);
        int pad = px(IosPalette.S2);
        for (int tint : new int[]{ICON_REST, INK}) {
            ImageView v = new ImageView(this);
            v.setImageResource(R.drawable.top_grid);
            v.setImageTintList(ColorStateList.valueOf(tint));
            v.setPadding(pad, pad, pad, pad);
            box.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        box.getChildAt(1).setAlpha(0f);
        box.setTag(new Object[]{"grid", (Action) () -> { if (!appViews.isEmpty()) moveTo(APPS, 0); }, INK});
        return box;
    }

    private float dockY(int i) {
        return (1080 - (dock.size() + 1) * DOCK_CELL) / 2f + i * DOCK_CELL + (DOCK_CELL - DOCK_LOGO) / 2f;
    }

    /** Сетка приложений подстраивается под их число (до 12 и метла): один ряд, если до шести,
     *  иначе два ряда во всю ширину. Карточки в духе tvOS: тёмная плашка, логотип по центру, подпись под ней. */
    private int appCols = 6;
    private float appW = 250, appH = 150;
    private static final float APP_GAP = 32, APP_LABEL = 64;
    private final List<TextView> appLabels = new ArrayList<>();

    private void appGrid(int n) {
        appCols = n <= 6 ? Math.max(4, n) : (n + 1) / 2;
        float width = 1920 - MAIN_X - EDGE_X;
        appW = (width - (appCols - 1) * APP_GAP) / appCols;
        appH = Math.round(appW * 0.6f);
    }

    private float appX(int i) { return MAIN_X + (i % appCols) * (appW + APP_GAP); }
    private float appY(int i) { return APPS_Y + (i / appCols) * (appH + APP_LABEL + APP_GAP); }

    /** Плашка приложения: детям 0 и 1 (серый и цветной логотип) даём место в центре. */
    private View appCard(View logo, String label, int i) {
        FrameLayout card = (FrameLayout) logo;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(IosPalette.GRAY6);
        bg.setCornerRadius(px(IosPalette.R_CARD));
        card.setBackground(bg);
        float box = Math.min(appH * 0.56f, appW * 0.5f);
        for (int c = 0; c < card.getChildCount(); c++) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(box), px(box));
            lp.gravity = Gravity.CENTER;
            card.getChildAt(c).setLayoutParams(lp);
        }
        appsLayer.addView(card, at(appX(i), appY(i), appW, appH));
        TextView name = text(IosPalette.CALLOUT, medium, INK);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setAlpha(LABEL_REST);
        // Высота задана явно: ниже края экрана «по содержимому» превращается в ноль, и подпись пропадает.
        appsLayer.addView(name, at(appX(i), appY(i) + appH + 16, appW, 40));
        appLabels.add(name);
        return card;
    }

    private void render() {
        handler.post(this::publishRemote);
        focused = null;
        dockLayer.removeAllViews();
        dockViews.clear();
        // Значки дока одного оптического веса: у плотного квадрата ВК и тонкой иконки разная «масса» краски.
        float[] ink = new float[dock.size()];
        for (int i = 0; i < dock.size(); i++) ink[i] = IosArt.inkShare(dock.get(i).logo.mono);
        float[] sorted = ink.clone();
        Arrays.sort(sorted);
        float ref = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
        for (int i = 0; i < dock.size(); i++) {
            View v = logoView(dock.get(i));
            v.setScaleX(DOCK_REST);
            v.setScaleY(DOCK_REST);
            if (ink[i] > 0 && ref > 0) {
                float k = Math.max(0.86f, Math.min(1.12f, (float) Math.sqrt(ref / ink[i])));
                ViewGroup box = (ViewGroup) v;
                for (int c = 0; c < box.getChildCount(); c++) { box.getChildAt(c).setScaleX(k); box.getChildAt(c).setScaleY(k); }
            }
            dockLayer.addView(v, at(DOCK_CX - DOCK_LOGO / 2f, dockY(i), DOCK_LOGO, DOCK_LOGO));
            dockViews.add(v);
        }
        View grid = gridButton();
        dockLayer.addView(grid, at(DOCK_CX - DOCK_LOGO / 2f, dockY(dock.size()), DOCK_LOGO, DOCK_LOGO));
        dockViews.add(grid);
        appsLayer.removeAllViews();
        appViews.clear();
        appLabels.clear();
        List<App> all = new ArrayList<>(dock);
        all.addAll(others);
        appGrid(all.size() + 1);
        for (int i = 0; i < all.size(); i++) appViews.add(appCard(logoView(all.get(i)), all.get(i).label, i));
        appViews.add(appCard(iconTile(R.drawable.top_clean, CLEAN, this::cleanUp), "Очистить память", appViews.size()));
        showSelected();
        if (views(zone).isEmpty()) { zone = !dockViews.isEmpty() ? DOCK : !appViews.isEmpty() ? APPS : TOP; index = 0; }
        moveTo(zone, Math.max(0, Math.min(index, views(zone).size() - 1)));
    }

    static final String HAPP = "su.happ.proxyutility", SOTA = "org.interhive.sota.connect";
    private static final int VPN_H = 280;

    boolean vpnOn() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private void showFeed(App a) {
        if ("off".equals(feed.mode(a.pkg))) {
            subtitle.setText("Лента выключена: долгое ОК на значке, чтобы включить");
            addPill("Открыть", a.logo.accent, () -> launch(a));
            return;
        }
        List<FeedItem> items = feed.cached(a.pkg);
        subtitle.setText("");
        for (int i = 0; i < items.size(); i++) cards.add(items.get(i), i);
        if (items.isEmpty()) {
            // Сервис не ответил: говорим об этом, а не показываем пустоту.
            String err = feed.error(a.pkg);
            if (err != null) subtitle.setText(a.label + " сейчас не отвечает. Лента появится, как только ответит");
            addPill("Открыть", a.logo.accent, () -> launch(a));
        }
        loadFeed(a.pkg);
        feed.wantPictures(a.pkg);
    }

    private void loadFeed(String pkg) {
        feed.load(pkg, false, (list, error) -> {
            StringBuilder state = new StringBuilder(String.valueOf(error == null));
            for (FeedItem f : list) state.append('|').append(f.title);
            boolean current = !dock.isEmpty() && pkg.equals(dock.get(selected).pkg);
            if (state.toString().equals(feedShown.get(pkg))) return;
            if (current && (zone == APPS || overlayOpen())) return;
            feedShown.put(pkg, state.toString());
            if (current) {
                int at = index;
                boolean inCards = zone == CARDS;
                showSelected();
                if (inCards && !cardViews.isEmpty()) moveTo(CARDS, Math.min(at, cardViews.size() - 1));
            }
        });
    }

    private final android.view.animation.LinearInterpolator linear = new android.view.animation.LinearInterpolator();
    private final Runnable settle = () -> {
        if (zone != CARDS || focused == null || !(focused.getTag() instanceof FeedItem) || dock.isEmpty()) return;
        FeedItem item = (FeedItem) focused.getTag();
        int tone = IosArt.coverTone(item.image), color = tone != 0 ? tone : dock.get(selected).logo.accent;
        washColor(color);
        spotAt(color, MAIN_X + (item.poster ? POSTER_W : CARD_W) / 2f, CARDS_Y + CARD_H / 2f, item.poster ? 900 : 1500);
    };
    // ---------- Пульт в телефоне, поиск, входы HDMI ----------

    private IosRemote remote;

    /** Данные для страницы пульта: приложения дока и сетки с цветными логотипами, названия всех пакетов, состояние. */
    private void publishRemote() {
        if (remote == null || dock.isEmpty()) return;
        List<IosRemote.Entry> list = new ArrayList<>();
        List<App> all = new ArrayList<>(dock);
        all.addAll(others);
        for (App a : all) {
            IosRemote.Entry e = new IosRemote.Entry();
            e.pkg = a.pkg;
            e.label = a.label;
            e.component = a.component == null ? null : a.component.flattenToShortString();
            e.accent = a.logo == null ? IosPalette.GRAY1 : a.logo.accent;
            e.logo = a.logo == null ? null : a.logo.color;
            list.add(e);
        }
        remote.publish(list);
        remote.state(prefs.getString("vpn_place", ""), IosSleep.at(this), feed.signedIn());
    }
    private IosWeather weather;

    /** Команды со страницы пульта в телефоне (главный поток). */

    private static String say(String s) { return "{\"say\":" + org.json.JSONObject.quote(s) + "}"; }

    private void bringHome() {
        startActivity(new Intent(this, IosHome.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** Большой QR-код: навёл камеру телефона — открылся пульт. */
    void showRemoteCode() {
        String url = remote == null ? null : remote.address();
        if (url == null) { note("Телевизор не в сети"); return; }
        Bitmap qr = IosQr.make(url, px(440));
        overlay.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = text(IosPalette.TITLE, semibold, INK);
        t.setText("Пульт в телефоне");
        box.addView(t);
        TextView h = text(IosPalette.CALLOUT, regular, INK_2);
        h.setText("Наведи камеру телефона на код");
        h.setPadding(0, px(IosPalette.S1), 0, px(IosPalette.S4));
        box.addView(h);
        FrameLayout plate = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(IosPalette.INK);
        g.setCornerRadius(px(IosPalette.R_PILL));
        plate.setBackground(g);
        plate.setPadding(px(28), px(28), px(28), px(28));
        ImageView code = new ImageView(this);
        code.setImageBitmap(qr);
        plate.addView(code, new FrameLayout.LayoutParams(px(440), px(440)));
        box.addView(plate, new LinearLayout.LayoutParams(-2, -2));
        TextView a = text(IosPalette.FOOTNOTE, regular, INK_2);
        a.setText(url.replaceAll("\\?k=.*", ""));
        a.setPadding(0, px(28), 0, 0);
        box.addView(a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER;
        overlay.addView(box, lp);
        overlay.setVisibility(View.VISIBLE);
    }

    /** Сообщение лаунчера: тёмная плашка сверху по центру в общем стиле (вместо системного тоста). */
    private TextView noteView;
    private final Runnable noteOff = () -> { if (noteView != null) noteView.animate().alpha(0f).setDuration(NORMAL).start(); };

    void note(String text) {
        if (noteView == null) {
            noteView = text(IosPalette.CALLOUT, semibold, INK);
            noteView.setPadding(px(IosPalette.S4), px(IosPalette.S2), px(IosPalette.S4), px(IosPalette.S2));
            GradientDrawable g = new GradientDrawable();
            g.setColor(IosPalette.alpha(IosPalette.GRAY6, 0.95f));
            g.setCornerRadius(px(IosPalette.R_PILL));
            noteView.setBackground(g);
            noteView.setAlpha(0f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = px(IosPalette.S4);
            root.addView(noteView, lp);
        }
        noteView.bringToFront();
        noteView.setText(text);
        noteView.animate().alpha(1f).setDuration(FAST).start();
        handler.removeCallbacks(noteOff);
        handler.postDelayed(noteOff, Math.max(2500, text.length() * 70));
    }

    void startYouTubeSignIn() {
        final TextView[] hint = new TextView[1];
        overlay.removeAllViews();
        float w = 820, x = (1920 - w) / 2f, y = 310;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(px(IosPalette.S5), px(IosPalette.S5), px(IosPalette.S5), px(IosPalette.S5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL);
        g.setCornerRadius(px(IosPalette.R_PILL));
        panel.setBackground(g);
        TextView code = text(IosPalette.DISPLAY, semibold, INK);
        code.setText("Подождите");
        code.setSingleLine(true);
        TextView tip = text(IosPalette.BODY, regular, INK_2);
        tip.setText("Получаю код от Google");
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(IosPalette.S3);
        panel.addView(code);
        panel.addView(tip, lp);
        overlay.addView(panel, at(x, y, w, 0));
        overlay.setVisibility(View.VISIBLE);
        menu.acts.clear();
        menu.views.clear();
        hint[0] = tip;
        feed.startSignIn(new IosFeed.CodeSink() {
            @Override public void code(String c, String url) {
                code.setText(c);
                hint[0].setText("На телефоне открой " + url.replace("https://", "") + ", выбери аккаунт и введи этот код");
            }
            @Override public void signedIn() {
                menu.close();
                feed.forget(IosFeed.YOUTUBE);
                reload();
            }
            @Override public void failed(String error) {
                code.setText("Не вышло");
                hint[0].setText("Проверь VPN и попробуй снова. Причина: " + error);
            }
        });
    }

    private void addPill(String text, int fill, Action action) {
        TextView pill = text(IosPalette.BODY, semibold, INK);
        pill.setText(text);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(px(IosPalette.S5), 0, px(IosPalette.S5), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(PILL);
        g.setCornerRadius(px(IosPalette.R_PILL));
        pill.setBackground(g);
        pill.setTag(new Object[]{text, action, fill});
        cardsLayer.addView(pill, at(MAIN_X, PILL_Y, 0, 80));
        cardViews.add(pill);
    }

    /** Заголовок секции: полный логотип в её цвете, а если логотипа нет, название тем же цветом. */
    private void showBrand(App a) {
        title.setSingleLine(true);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(IosPalette.DISPLAY));
        title.setTextColor(a == null ? INK : a.logo.accent);
        Integer mark = a == null ? null : WORD.get(a.pkg);
        if (mark != null) {
            wordmark.setImageResource(mark);
            // Логотипы в шапке одного оптического веса: YouTube не должен быть «громче» пиктограммы ВК.
            if (wordRef == 0) {
                List<Float> all = new ArrayList<>();
                for (int res : WORD.values()) all.add(wordArea(res));
                Collections.sort(all);
                wordRef = all.get(all.size() / 2);
            }
            float area = wordArea(mark), k = area > 0 ? Math.max(0.8f, Math.min(1.2f, (float) Math.sqrt(wordRef / area))) : 1f;
            wordmark.setPivotX(0);
            wordmark.setPivotY(px(38));
            wordmark.setScaleX(k);
            wordmark.setScaleY(k);
            wordmark.setImageTintList(mark == R.drawable.word_vk ? null : ColorStateList.valueOf(a.logo.accent));
            wordmark.setVisibility(View.VISIBLE);
            title.setText("");
        } else {
            wordmark.setVisibility(View.GONE);
            title.setText(a == null ? "" : a.label);
        }
    }

    private void paintPill(View v, boolean on) {
        Object[] tag = (Object[]) v.getTag();
        if (tag.length == 5) {
            ((GradientDrawable) v.getBackground()).setColor(on ? IosPalette.GRAY5 : PILL);
            v.animate().scaleX(on ? 1.03f : 1f).scaleY(on ? 1.03f : 1f).setDuration(FAST).start();
            return;
        }
        GradientDrawable g = (GradientDrawable) v.getBackground();
        g.setColor(on ? (int) ((Object[]) v.getTag())[2] : PILL);
        ((TextView) v).setTextColor(on ? ROW_ON_TEXT : INK);
        v.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(FAST).start();
    }

    /** Заголовок и цвет экрана берутся из выбранного в доке сервиса. */
    void showSelected() {
        cardsLayer.removeAllViews();
        cardViews.clear();
        cardLabels.clear();
        cards.lit = null;
        cardsLayer.setTranslationX(0);
        cardsLayer.animate().cancel();
        cardsLayer.setTranslationY(0);
        cardsLayer.setAlpha(1f);
        if (dock.isEmpty()) { showBrand(null); subtitle.setText(""); return; }
        App a = dock.get(selected);
        if (!a.pkg.equals(prefs.getString("dock_at", ""))) prefs.edit().putString("dock_at", a.pkg).apply();
        Set<String> near = new HashSet<>();
        for (int k = Math.max(0, selected - 1); k <= Math.min(dock.size() - 1, selected + 1); k++) near.add(dock.get(k).pkg);
        feed.keepImages(near);
        showBrand(a);
        if (HAPP.equals(a.pkg) || SOTA.equals(a.pkg)) {
            subtitle.setText("");
            vpn.showVpn(a);
        } else {
            subtitle.setText("");
            if (IosFeed.supports(a.pkg)) showFeed(a);
            else addPill("Открыть", a.logo.accent, () -> launch(a));
        }
        washColor(a.logo.accent);
        paintDock();
    }

    /** Цвет в доке горит, только пока фокус в самом доке. */
    private void paintDock() {
        for (int i = 0; i < dockViews.size(); i++) {
            View v = ((FrameLayout) dockViews.get(i)).getChildAt(1);
            boolean lit = zone == DOCK ? i == index : zone == CARDS && i == selected;
            v.animate().alpha(lit ? 1f : 0f).setDuration(FAST).start();
        }
    }

    private List<View> views(int z) {
        return z == DOCK ? dockViews : z == CARDS ? cardViews : z == APPS ? appViews : topViews;
    }

    private void scrollTo(float y) {
        if (y == scroll) return;
        scroll = y;
        content.animate().translationY(-px(y)).setDuration(NORMAL).setInterpolator(ease).start();
    }

    private void blur(View v, int z) {
        v.animate().scaleX(z == DOCK ? DOCK_REST : 1f).scaleY(z == DOCK ? DOCK_REST : 1f).setDuration(FAST).start();
        if (z == APPS) {
            int at = appViews.indexOf(v);
            if (at >= 0 && at < appLabels.size()) appLabels.get(at).animate().alpha(LABEL_REST).setDuration(FAST).start();
        }
        if (z == APPS) ((FrameLayout) v).getChildAt(1).animate().alpha(0f).setDuration(NORMAL).start();
        if (z == TOP) paintButton(v, false);
        if (z == CARDS) {
            if (v.getTag() instanceof FeedItem) {
                cards.dim(null, 0);
                cardsLayer.animate().translationX(0).setDuration(NORMAL).setInterpolator(ease).start();
            }
            else paintPill(v, false);
        }
    }

    private void moveTo(int z, int i) {
        View next = i >= 0 && i < views(z).size() ? views(z).get(i) : null;
        boolean withinFeed = zone == CARDS && z == CARDS && focused != null && focused.getTag() instanceof FeedItem
                && next != null && next.getTag() instanceof FeedItem;
        if (focused != null && focused != next) {
            if (withinFeed) focused.animate().scaleX(1f).scaleY(1f).setDuration(FAST).start();
            else blur(focused, zone);
        }
        zone = z;
        index = i;
        focused = next;
        if (!holding) Log.i("IosHome", "focus " + z + " " + i);
        if (z == CARDS && !dock.isEmpty() && next != null) cardMemory.put(dock.get(selected).pkg, i);
        if (z != CARDS) {
            cardsLayer.animate().translationX(0).setDuration(NORMAL).setInterpolator(ease).start();
        }
        paintDock();
        if (next == null) { spot.animate().alpha(0f).setDuration(FAST).start(); return; }
        if (z == DOCK && i == dock.size()) {
            scrollTo(APPS_SCROLL);
            washOff();
            next.animate().scaleX(1f).scaleY(1f).setDuration(FAST).setInterpolator(ease).start();
            spotAt(INK, DOCK_CX, dockY(i) + DOCK_LOGO / 2f, 280);
        } else if (z == DOCK) {
            scrollTo(0);
            if (i != selected) {
                selected = i;
                // По доку ленту строим, когда остановились (и при зажатой стрелке, и при быстрых нажатиях подряд):
                // пока листаешь — только логотип и цвет. Иначе при нехватке памяти каждое нажатие пересобирает ленту
                // и главный поток не успевает за кнопками.
                handler.removeCallbacks(showLater);
                showBrand(dock.get(i));
                washColor(dock.get(i).logo.accent);
                cardsLayer.animate().alpha(0.25f).setDuration(FAST).start();
                showPending = true;
                handler.postDelayed(showLater, holding ? 220 : 160);
            }
            else washColor(dock.get(i).logo.accent);
            next.animate().scaleX(1f).scaleY(1f).setDuration(FAST).setInterpolator(ease).start();
            spotAt(dock.get(i).logo.accent, DOCK_CX, dockY(i) + DOCK_LOGO / 2f, 330);
        } else if (z == APPS) {
            int accent = next.getTag() instanceof App ? ((App) next.getTag()).logo.accent : CLEAN;
            if (i < appLabels.size()) appLabels.get(i).animate().alpha(1f).setDuration(FAST).start();
            ((FrameLayout) next).getChildAt(1).animate().alpha(1f).setDuration(FAST).start();
            next.animate().scaleX(1.06f).scaleY(1.06f).setDuration(FAST).setInterpolator(ease).start();
            int row = i / appCols;
            scrollTo(APPS_SCROLL + Math.max(0, row - 1) * (appH + APP_LABEL + APP_GAP));
            washColor(accent);
            spotAt(accent, appX(i) + appW / 2f, appY(i) - scroll + appH / 2f, 420);
        } else if (z == CARDS) {
            scrollTo(0);
            if (next.getTag() instanceof FeedItem) {
                FeedItem item = (FeedItem) next.getTag();
                float w = item.poster ? POSTER_W : CARD_W;
                cards.lazy(i);
                // Зажатая стрелка: ровное линейное скольжение, шаг в шаг с повтором кнопки.
                cardsLayer.animate().translationX(-px(i * (w + CARD_GAP))).setDuration(holding ? 120 : NORMAL)
                        .setInterpolator(holding ? linear : ease).start();
                next.animate().scaleX(1.04f).scaleY(1.04f).setDuration(FAST).setInterpolator(ease).start();
                cards.dim(next, i);
                // Цвет фона по обложке — когда лента остановилась, а не на каждом шаге.
                handler.removeCallbacks(settle);
                handler.postDelayed(settle, holding ? 180 : 0);
            } else {
                paintPill(next, true);
                Object[] tag = (Object[]) next.getTag();
                int color = (int) tag[2];
                if (tag.length == 5) spotAt(color, (float) tag[4], CARDS_Y + 40 + VPN_H / 2f, 760);
                else spotAt(color, MAIN_X + 120, PILL_Y + 40, 420);
            }
        } else if (z == TOP) {
            scrollTo(0);
            paintButton(next, true);
            spot.animate().alpha(0f).setDuration(FAST).start();
        }
    }

    // ---------- Пульт ----------

    /** Фон замирает, пока жмёшь кнопки, и плывёт снова через 2 секунды покоя: листанию весь процессор. */
    private final Runnable driftOn = () -> {
        if (!hasWindowFocus()) return;
        for (ObjectAnimator a : drift) if (a.isPaused()) a.resume();
    };

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        if (dimming) { wake(); return true; }
        if (search.key(e)) return true;
        lastInput = SystemClock.uptimeMillis();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            for (ObjectAnimator a : drift) if (a.isRunning()) a.pause();
            handler.removeCallbacks(driftOn);
            handler.postDelayed(driftOn, 2000);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) Log.i("IosHome", "key " + key + " " + KeyEvent.keyCodeToString(key) + " scan " + e.getScanCode());
        boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { okDown = true; okLong = false; okDownAt = e.getEventTime(); }
                else if (okDown && !okLong && !overlayOpen() && !moving && e.getEventTime() - okDownAt > 550) {
                    okLong = true;
                    menu.appMenu();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                boolean click = okDown && !okLong;
                okDown = false;
                if (click) onOk();
            }
            return true;
        }
        boolean arrow = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            return arrow || key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU
                    || key == KeyEvent.KEYCODE_TV_INPUT || key == KeyEvent.KEYCODE_SETTINGS || super.dispatchKeyEvent(e);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        if (arrow) {
            // Зажатая стрелка в ленте: шаг не чаще раза в 110 мс, иначе анимации не успевают и лента дёргается.
            holding = e.getRepeatCount() > 0;
            if (holding && zone == CARDS && e.getEventTime() - lastStep < 110) return true;
            lastStep = e.getEventTime();
            if (overlayOpen()) menu.key(key);
            else if (moving) moveKey(key);
            else navigate(key);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_BACK:
                if (overlayOpen()) menu.close();
                else if (moving) stopMoving();
                else if (!dockViews.isEmpty()) moveTo(DOCK, selected);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (overlayOpen()) menu.close(); else menu.appMenu();
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
                if (overlayOpen()) menu.close(); else settings.show();
                return true;
            case KeyEvent.KEYCODE_TV_INPUT:
                inputs.show();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void onOk() {
        if (overlayOpen()) {
            if (menu.index >= 0 && menu.index < menu.acts.size()) {
                Runnable r = menu.acts.get(menu.index);
                if (!(r instanceof Keep)) menu.close();
                r.run();
            }
            return;
        }
        if (inputs.openPending()) return;
        if (moving) { stopMoving(); return; }
        if (focused == null) return;
        Object tag = focused.getTag();
        if (tag instanceof FeedItem && ((FeedItem) tag).action != null) ((FeedItem) tag).action.run();
        else if (tag instanceof FeedItem) {
            FeedItem item = (FeedItem) tag;
            String pkg = dock.get(selected).pkg;
            // Афиша Кинопоиска и Зоны открывает приложение, как значок: сам фильм по ссылке они не открывают.
            feed.remember(pkg, item);
            try { startActivity(item.open); }
            catch (ActivityNotFoundException | SecurityException e) { launch(dock.get(selected)); }
        } else if (tag instanceof App) launch((App) tag);
        else ((Action) ((Object[]) tag)[1]).run();
    }

    private int remembered() {
        Integer at = dock.isEmpty() ? null : cardMemory.get(dock.get(selected).pkg);
        return at == null ? 0 : Math.max(0, Math.min(at, cardViews.size() - 1));
    }

    /** Вверх и вниз из ленты: соседний сервис, на той карточке, где остановились. */
    private void nextFeed(int target) {
        View old = focused;
        focused = null;
        if (old != null) blur(old, CARDS);
        boolean down = target > selected;
        cardsLayer.animate().cancel();
        selected = target;
        showSelected();
        if (cardViews.isEmpty()) { cardsLayer.setTranslationX(0); moveTo(DOCK, target); return; }
        int at = remembered();
        Object tag = cardViews.get(0).getTag();
        float w = tag instanceof FeedItem && ((FeedItem) tag).poster ? POSTER_W : CARD_W;
        cardsLayer.setTranslationX(tag instanceof FeedItem ? -px(at * (w + CARD_GAP)) : 0);
        cardsLayer.setTranslationY(px(down ? 70 : -70));
        cardsLayer.setAlpha(0f);
        cardsLayer.animate().translationY(0).alpha(1f).setDuration(NORMAL).setInterpolator(ease).start();
        moveTo(CARDS, at);
    }

    private boolean holding;
    private boolean showPending;
    private final Runnable showLater = () -> { showPending = false; showSelected(); };
    private long lastStep;

    private void navigate(int key) {
        // Лента нового сервиса ещё не построена (листали док зажатой стрелкой) — строим сейчас.
        if (showPending && !holding) { handler.removeCallbacks(showLater); showPending = false; showSelected(); }
        IosNav.Screen s = new IosNav.Screen();
        s.zone = zone;
        s.index = index;
        s.selected = selected;
        s.services = dock.size();
        s.cards = cardViews.size();
        s.remembered = remembered();
        s.apps = appViews.size();
        s.perRow = appCols;
        s.top = topViews.size();
        int k = key == KeyEvent.KEYCODE_DPAD_LEFT ? IosNav.LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT ? IosNav.RIGHT
                : key == KeyEvent.KEYCODE_DPAD_UP ? IosNav.UP : IosNav.DOWN;
        IosNav.Move m = IosNav.next(s, k);
        if (m.kind == IosNav.Move.FEED) nextFeed(m.index);
        else if (m.kind == IosNav.Move.GO) moveTo(m.zone, m.index);
    }

    void launch(App a) {
        open(new Intent(Intent.ACTION_MAIN).setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
    void open(Intent i) {
        if (i == null) return;
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            note("Не удалось открыть");
        }
    }

    void startMoving() { moving = true; }

    private void stopMoving() {
        moving = false;
        saveLayout();
        render();
    }

    private void moveKey(int key) {
        List<App> list = zone == DOCK ? dock : zone == APPS ? others : null;
        if (list == null) return;
        boolean back = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP;
        int shift = zone == APPS ? dock.size() : 0, from = index - shift, target = from + (back ? -1 : 1);
        if (from < 0 || from >= list.size() || target < 0 || target >= list.size()) return;
        Collections.swap(list, from, target);
        index = target + shift;
        if (zone == DOCK) selected = index;
        render();
    }

    /** Пункт меню, после которого меню остаётся открытым: переключатели. Подпись обновляется на месте, без анимации. */
    static final class Keep implements Runnable {
        final Runnable r;
        Keep(Runnable r) { this.r = r; }
        @Override public void run() { r.run(); }
    }

    /** Долго без нажатий на главном экране: экран мягко притухает, потом заставка. Заодно чистка кэша раз в 15 дней. */
    private void checkIdle() {
        int idle = getSharedPreferences("ios_saver", 0).getInt("idle", 5);
        if (idle == 0 || overlayOpen() || !hasWindowFocus() || dimming) return;
        if (SystemClock.uptimeMillis() - lastInput < idle * 60_000L) return;
        dimming = true;
        tools.trimIfDue();
        root.animate().alpha(0.25f).setDuration(2500).withEndAction(() -> {
            if (!dimming) return;
            startActivity(new Intent(this, IosSaverActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }).start();
    }

    private void wake() {
        lastInput = SystemClock.uptimeMillis();
        if (!dimming) return;
        dimming = false;
        root.animate().cancel();
        root.animate().alpha(1f).setDuration(FAST).start();
    }
}
