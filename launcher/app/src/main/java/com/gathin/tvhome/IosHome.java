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
import android.net.Uri;
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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int INK = 0xFFF5F5F7, INK_2 = 0xFFC7C7CC, ICON_REST = 0xFF8E8E93, GROUND = 0xFF050506,
            PANEL = 0xFF17171A, PILL = 0xFF1C1C1F, ROW_ON = 0xFFF5F5F7, ROW_ON_TEXT = 0xFF0B0B0D, SCRIM = 0xB3000000;
    private static final float DOCK_CX = 95, DOCK_CELL = 116, DOCK_LOGO = 96, DOCK_REST = 0.875f, MAIN_X = 236,
            TITLE_Y = 170, PILL_Y = 300, CARDS_Y = 300, CARD_W = 880, CARD_H = 495, CARD_GAP = 36, EDGE_X = 57,
            HEADER_Y = 1180, APPS_Y = 1280, APP_LOGO = 110, APP_CELL = 200, APP_ROW = 190, APPS_SCROLL = 1090;
    private static final int APPS_PER_ROW = 8, MAX_DOCK = 7;
    private static final int DOCK = 0, CARDS = 1, APPS = 2, TOP = 3;
    private static final String[] DEFAULT_DOCK = {"com.yandex.tv.ytplayer", "com.vk.tv", "ru.rutube.app.tv", "ru.kinopoisk.yandex.tv", "su.happ.proxyutility"};
    private static final String[] SKIP = {"com.cvte.", "com.mediatek.", "com.android.", "com.gathin.tvhome",
            "com.tim.adbmenu", "com.yandex.tv.home", "com.yandex.tv.settings", "com.yandex.tv.setupwizard",
            "com.yandex.tv.services", "com.yandex.io.", "com.yandex.webview", "ru.yandex.androidkeyboard",
            "com.yandex.devices.", "org.chromium", "com.yandex.tv.live", "com.yandex.tv.input"};
    private static final Map<String, int[]> BRAND = new HashMap<>();
    private static final Map<String, Integer> WORD = new HashMap<>();
    private static final int ZONA_PINK = 0xFFFF4FA3;
    static {
        WORD.put("com.yandex.tv.ytplayer", R.drawable.word_youtube);
        WORD.put("com.vk.tv", R.drawable.word_vk);
        WORD.put("su.happ.proxyutility", R.drawable.word_happ);
        WORD.put("ru.zonaplayer.app", R.drawable.word_zona);
        WORD.put("org.interhive.sota.connect", R.drawable.word_sota);
        BRAND.put("org.interhive.sota.connect", new int[]{R.drawable.logo_sota, 0xFFF5D020});
        BRAND.put("ru.zonaplayer.app", new int[]{R.drawable.logo_zona, ZONA_PINK});
        WORD.put("ru.rutube.app.tv", R.drawable.word_rutube);
        WORD.put("ru.rutube.app", R.drawable.word_rutube);
        WORD.put("ru.kinopoisk.yandex.tv", R.drawable.word_kino);
        BRAND.put("com.yandex.tv.ytplayer", new int[]{R.drawable.logo_youtube, 0xFFFF2A2A});
        BRAND.put("com.vk.tv", new int[]{R.drawable.logo_vk, 0xFF2F8BFF});
        BRAND.put("ru.rutube.app.tv", new int[]{R.drawable.logo_rutube, 0xFF8B5CF6});
        BRAND.put("ru.rutube.app", new int[]{R.drawable.logo_rutube, 0xFF8B5CF6});
        BRAND.put("su.happ.proxyutility", new int[]{R.drawable.logo_happ, 0xFF7FD14F});
        BRAND.put("me.zhanghai.android.files", new int[]{R.drawable.logo_files, 0xFF2F8BFF});
        BRAND.put("ru.vk.store.tv", new int[]{R.drawable.logo_rustore, 0xFF8A5CFF});
        BRAND.put("ru.kinopoisk.yandex.tv", new int[]{R.drawable.logo_kino, 0xFFFF6A00});
        BRAND.put("com.yandex.browser.tv", new int[]{R.drawable.logo_browser, 0xFFFF2D3A});
        BRAND.put("cm.aptoidetv.pt", new int[]{R.drawable.logo_aptoide, 0xFFFF7A18});
        BRAND.put("com.moovi.tv.moovitv", new int[]{R.drawable.logo_moovi, 0xFFFF5A3C});
    }

    /** Один пункт ленты. Источники данных подключаются отдельно; без них лента пустая, а не выдуманная. */
    static final class FeedItem {
        String title, subtitle;
        Bitmap image;
        String[] urls;
        Runnable onImage;
        boolean fetching;
        boolean poster;
        Intent open;
        Runnable action, onFail;
        String duration;
    }

    private static final class App {
        String pkg, label;
        ComponentName component;
        boolean system;
        IosIcons.Logo logo;
    }

    private interface Action { void run(); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, App> apps = new HashMap<>(), cache = new HashMap<>();
    private final List<App> dock = new ArrayList<>(), others = new ArrayList<>();
    private final List<View> dockViews = new ArrayList<>(), appViews = new ArrayList<>(), cardViews = new ArrayList<>(),
            topViews = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private SharedPreferences prefs;
    private FrameLayout root, dockLayer, content, cardsLayer, appsLayer, overlay;
    private TextView clock, date, title, subtitle, action;
    private static String returnTo;
    /** Три длительности на весь экран, кривая одна: ease. */
    private static final int FAST = 160, NORMAL = 260, SLOW = 420;
    private static final int CARD_CLIP = 20, CLEAN = 0xFF30D5C8;
    private static final int MENU_W = 620, MENU_ROW = 78, POSTER_W = 330;
    private LinearLayout menuPanel;
    private String vpnShown = "", vpnStats = "";
    private final Map<String, Integer> cardMemory = new HashMap<>();
    private final List<ObjectAnimator> drift = new ArrayList<>();
    private ImageView glow;
    private ConnectivityManager.NetworkCallback vpnWatch;
    private boolean dirty = true, lastVpn, wasAway;
    private int ticks;
    private ImageView wordmark;
    private TextView caption, captionSub, appLabel;
    private final Map<View, TextView[]> cardLabels = new HashMap<>();
    /** Подписи карточек: в покое, у соседей выбранной. */
    private static final float LABEL_REST = 0.62f, LABEL_DIM = 0.4f;
    private IosFeed feed;
    private final Map<String, String> feedShown = new HashMap<>();
    private ImageView washA, washB, spot;
    private int washNow;
    private float s;
    private int screenW;
    private Typeface regular, medium, semibold;
    private int zone = DOCK, index = 0, selected = 0;
    private float scroll = 0;
    private boolean moving, washOnA, okDown, okLong;
    private long okDownAt;
    private View focused;
    private final List<Runnable> menuActions = new ArrayList<>();
    private final List<TextView> menuViews = new ArrayList<>();
    private int menuIndex;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateClock();
            ticks++;
            if (ticks % 12 == 0 && zone != CARDS && zone != APPS && !overlayOpen()) rotateFeeds();
            if (ticks % 4 == 0) loadAllFeeds();
            handler.postDelayed(this, 15000);
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getData() != null) cache.remove(i.getData().getSchemeSpecificPart());
            reload();
        }
    };

    private int px(float v) { return Math.round(v * s); }
    private boolean overlayOpen() { return overlay.getVisibility() == View.VISIBLE; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("ios_home_v4", MODE_PRIVATE);
        feed = new IosFeed(this);
        screenW = getResources().getDisplayMetrics().widthPixels;
        s = screenW / 1920f;
        regular = font(R.font.sf_regular);
        medium = font(R.font.sf_medium);
        semibold = font(R.font.sf_semibold);
        root = new FrameLayout(this);
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
        for (ImageView v : new ImageView[]{washA, washB}) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            swing(v, "translationX", v.getTranslationX(), 110, 23000);
            swing(v, "translationY", v.getTranslationY(), 70, 17000);
            swing(v, "scaleX", 1f, 0.08f, 29000);
            swing(v, "scaleY", 1f, 0.06f, 37000);
        }
        glow.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        swing(glow, "translationX", glow.getTranslationX(), 240, 31000);
        swing(glow, "translationY", glow.getTranslationY(), 130, 26000);
        View grain = new View(this);
        grain.setBackground(grain());
        grain.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(grain, new FrameLayout.LayoutParams(-1, -1));
        buildChrome();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
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
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && vpnWatch != null) cm.unregisterNetworkCallback(vpnWatch);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(ticker);
        for (ObjectAnimator a : drift) { if (a.isPaused()) a.resume(); else if (!a.isStarted()) a.start(); }
        if (wasAway) {
            wasAway = false;
            rotateFeeds();
        }
        if (dirty || returnTo != null) {
            dirty = false;
            reload();
        }
        refreshVpn();
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
        for (int k = 0; k < lines.size(); k++) close.add(this::closeMenu);
        showMenu("Подсказки", lines, close);
        menuIndex = lines.size() - 1;
        paintMenu();
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
        refreshVpn();
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
        for (long delay : new long[]{3000, 10000, 22000}) handler.postDelayed(() -> { if (!vpnOn()) vpnCommand(true); }, delay);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        for (ObjectAnimator a : drift) a.pause();
        wasAway = true;
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeMenu();
        moving = false;
        if (dirty || returnTo != null || dock.isEmpty()) {
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
        v.setImageBitmap(radial(new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x33FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.34f, 0.66f, 1f}));
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
        v.setImageBitmap(radial(new int[]{0xCCFFFFFF, 0x55FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.48f, 1f}));
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        v.setAlpha(0f);
        root.addView(v, new FrameLayout.LayoutParams(px(300), px(300)));
        return v;
    }

    private void addScrims() {
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{GROUND, 0xD9050506, 0x40050506, 0x00050506}));
        root.addView(left, new FrameLayout.LayoutParams(px(1540), -1));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{GROUND, 0x99050506, 0x00050506}));
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

    private void washColor(int color) {
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

    private TextView text(float size, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private FrameLayout.LayoutParams at(float x, float y, float w, float h) {
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
        title = text(88, semibold, INK);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLetterSpacing(-0.025f);
        content.addView(title, at(MAIN_X, TITLE_Y, 1300, 0));
        wordmark = new ImageView(this);
        wordmark.setScaleType(ImageView.ScaleType.FIT_START);
        wordmark.setAdjustViewBounds(false);
        wordmark.setVisibility(View.GONE);
        content.addView(wordmark, at(MAIN_X, TITLE_Y + 8, 1100, 76));
        subtitle = text(29, regular, INK_2);
        content.addView(subtitle, at(MAIN_X, TITLE_Y + 118, 1100, 0));
        caption = text(36, medium, INK);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(caption, at(MAIN_X, CARDS_Y + CARD_H + 30, 1180, 0));
        captionSub = text(26, regular, INK_2);
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
        action = text(30, semibold, ROW_ON_TEXT);
        action.setText("Открыть");
        action.setGravity(Gravity.CENTER);
        action.setVisibility(View.GONE);
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.HORIZONTAL);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView head = text(34, medium, INK);
        head.setText("Все приложения");
        section.addView(head);
        content.addView(section, at(MAIN_X, HEADER_Y, 0, 0));
        appsLayer = layer();
        content.addView(appsLayer, new FrameLayout.LayoutParams(-1, -1));

        dockLayer = layer();
        root.addView(dockLayer, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        clock = text(34, medium, INK);
        date = text(26, regular, INK_2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(20);
        status.addView(clock);
        status.addView(date, lp);
        root.addView(status, at(EDGE_X, 36, 0, 0));
        addButton(1680, R.drawable.top_input, "Источники", this::showInputs);
        addButton(1748, R.drawable.top_wifi, "Wi-Fi", () -> open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));
        addButton(1816, R.drawable.top_settings, "Настройки", this::showSettings);

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
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(36), px(36));
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
        date.setText(d.isEmpty() ? d : d.substring(0, 1).toUpperCase(Locale.getDefault()) + d.substring(1));
    }

    // ---------- Приложения ----------

    private static boolean skipped(String pkg) {
        for (String p : SKIP) if (pkg.startsWith(p)) return true;
        return false;
    }

    private List<String> stored(String key) {
        String v = prefs.getString(key, "");
        return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(v.split(",")));
    }

    private void store(String key, List<String> values) {
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

    private void reload() {
        loadApps();
        hidden.clear();
        hidden.addAll(stored("hidden"));
        List<String> order = new ArrayList<>();
        if (prefs.contains("dock")) order.addAll(stored("dock"));
        else {
            order.addAll(Arrays.asList(DEFAULT_DOCK));
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
        if (returnTo != null) {
            for (int i = 0; i < dock.size(); i++) if (returnTo.equals(dock.get(i).pkg)) selected = i;
            returnTo = null;
        }
        selected = dock.isEmpty() ? 0 : Math.min(selected, dock.size() - 1);
        render();
        root.post(() -> {
            for (App a : new ArrayList<>(dock)) if (IosFeed.supports(a.pkg)) loadFeed(a.pkg);
        });
    }

    private void saveLayout() {
        List<String> d = new ArrayList<>(), g = new ArrayList<>();
        for (App a : dock) d.add(a.pkg);
        for (App a : others) g.add(a.pkg);
        store("dock", d);
        store("order", g);
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
        showMenu("Очистка памяти", Collections.singletonList("Закрываю фоновые приложения"), Collections.singletonList(this::closeMenu));
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        Set<String> keep = new HashSet<>(Arrays.asList(getPackageName(), HAPP, SOTA, "com.android.systemui",
                "com.android.providers.tv", "com.android.bluetooth", "ru.yandex.androidkeyboard.tv", "com.mediatek.tvinput",
                "com.mediatek.tv.service", "com.cvte.tv.api.impl", "com.cvte.tv.globalui", "com.yandex.tv.services.platform"));
        int n = 0;
        String problem = null;
        for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
            if (keep.contains(ai.packageName) || ai.packageName.startsWith("com.android.") && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            try {
                am.killBackgroundProcesses(ai.packageName);
                n++;
            } catch (RuntimeException e) {
                problem = e.getClass().getSimpleName();
                Log.w("IosHome", "clean " + ai.packageName + ": " + e);
            }
        }
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
            for (int k = 0; k < lines.size(); k++) close.add(this::closeMenu);
            showMenu("Очистка памяти", lines, close);
            menuIndex = lines.size() - 1;
            paintMenu();
        }, 1800);
    }

    /** Последняя кнопка дока: наведение прокручивает к общему списку приложений. */
    private View gridButton() {
        FrameLayout box = new FrameLayout(this);
        int pad = px(14);
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

    private float appX(int i) { return MAIN_X + (i % APPS_PER_ROW) * APP_CELL; }
    private float appY(int i) { return APPS_Y + (i / APPS_PER_ROW) * APP_ROW; }

    private void render() {
        focused = null;
        dockLayer.removeAllViews();
        dockViews.clear();
        for (int i = 0; i < dock.size(); i++) {
            View v = logoView(dock.get(i));
            v.setScaleX(DOCK_REST);
            v.setScaleY(DOCK_REST);
            dockLayer.addView(v, at(DOCK_CX - DOCK_LOGO / 2f, dockY(i), DOCK_LOGO, DOCK_LOGO));
            dockViews.add(v);
        }
        View grid = gridButton();
        dockLayer.addView(grid, at(DOCK_CX - DOCK_LOGO / 2f, dockY(dock.size()), DOCK_LOGO, DOCK_LOGO));
        dockViews.add(grid);
        appsLayer.removeAllViews();
        appViews.clear();
        List<App> all = new ArrayList<>(dock);
        all.addAll(others);
        for (int i = 0; i < all.size(); i++) {
            View v = logoView(all.get(i));
            appsLayer.addView(v, at(appX(i), appY(i), APP_LOGO, APP_LOGO));
            appViews.add(v);
        }
        appLabel = text(26, medium, INK);
        appLabel.setGravity(Gravity.CENTER);
        appLabel.setSingleLine(true);
        appLabel.setEllipsize(TextUtils.TruncateAt.END);
        appLabel.setAlpha(0f);
        appsLayer.addView(appLabel, at(0, 0, APP_CELL, 0));
        View clean = iconTile(R.drawable.top_clean, CLEAN, this::cleanUp);
        int ci = appViews.size();
        appsLayer.addView(clean, at(appX(ci), appY(ci), APP_LOGO, APP_LOGO));
        appViews.add(clean);
        showSelected();
        if (views(zone).isEmpty()) { zone = !dockViews.isEmpty() ? DOCK : !appViews.isEmpty() ? APPS : TOP; index = 0; }
        moveTo(zone, Math.max(0, Math.min(index, views(zone).size() - 1)));
    }

    private static final String HAPP = "su.happ.proxyutility", SOTA = "org.interhive.sota.connect";
    private static final int VPN_H = 280;

    private boolean vpnOn() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private void vpnCommand(boolean connect) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("happ://" + (connect ? "connect" : "disconnect") + "_without_ui"))
                .setClassName(HAPP, HAPP + ".feature.deeplink.DeeplinkInterceptorActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        open(i);
        for (long delay : new long[]{700, 1800, 3500, 6000}) handler.postDelayed(this::refreshVpn, delay);
    }

    private void refreshVpn() {
        if (dock.isEmpty() || !(HAPP.equals(dock.get(selected).pkg) || SOTA.equals(dock.get(selected).pkg))) return;
        String state = vpnOn() + "|" + prefs.getString("vpn_place", "") + "|" + vpnStats + "|" + dock.get(selected).pkg;
        if (state.equals(vpnShown)) return;
        boolean keep = zone == CARDS;
        int at = index;
        showSelected();
        if (keep) moveTo(CARDS, Math.min(at, cardViews.size() - 1));
    }



    /** Экран VPN: три узкие карточки, как афиши. Тумблер, связь (страна и отклик), смена сервера. */
    private void showVpn(App a) {
        boolean on = vpnOn(), own = HAPP.equals(a.pkg);
        int accent = a.logo.accent;
        vpnShown = on + "|" + prefs.getString("vpn_place", "") + "|" + vpnStats + "|" + a.pkg;
        String iso = prefs.getString("vpn_iso", ""), where = prefs.getString("vpn_place", "");
        String[] parts = vpnStats.split("\\|");
        boolean measured = on && parts.length == 3;
        int grade = measured ? Integer.parseInt(parts[2]) : -1;

        FeedItem toggle = new FeedItem();
        toggle.poster = true;
        toggle.title = on ? "VPN включён" : "VPN выключен";
        toggle.subtitle = own ? (on ? "ОК, чтобы выключить" : "ОК, чтобы включить") : "Включается в приложении";
        toggle.image = switchArt(on, accent);
        toggle.action = () -> { if (own) vpnCommand(!on); else launch(a); };

        FeedItem link = new FeedItem();
        link.poster = true;
        link.title = !on ? "Нет подключения" : where.isEmpty() ? "Определяю страну" : where;
        link.subtitle = !on ? "Сначала включи VPN" : (measured ? parts[0] + ", " + parts[1].toLowerCase(new Locale("ru")) + ". " : "Измеряю. ") + "ОК, обновить";
        link.image = linkArt(on && !iso.isEmpty() ? flagOf(iso) : "\uD83C\uDF10", grade, measured ? parts[0] : on ? "…" : "—", accent);
        link.action = () -> {
            prefs.edit().remove("vpn_place").remove("vpn_iso").apply();
            feed.resetProbe();
            vpnStats = "";
            refreshVpn();
            if (vpnOn()) { checkExit(); checkSpeed(); }
        };

        FeedItem server = new FeedItem();
        server.poster = true;
        server.title = "Сменить сервер";
        server.subtitle = own ? "Откроется Happ" : "Откроется приложение";
        server.image = serverArt(accent);
        server.action = () -> launch(a);

        FeedItem[] all = {toggle, link, server};
        for (int i = 0; i < all.length; i++) addCard(all[i], i);
        if (on) { checkExit(); checkSpeed(); }
    }

    private Bitmap art() {
        Bitmap b = Bitmap.createBitmap(px(POSTER_W), px(CARD_H), Bitmap.Config.ARGB_8888);
        b.eraseColor(PILL);
        return b;
    }

    private Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private void glow(Canvas c, float cx, float cy, float r, int accent) {
        Paint g = paint(accent);
        g.setShader(new RadialGradient(cx, cy, r, (accent & 0x00FFFFFF) | 0x77000000, accent & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, g);
    }

    /** Вертикальный тумблер: включённый светится цветом секции, бегунок наверху. */
    private Bitmap switchArt(boolean on, int accent) {
        Bitmap b = art();
        Canvas c = new Canvas(b);
        float cx = b.getWidth() / 2f, cy = b.getHeight() / 2f, w = px(150), h = px(270);
        if (on) glow(c, cx, cy, px(260), accent);
        c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, w / 2, w / 2, paint(on ? accent : 0xFF3A3A3F));
        float r = w / 2 - px(12), ky = on ? cy - h / 2 + w / 2 : cy + h / 2 - w / 2;
        c.drawCircle(cx, ky + px(5), r, paint(0x55000000));
        c.drawCircle(cx, ky, r, paint(INK));
        return b;
    }

    /** Связь: флаг страны выхода сверху, под ним дуга отклика с числом. */
    private Bitmap linkArt(String flag, int grade, String value, int accent) {
        Bitmap b = art();
        Canvas c = new Canvas(b);
        float cx = b.getWidth() / 2f;
        Paint f = paint(INK);
        f.setTextSize(px(150));
        f.setTextAlign(Paint.Align.CENTER);
        c.drawText(flag, cx, px(200), f);
        float cy = px(400), r = px(110);
        Paint arc = paint(0xFF3A3A3F);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(px(22));
        arc.setStrokeCap(Paint.Cap.ROUND);
        android.graphics.RectF box = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(box, 180, 180, false, arc);
        if (grade >= 0) {
            arc.setColor(grade >= 2 ? accent : grade == 1 ? 0xFFFF9F0A : 0xFFFF453A);
            c.drawArc(box, 180, 180 * (grade + 1) / 4f, false, arc);
        }
        Paint t = paint(INK);
        t.setTypeface(semibold);
        t.setTextSize(px(value.length() > 6 ? 40 : 52));
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(value, cx, cy - px(8), t);
        return b;
    }

    /** Смена сервера: стопка из трёх серверов, верхний светится. */
    private Bitmap serverArt(int accent) {
        Bitmap b = art();
        Canvas c = new Canvas(b);
        float cx = b.getWidth() / 2f, w = px(200), h = px(56), gap = px(22), top = b.getHeight() / 2f - (3 * h + 2 * gap) / 2;
        glow(c, cx, top + h / 2, px(180), accent);
        for (int i = 0; i < 3; i++) {
            float y = top + i * (h + gap);
            c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, px(16), px(16), paint(i == 0 ? accent : 0xFF3A3A3F));
            c.drawCircle(cx - w / 2 + px(30), y + h / 2, px(9), paint(i == 0 ? INK : 0xFF6E6E73));
            Paint line = paint(i == 0 ? INK : 0xFF6E6E73);
            c.drawRoundRect(cx - w / 2 + px(56), y + h / 2 - px(5), cx + w / 2 - px(28), y + h / 2 + px(5), px(5), px(5), line);
        }
        return b;
    }

    /** Отклик через VPN: пять запросов подряд, среднее, разброс и потери. */
    private void checkSpeed() {
        feed.probe((avg, spread, lost) -> {
            int grade = lost > 1 || avg < 0 ? 0 : spread > 120 || avg > 600 ? 1 : spread > 50 || avg > 250 ? 2 : 3;
            String word = grade == 0 ? "Связь рвётся" : grade == 1 ? "Нестабильно" : grade == 2 ? "Стабильно" : "Отлично";
            vpnStats = (avg < 0 ? "нет ответа" : avg + " мс") + "|" + word + "|" + grade;
            refreshVpn();
        });
    }

    private static String flagOf(String iso) {
        return new String(Character.toChars(0x1F1E6 + iso.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + iso.charAt(1) - 'A'));
    }

    /** Страну показывает адрес, с которого виден телевизор через VPN. */
    private void checkExit() {
        feed.exit((iso, city) -> {
            String country = new Locale("", iso).getDisplayCountry(new Locale("ru"));
            String place = city.isEmpty() ? country : country + ", " + city;
            if (place.equals(prefs.getString("vpn_place", ""))) return;
            prefs.edit().putString("vpn_iso", iso).putString("vpn_place", place).apply();
            if (!dock.isEmpty() && (HAPP.equals(dock.get(selected).pkg) || SOTA.equals(dock.get(selected).pkg))) refreshVpn();
        });
    }

    private void showFeed(App a) {
        List<FeedItem> items = feed.cached(a.pkg);
        subtitle.setText("");
        for (int i = 0; i < items.size(); i++) addCard(items.get(i), i);
        if (items.isEmpty()) addPill("Открыть", a.logo.accent, () -> launch(a));
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

    private void addCard(FeedItem item, int i) {
        int accent = dock.isEmpty() ? INK_2 : dock.get(selected).logo.accent;
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PILL);
        bg.setCornerRadius(px(26));
        card.setBackground(bg);
        card.setClipToOutline(true);
        ImageView pic = new ImageView(this);
        pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(pic, new FrameLayout.LayoutParams(-1, -1));
        if (item.image != null) {
            pic.setImageBitmap(item.image);
            item.onImage = () -> pic.setImageBitmap(item.image);
        } else {
            // Пока обложка грузится, карточка мягко дышит.
            // Дышат только первые карточки: дальние за краем экрана, анимация там лишь грузит процессор.
            final ObjectAnimator pulse = i < 6 ? ObjectAnimator.ofFloat(card, "alpha", 1f, 0.55f) : null;
            if (pulse != null) {
                pulse.setDuration(SLOW * 2);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.start();
            }
            item.onImage = () -> {
                if (pulse != null) { pulse.cancel(); card.setAlpha(1f); }
                pic.setAlpha(0f);
                pic.setImageBitmap(item.image);
                pic.animate().alpha(1f).setDuration(NORMAL).setInterpolator(ease).start();
            };
            item.onFail = () -> {
                if (pulse != null) pulse.cancel();
                card.setAlpha(1f);
                bg.setColor((accent & 0x00FFFFFF) | 0x55000000);
                TextView t = text(item.poster ? 34 : 44, semibold, INK);
                t.setText(item.title);
                t.setGravity(Gravity.CENTER);
                t.setMaxLines(4);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(px(36), px(36), px(36), px(36));
                card.addView(t, new FrameLayout.LayoutParams(-1, -1));
            };
        }
        if (item.duration != null && !item.duration.isEmpty()) {
            TextView d = text(24, semibold, INK);
            d.setText(item.duration);
            d.setPadding(px(12), px(5), px(12), px(5));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xCC000000);
            g.setCornerRadius(px(10));
            d.setBackground(g);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            lp.rightMargin = lp.bottomMargin = px(16);
            card.addView(d, lp);
        }
        card.setTag(item);
        float w = item.poster ? POSTER_W : CARD_W, x = MAIN_X + i * (w + CARD_GAP);
        cardsLayer.addView(card, at(x, CARDS_Y, w, CARD_H));
        cardViews.add(card);
        // Подпись под каждой карточкой: в покое приглушена, у выбранной яркая и с описанием.
        TextView name = text(item.poster ? 28 : 34, medium, INK);
        name.setText(item.title == null ? "" : item.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAlpha(LABEL_REST);
        cardsLayer.addView(name, at(x + 4, CARDS_Y + CARD_H + 26, w - 8, 0));
        TextView sub = text(item.poster ? 22 : 26, regular, INK_2);
        sub.setText(item.subtitle == null ? "" : item.subtitle);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setAlpha(0f);
        cardsLayer.addView(sub, at(x + 4, CARDS_Y + CARD_H + (item.poster ? 66 : 74), w - 8, 0));
        cardLabels.put(card, new TextView[]{name, sub});
    }

    /** В длинной ленте обложки живут только рядом с выбранной карточкой: так 40 карточек не съедают память. */
    private void lazyCards(int at) {
        List<FeedItem> list = new ArrayList<>();
        for (View c : cardViews) if (c.getTag() instanceof FeedItem) list.add((FeedItem) c.getTag());
        for (int k = 0; k < cardViews.size(); k++) {
            View c = cardViews.get(k);
            if (!(c.getTag() instanceof FeedItem)) continue;
            FeedItem it = (FeedItem) c.getTag();
            if (it.urls == null || it.urls.length == 0 || Math.abs(k - at) <= 9) continue;
            ImageView pic = (ImageView) ((FrameLayout) c).getChildAt(0);
            if (pic.getDrawable() != null) { pic.setImageDrawable(null); it.image = null; }
        }
        feed.picturesAround(list, at);
    }

    /** Пока лента в фокусе, остальные карточки притушены, а уехавшие влево прячутся за доком. */
    private void dimCards(View on, int position) {
        for (int k = 0; k < cardViews.size(); k++) {
            View c = cardViews.get(k);
            boolean lit = c == on;
            c.animate().alpha(on == null || lit ? 1f : 0.5f).setDuration(FAST).start();
            TextView[] l = cardLabels.get(c);
            if (l != null) {
                l[0].animate().alpha(lit ? 1f : on == null ? LABEL_REST : LABEL_DIM).setDuration(FAST).start();
                l[1].animate().alpha(lit ? 1f : 0f).setDuration(FAST).start();
            }
        }
    }

    private void startYouTubeSignIn() {
        final TextView[] hint = new TextView[1];
        overlay.removeAllViews();
        float w = 820, x = (1920 - w) / 2f, y = 310;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(px(50), px(46), px(50), px(46));
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL);
        g.setCornerRadius(px(34));
        panel.setBackground(g);
        TextView code = text(84, semibold, INK);
        code.setText("Подождите");
        code.setSingleLine(true);
        TextView tip = text(30, regular, INK_2);
        tip.setText("Получаю код от Google");
        tip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = px(24);
        panel.addView(code);
        panel.addView(tip, lp);
        overlay.addView(panel, at(x, y, w, 0));
        overlay.setVisibility(View.VISIBLE);
        menuActions.clear();
        menuViews.clear();
        hint[0] = tip;
        feed.startSignIn(new IosFeed.CodeSink() {
            @Override public void code(String c, String url) {
                code.setText(c);
                hint[0].setText("На телефоне открой " + url.replace("https://", "") + ", выбери аккаунт и введи этот код");
            }
            @Override public void signedIn() {
                closeMenu();
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
        TextView pill = text(30, semibold, INK);
        pill.setText(text);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(px(46), 0, px(46), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(PILL);
        g.setCornerRadius(px(40));
        pill.setBackground(g);
        pill.setTag(new Object[]{text, action, fill});
        cardsLayer.addView(pill, at(MAIN_X, PILL_Y, 0, 80));
        cardViews.add(pill);
    }

    /** Заголовок секции: полный логотип в её цвете, а если логотипа нет, название тем же цветом. */
    private void showBrand(App a) {
        title.setSingleLine(true);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(88));
        title.setTextColor(a == null ? INK : a.logo.accent);
        Integer mark = a == null ? null : WORD.get(a.pkg);
        if (mark != null) {
            wordmark.setImageResource(mark);
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
            ((GradientDrawable) v.getBackground()).setColor(on ? 0xFF2C2C31 : PILL);
            v.animate().scaleX(on ? 1.03f : 1f).scaleY(on ? 1.03f : 1f).setDuration(FAST).start();
            return;
        }
        GradientDrawable g = (GradientDrawable) v.getBackground();
        g.setColor(on ? (int) ((Object[]) v.getTag())[2] : PILL);
        ((TextView) v).setTextColor(on ? ROW_ON_TEXT : INK);
        v.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(FAST).start();
    }

    /** Заголовок и цвет экрана берутся из выбранного в доке сервиса. */
    private void showSelected() {
        cardsLayer.removeAllViews();
        cardViews.clear();
        cardLabels.clear();
        cardsLayer.setTranslationX(0);
        cardsLayer.animate().cancel();
        cardsLayer.setTranslationY(0);
        cardsLayer.setAlpha(1f);
        if (dock.isEmpty()) { showBrand(null); subtitle.setText(""); return; }
        App a = dock.get(selected);
        Set<String> near = new HashSet<>();
        for (int k = Math.max(0, selected - 1); k <= Math.min(dock.size() - 1, selected + 1); k++) near.add(dock.get(k).pkg);
        feed.keepImages(near);
        showBrand(a);
        if (HAPP.equals(a.pkg) || SOTA.equals(a.pkg)) {
            subtitle.setText("");
            showVpn(a);
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
        if (z == APPS && appLabel != null) appLabel.animate().alpha(0f).setDuration(FAST).start();
        if (z == APPS) ((FrameLayout) v).getChildAt(1).animate().alpha(0f).setDuration(NORMAL).start();
        if (z == TOP) paintButton(v, false);
        if (z == CARDS) {
            if (v.getTag() instanceof FeedItem) {
                dimCards(null, 0);
                cardsLayer.animate().translationX(0).setDuration(NORMAL).setInterpolator(ease).start();
            }
            else paintPill(v, false);
        }
    }

    private void moveTo(int z, int i) {
        View next = i >= 0 && i < views(z).size() ? views(z).get(i) : null;
        if (focused != null && focused != next) blur(focused, zone);
        zone = z;
        index = i;
        focused = next;
        Log.i("IosHome", "focus " + z + " " + i);
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
            if (i != selected) { selected = i; showSelected(); }
            else washColor(dock.get(i).logo.accent);
            next.animate().scaleX(1f).scaleY(1f).setDuration(FAST).setInterpolator(ease).start();
            spotAt(dock.get(i).logo.accent, DOCK_CX, dockY(i) + DOCK_LOGO / 2f, 330);
        } else if (z == APPS) {
            int accent = next.getTag() instanceof App ? ((App) next.getTag()).logo.accent : CLEAN;
            appLabel.setText(next.getTag() instanceof App ? ((App) next.getTag()).label : "Очистить память");
            appLabel.setTranslationX(px(appX(i) + APP_LOGO / 2f - APP_CELL / 2f));
            appLabel.setTranslationY(px(appY(i) + APP_LOGO + 26));
            appLabel.animate().alpha(1f).setDuration(FAST).start();
            ((FrameLayout) next).getChildAt(1).animate().alpha(1f).setDuration(FAST).start();
            next.animate().scaleX(1.16f).scaleY(1.16f).setDuration(FAST).setInterpolator(ease).start();
            int row = i / APPS_PER_ROW;
            scrollTo(APPS_SCROLL + Math.max(0, row - 2) * APP_ROW);
            washColor(accent);
            spotAt(accent, appX(i) + APP_LOGO / 2f, appY(i) - scroll + APP_LOGO / 2f, 300);
        } else if (z == CARDS) {
            scrollTo(0);
            if (next.getTag() instanceof FeedItem) {
                FeedItem item = (FeedItem) next.getTag();
                float w = item.poster ? POSTER_W : CARD_W;
                lazyCards(i);
                cardsLayer.animate().translationX(-px(i * (w + CARD_GAP))).setDuration(NORMAL).setInterpolator(ease).start();
                next.animate().scaleX(1.03f).scaleY(1.03f).setDuration(FAST).setInterpolator(ease).start();
                dimCards(next, i);
                spotAt(dock.get(selected).logo.accent, MAIN_X + w / 2f, CARDS_Y + CARD_H / 2f, item.poster ? 900 : 1500);
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

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) Log.i("IosHome", "key " + key + " " + KeyEvent.keyCodeToString(key) + " scan " + e.getScanCode());
        boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { okDown = true; okLong = false; okDownAt = e.getEventTime(); }
                else if (okDown && !okLong && !overlayOpen() && !moving && e.getEventTime() - okDownAt > 550) {
                    okLong = true;
                    showAppMenu();
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
            if (overlayOpen()) menuKey(key);
            else if (moving) moveKey(key);
            else navigate(key);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_BACK:
                if (overlayOpen()) closeMenu();
                else if (moving) stopMoving();
                else if (!dockViews.isEmpty()) moveTo(DOCK, selected);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (overlayOpen()) closeMenu(); else showAppMenu();
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
                if (overlayOpen()) closeMenu(); else showSettings();
                return true;
            case KeyEvent.KEYCODE_TV_INPUT:
                showInputs();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void onOk() {
        if (overlayOpen()) {
            if (menuIndex < menuActions.size()) {
                Runnable r = menuActions.get(menuIndex);
                closeMenu();
                r.run();
            }
            return;
        }
        if (moving) { stopMoving(); return; }
        if (focused == null) return;
        Object tag = focused.getTag();
        if (tag instanceof FeedItem && ((FeedItem) tag).action != null) ((FeedItem) tag).action.run();
        else if (tag instanceof FeedItem) {
            try { startActivity(((FeedItem) tag).open); }
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

    private void navigate(int key) {
        IosNav.Screen s = new IosNav.Screen();
        s.zone = zone;
        s.index = index;
        s.selected = selected;
        s.services = dock.size();
        s.cards = cardViews.size();
        s.remembered = remembered();
        s.apps = appViews.size();
        s.perRow = APPS_PER_ROW;
        s.top = topViews.size();
        int k = key == KeyEvent.KEYCODE_DPAD_LEFT ? IosNav.LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT ? IosNav.RIGHT
                : key == KeyEvent.KEYCODE_DPAD_UP ? IosNav.UP : IosNav.DOWN;
        IosNav.Move m = IosNav.next(s, k);
        if (m.kind == IosNav.Move.FEED) nextFeed(m.index);
        else if (m.kind == IosNav.Move.GO) moveTo(m.zone, m.index);
    }

    private void launch(App a) {
        open(new Intent(Intent.ACTION_MAIN).setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
    private void open(Intent i) {
        if (i == null) return;
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Меню ----------

    /** Все меню выезжают одной панелью справа, как настройки. */
    private void showMenu(String title, List<String> labels, List<Runnable> actions) {
        overlay.removeAllViews();
        menuActions.clear();
        menuActions.addAll(actions);
        menuViews.clear();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(24), px(64), px(24), px(40));
        panel.setBackgroundColor(PANEL);
        TextView t = text(44, semibold, INK);
        t.setText(title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(px(24), 0, px(24), px(32));
        panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
        // Пункты лежат в отдельном окне: панель стоит на месте, листается только список.
        FrameLayout window = new FrameLayout(this);
        window.setClipChildren(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        window.addView(list, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(window, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (String l : labels) {
            TextView r = text(30, regular, INK);
            r.setText(l);
            r.setSingleLine(true);
            r.setEllipsize(TextUtils.TruncateAt.END);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(24), 0, px(24), 0);
            list.addView(r, new LinearLayout.LayoutParams(-1, px(MENU_ROW)));
            menuViews.add(r);
        }
        overlay.addView(panel, at(1920 - MENU_W, 0, MENU_W, 1080));
        menuPanel = list;
        overlay.setVisibility(View.VISIBLE);
        panel.setTranslationX(px(MENU_W));
        panel.animate().translationX(0).setDuration(NORMAL).setInterpolator(ease).start();
        menuIndex = 0;
        paintMenu();
    }

    private void paintMenu() {
        if (menuPanel != null) {
            View window = (View) menuPanel.getParent();
            float room = window.getHeight() > 0 ? window.getHeight() : px(1080 - 64 - 40 - 90);
            float bottom = px((menuIndex + 1) * MENU_ROW + MENU_ROW / 2);
            menuPanel.animate().translationY(-Math.max(0, bottom - room)).setDuration(FAST).setInterpolator(ease).start();
        }
        for (int i = 0; i < menuViews.size(); i++) {
            TextView r = menuViews.get(i);
            boolean on = i == menuIndex;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(20));
            g.setColor(on ? ROW_ON : 0x00000000);
            r.setBackground(g);
            r.setTextColor(on ? ROW_ON_TEXT : INK);
        }
    }

    private void menuKey(int key) {
        if (key == KeyEvent.KEYCODE_DPAD_UP && menuIndex > 0) menuIndex--;
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN && menuIndex < menuViews.size() - 1) menuIndex++;
        paintMenu();
    }

    private void closeMenu() {
        feed.stopPolling();
        overlay.setVisibility(View.GONE);
        overlay.removeAllViews();
    }

    private void showAppMenu() {
        if (focused == null || !(focused.getTag() instanceof App)) return;
        final App a = (App) focused.getTag();
        final boolean inDock = dock.contains(a);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Открыть");
        actions.add(() -> launch(a));
        if (IosFeed.YOUTUBE.equals(a.pkg)) {
            labels.add(feed.signedIn() ? "Выйти из аккаунта YouTube" : "Войти в YouTube: лента подписок");
            actions.add(() -> { if (feed.signedIn()) { feed.signOut(); reload(); } else startYouTubeSignIn(); });
        }
        labels.add("Переместить");
        actions.add(this::startMoving);
        labels.add(inDock ? "Убрать из дока" : "Добавить в док");
        actions.add(() -> {
            if (inDock) { dock.remove(a); others.add(0, a); }
            else if (dock.size() < MAX_DOCK) { others.remove(a); dock.add(a); }
            else Toast.makeText(this, "В доке не больше " + MAX_DOCK + " приложений", Toast.LENGTH_SHORT).show();
            saveLayout();
            reload();
        });
        labels.add("Скрыть с экрана");
        actions.add(() -> {
            dock.remove(a);
            others.remove(a);
            List<String> h = stored("hidden");
            h.add(a.pkg);
            store("hidden", h);
            saveLayout();
            reload();
        });
        final Intent info = SystemSettings.appInfo(this, a.pkg);
        if (info != null) {
            labels.add("О приложении");
            actions.add(() -> open(info));
        }
        if (!a.system) {
            labels.add("Удалить");
            actions.add(() -> open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        showMenu(a.label, labels, actions);
    }

    private void startMoving() { moving = true; }

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

    private void showSettings() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String[][] rows = {{"Wi-Fi и сеть", Settings.ACTION_WIFI_SETTINGS}, {"Bluetooth и пульты", Settings.ACTION_BLUETOOTH_SETTINGS},
                {"Звук", Settings.ACTION_SOUND_SETTINGS}, {"Экран", Settings.ACTION_DISPLAY_SETTINGS}, {"Дата и время", Settings.ACTION_DATE_SETTINGS},
                {"Приложения", Settings.ACTION_APPLICATION_SETTINGS}, {"Все настройки", Settings.ACTION_SETTINGS}};
        for (String[] row : rows) {
            labels.add(row[0]);
            actions.add(() -> open(SystemSettings.intent(this, row[1])));
        }
        // Аккаунт Яндекса всего телевизора: за ним следуют Кинопоиск и другие приложения Яндекса.
        labels.add("Аккаунт Яндекса");
        actions.add(() -> open(new Intent("com.yandex.tv.passport.MANAGE_ACCOUNTS").setPackage("com.yandex.tv.services")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        for (final String pkg : stored("hidden")) {
            String name = pkg;
            try {
                PackageManager pm = getPackageManager();
                name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException ignored) { }
            labels.add("Вернуть " + name);
            actions.add(() -> {
                List<String> h = stored("hidden");
                h.remove(pkg);
                store("hidden", h);
                reload();
            });
        }
        showMenu("Настройки", labels, actions);
    }

    private void showInputs() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) ->
                    String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
            for (final TvInputInfo info : inputs) {
                String label = String.valueOf(info.loadLabel(this));
                if (!info.isPassthroughInput() || info.isHidden(this) || label.startsWith("Playback")) continue;
                labels.add(label);
                actions.add(() -> open(new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(info.getId()).buildUpon().scheme("live-tv").build())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            }
        }
        final Intent live = getPackageManager().getLeanbackLaunchIntentForPackage("com.yandex.tv.live");
        if (live != null) {
            labels.add("Телеканалы");
            actions.add(() -> open(live));
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "Источники не найдены", Toast.LENGTH_SHORT).show();
            return;
        }
        showMenu("Источники", labels, actions);
    }
}
