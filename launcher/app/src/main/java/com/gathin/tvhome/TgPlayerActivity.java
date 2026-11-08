package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Плеер видео из Telegram: системный плеер телевизора (аппаратное декодирование) + TgSource, который качает
 * ровно то место, что смотрим. ОК — пауза, ←/→ — 10 секунд назад / 30 вперёд (зажать — быстрее),
 * ↑/↓ — показать полоску. Место просмотра запоминается: открыл снова — продолжит.
 */
public final class TgPlayerActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "TgPlayer";
    private MediaPlayer player;
    private TgSource source;
    private SurfaceView surface;
    private FrameLayout root, bar;
    private View track, fill, loaded;
    private TextView title, clock, status;
    private float scale;
    private boolean prepared, surfaceReady;
    private int seekTarget = -1, duration;
    private String unique;
    private long chat, message;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = () -> bar.animate().alpha(0f).setDuration(300).start();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            paint();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        prefs = getSharedPreferences("tg", MODE_PRIVATE);
        Typeface regular = getResources().getFont(R.font.sf_regular), semibold = getResources().getFont(R.font.sf_semibold);
        root = new FrameLayout(this);
        root.setBackgroundColor(IosPalette.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        status = text(IosPalette.BODY, regular, IosPalette.SECONDARY);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        // Полоска внизу, как в Apple TV: затемнение, название, дорожка, время.
        bar = new FrameLayout(this);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{IosPalette.SCRIM_STRONG, 0});
        bar.setBackground(shade);
        root.addView(bar, new FrameLayout.LayoutParams(-1, px(300), Gravity.BOTTOM));
        title = text(IosPalette.HEADLINE, semibold, IosPalette.INK);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, at(IosPalette.EDGE, 130, 1920 - 2 * IosPalette.EDGE, 0));
        track = rounded(IosPalette.GRAY4);
        bar.addView(track, at(IosPalette.EDGE, 196, 1920 - 2 * IosPalette.EDGE, 8));
        loaded = rounded(IosPalette.GRAY2);
        bar.addView(loaded, at(IosPalette.EDGE, 196, 0, 8));
        fill = rounded(IosPalette.INK);
        bar.addView(fill, at(IosPalette.EDGE, 196, 0, 8));
        clock = text(IosPalette.FOOTNOTE, regular, IosPalette.SECONDARY);
        bar.addView(clock, at(IosPalette.EDGE, 220, 1920 - 2 * IosPalette.EDGE, 0));
        setContentView(root);

        int fileId = getIntent().getIntExtra("file", 0);
        long size = getIntent().getLongExtra("size", 0);
        duration = getIntent().getIntExtra("duration", 0);
        unique = getIntent().getStringExtra("unique");
        title.setText(getIntent().getStringExtra("title"));
        chat = getIntent().getLongExtra("chat", 0);
        message = getIntent().getLongExtra("message", 0);
        if (chat != 0) TgActivity.remember(prefs, chat, message);
        status.setText("Загружаю…");
        if (!TgCore.vpn()) { status.setText("Включи VPN — Telegram работает только через него"); return; }
        source = new TgSource(TgCore.get(this), fileId, size);
        player = new MediaPlayer();
        try {
            player.setDataSource(source);
        } catch (Exception e) {
            status.setText("Видео не открылось");
            Log.w(TAG, "source", e);
            return;
        }
        player.setOnPreparedListener(mp -> {
            prepared = true;
            if (mp.getDuration() > 0) duration = mp.getDuration() / 1000;
            int at = unique == null ? 0 : prefs.getInt("pos_" + unique, 0);
            if (at > 30 && at < duration - 60) mp.seekTo(at * 1000);
            fit(mp.getVideoWidth(), mp.getVideoHeight());
            if (surfaceReady) begin();
        });
        player.setOnVideoSizeChangedListener((mp, w, h) -> fit(w, h));
        player.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) status.setText("Загружаю…");
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) status.setText("");
            return false;
        });
        player.setOnSeekCompleteListener(mp -> status.setText(""));
        player.setOnErrorListener((mp, what, extra) -> {
            status.setText("Не получилось воспроизвести (" + what + "/" + extra + ")");
            return true;
        });
        player.setOnCompletionListener(mp -> {
            if (unique != null) prefs.edit().remove("pos_" + unique).apply();
            if (chat != 0) TgActivity.forget(prefs, chat, message);
            finish();
        });
        player.prepareAsync();
        show();
    }

    private void begin() {
        player.setDisplay(surface.getHolder());
        player.start();
        handler.post(tick);
    }

    /** Видео по размеру экрана с сохранением пропорций. */
    private void fit(int w, int h) {
        if (w <= 0 || h <= 0) return;
        int sw = root.getWidth() > 0 ? root.getWidth() : px(1920), sh = root.getHeight() > 0 ? root.getHeight() : px(1080);
        float k = Math.min(sw / (float) w, sh / (float) h);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * k), Math.round(h * k), Gravity.CENTER);
        surface.setLayoutParams(lp);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (prepared && !player.isPlaying()) begin();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    // ---------- Пульт ----------

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (player == null || !prepared) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player.isPlaying()) player.pause(); else player.start();
                show();
                paint();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(-(e.getRepeatCount() > 6 ? 60 : 10));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(e.getRepeatCount() > 6 ? 120 : 30);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                show();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    /** Перемотка копится, пока жмут: плееру отдаём итоговое место через полсекунды. */
    private final Runnable doSeek = () -> {
        if (player != null && seekTarget >= 0) { status.setText("Загружаю…"); player.seekTo(seekTarget * 1000); }
        seekTarget = -1;
    };

    private void seek(int by) {
        int now = seekTarget >= 0 ? seekTarget : player.getCurrentPosition() / 1000;
        seekTarget = Math.max(0, Math.min(Math.max(0, duration - 5), now + by));
        show();
        paint();
        handler.removeCallbacks(doSeek);
        handler.postDelayed(doSeek, 500);
    }

    private void show() {
        bar.animate().cancel();
        bar.setAlpha(1f);
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, 4000);
    }

    private void paint() {
        if (player == null || !prepared) return;
        int pos = seekTarget >= 0 ? seekTarget : player.getCurrentPosition() / 1000;
        float w = 1920 - 2 * IosPalette.EDGE;
        float part = duration > 0 ? Math.min(1f, pos / (float) duration) : 0;
        setWidth(fill, w * part);
        long size = getIntent().getLongExtra("size", 0);
        float ahead = size > 0 && source != null ? Math.min(1f - part, source.buffered / (float) size) : 0;
        setWidth(loaded, w * (part + ahead));
        clock.setText(TgActivity.time(pos) + " / " + TgActivity.time(duration) + (player.isPlaying() ? "" : "   ·   пауза"));
        if (unique != null && pos > 0 && pos % 10 == 0) prefs.edit().putInt("pos_" + unique, pos).apply();
    }

    private void setWidth(View v, float w) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.width = Math.max(0, px(w));
        v.setLayoutParams(lp);
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null && prepared && unique != null) prefs.edit().putInt("pos_" + unique, player.getCurrentPosition() / 1000).apply();
        if (player != null && player.isPlaying()) player.pause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (source != null) {
            source.close();
            source.forget();
        }
        super.onDestroy();
    }

    // ---------- Вёрстка ----------

    private int px(float v) { return Math.round(v * scale); }

    private View rounded(int color) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(4));
        v.setBackground(g);
        return v;
    }

    private TextView text(float size, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w <= 0 && h > 0 ? 0 : w <= 0 ? -2 : px(w), h <= 0 ? -2 : px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }
}
