package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Экран Telegram: вход по QR-коду и видео из поста. Ссылку на пост присылает пульт (Телевизор → Открыть ссылку);
 * в посте до десяти видео — карточками, стрелки выбирают, ОК включает. Всё в стиле лаунчера: палитра и сетка IosPalette.
 */
public final class TgActivity extends Activity {
    private static final float EDGE = IosPalette.EDGE, COLS = 4, GAP = IosPalette.S4;
    private static final float CARD_W = (1920 - 2 * EDGE - (COLS - 1) * GAP) / COLS, THUMB_H = CARD_W * 9 / 16, CARD_H = THUMB_H + 120;

    /** Одно видео поста. */
    static final class Item {
        int fileId, duration, thumbId;
        long size;
        String title, unique;
        byte[] mini;
    }

    private TgCore tg;
    private SharedPreferences prefs;
    private FrameLayout root, grid;
    private TextView title, sub, center;
    private ImageView qr;
    private final List<Item> items = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private int index;
    private String link = "", shownState = "";
    private float scale;
    private Typeface regular, semibold;
    private final Runnable onState = this::render;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        regular = getResources().getFont(R.font.sf_regular);
        semibold = getResources().getFont(R.font.sf_semibold);
        prefs = getSharedPreferences("tg", MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(IosPalette.BLACK);
        setContentView(root);

        title = text(IosPalette.TITLE, semibold, IosPalette.INK);
        title.setText("Telegram");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(title, at(EDGE, IosPalette.S6, 1920 - 2 * EDGE, 0));
        sub = text(IosPalette.CALLOUT, regular, IosPalette.SECONDARY);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(sub, at(EDGE, IosPalette.S6 + 60, 1920 - 2 * EDGE, 0));

        grid = new FrameLayout(this);
        root.addView(grid, at(0, 200, 1920, 880));

        qr = new ImageView(this);
        qr.setVisibility(View.GONE);
        GradientDrawable white = new GradientDrawable();
        white.setColor(IosPalette.INK);
        white.setCornerRadius(px(IosPalette.R_CARD));
        qr.setBackground(white);
        int pad = px(IosPalette.S4);
        qr.setPadding(pad, pad, pad, pad);
        root.addView(qr, at(1920 / 2f - 260, 230, 520, 520));

        center = text(IosPalette.BODY, regular, IosPalette.SECONDARY);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setLineSpacing(0, 1.25f);
        root.addView(center, at(260, 790, 1400, 0));

        tg = TgCore.get(this);
        tg.listen(onState);
        tg.start();
        take(getIntent());
        render();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        take(i);
        render();
    }

    @Override protected void onDestroy() {
        tg.unlisten(onState);
        super.onDestroy();
    }

    /** Ссылка из пульта; без ссылки — последний открытый пост. */
    private void take(Intent i) {
        Uri u = i == null ? null : i.getData();
        String l = u != null ? u.toString() : prefs.getString("last_link", "");
        if (!l.equals(link)) { link = l; items.clear(); shownState = ""; }
    }

    // ---------- Экран по состоянию ----------

    private void render() {
        String s = tg.state;
        qr.setVisibility(View.GONE);
        center.setText("");
        if (!"ready".equals(s)) { grid.removeAllViews(); cards.clear(); }
        switch (s) {
            case "api":
                sub.setText("Нужен ключ приложения");
                center.setText((tg.hint.isEmpty() ? "" : tg.hint + "\n\n") + "Пульт в телефоне → вкладка «Телевизор» → Telegram:\nвставь api_id и api_hash с сайта my.telegram.org");
                break;
            case "novpn":
                sub.setText("Нет VPN");
                center.setText("Telegram работает только через VPN.\nВключи его — и всё продолжится само.");
                break;
            case "qr":
                sub.setText("Вход в аккаунт");
                qr.setImageBitmap(IosQr.make(tg.qrLink, px(488)));
                qr.setVisibility(View.VISIBLE);
                center.setText("В Telegram на телефоне: Настройки → Устройства → Подключить устройство.\nНаведи камеру на этот код.");
                break;
            case "password":
                sub.setText("Облачный пароль");
                center.setText((tg.hint.isEmpty() ? "" : tg.hint + "\n\n") + "На аккаунте стоит облачный пароль.\nВведи его в пульте: вкладка «Телевизор» → Telegram.");
                break;
            case "ready":
                if (link.isEmpty()) {
                    sub.setText("Готово");
                    center.setText("Отправь ссылку на пост с пульта:\nвкладка «Телевизор» → Открыть ссылку.");
                } else if (!shownState.equals("ready:" + link)) {
                    shownState = "ready:" + link;
                    load(link);
                }
                break;
            case "error":
                sub.setText("Ошибка");
                center.setText(tg.hint);
                break;
            default:
                sub.setText("Подключаюсь…");
        }
        if (!"ready".equals(s)) shownState = s;
        // Без QR-кода текст — по центру экрана, с кодом — под ним.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) center.getLayoutParams();
        lp.topMargin = px(qr.getVisibility() == View.VISIBLE ? 790 : 470);
        center.setLayoutParams(lp);
    }

    // ---------- Пост ----------

    private void load(String url) {
        sub.setText("Открываю пост…");
        grid.removeAllViews();
        cards.clear();
        tg.send(new TdApi.GetMessageLinkInfo(url), r -> {
            if (!(r instanceof TdApi.MessageLinkInfo) || ((TdApi.MessageLinkInfo) r).message == null) {
                sub.setText("Пост не открылся");
                center.setText(r instanceof TdApi.Error ? "Telegram: " + ((TdApi.Error) r).message : "Ссылка не ведёт на пост с видео или канал закрыт.");
                return;
            }
            TdApi.MessageLinkInfo info = (TdApi.MessageLinkInfo) r;
            prefs.edit().putString("last_link", url).apply();
            TdApi.Message m = info.message;
            tg.send(new TdApi.GetChat(m.chatId), c -> { if (c instanceof TdApi.Chat) title.setText(((TdApi.Chat) c).title); });
            if (m.mediaAlbumId == 0) { show(Arrays.asList(m)); return; }
            // Альбом: соседние сообщения канала (номера идут подряд, у TDLib — с шагом 2^20) с тем же альбомом.
            long[] ids = new long[21];
            for (int k = -10; k <= 10; k++) ids[k + 10] = m.id + ((long) k << 20);
            tg.send(new TdApi.GetMessages(m.chatId, ids), rr -> {
                List<TdApi.Message> album = new ArrayList<>();
                if (rr instanceof TdApi.Messages)
                    for (TdApi.Message x : ((TdApi.Messages) rr).messages) if (x != null && x.mediaAlbumId == m.mediaAlbumId) album.add(x);
                if (album.isEmpty()) album.add(m);
                show(album);
            });
        });
    }

    private void show(List<TdApi.Message> messages) {
        items.clear();
        String caption = "";
        for (TdApi.Message m : messages) {
            Item it = itemOf(m);
            if (it != null) items.add(it);
            String c = captionOf(m);
            if (caption.isEmpty() && !c.isEmpty()) caption = c;
        }
        for (int i = 0; i < items.size(); i++) if (items.get(i).title.isEmpty()) items.get(i).title = "Видео " + (i + 1);
        if (items.isEmpty()) {
            sub.setText("В посте нет видео");
            return;
        }
        String first = caption.split("\n")[0];
        sub.setText((first.isEmpty() ? "" : first + "  ·  ") + count(items.size()));
        grid.removeAllViews();
        cards.clear();
        for (int i = 0; i < items.size(); i++) addCard(i);
        index = 0;
        focus();
    }

    private static Item itemOf(TdApi.Message m) {
        Item it = new Item();
        if (m.content instanceof TdApi.MessageVideo) {
            TdApi.Video v = ((TdApi.MessageVideo) m.content).video;
            it.fileId = v.video.id;
            it.size = v.video.size > 0 ? v.video.size : v.video.expectedSize;
            it.unique = v.video.remote.uniqueId;
            it.duration = v.duration;
            it.title = clean(v.fileName);
            it.mini = v.minithumbnail == null ? null : v.minithumbnail.data;
            it.thumbId = v.thumbnail == null ? 0 : v.thumbnail.file.id;
            return it;
        }
        if (m.content instanceof TdApi.MessageDocument) {
            TdApi.Document d = ((TdApi.MessageDocument) m.content).document;
            if (d.mimeType == null || !d.mimeType.startsWith("video/")) return null;
            it.fileId = d.document.id;
            it.size = d.document.size > 0 ? d.document.size : d.document.expectedSize;
            it.unique = d.document.remote.uniqueId;
            it.title = clean(d.fileName);
            it.mini = d.minithumbnail == null ? null : d.minithumbnail.data;
            it.thumbId = d.thumbnail == null ? 0 : d.thumbnail.file.id;
            return it;
        }
        return null;
    }

    private static String captionOf(TdApi.Message m) {
        TdApi.FormattedText t = m.content instanceof TdApi.MessageVideo ? ((TdApi.MessageVideo) m.content).caption
                : m.content instanceof TdApi.MessageDocument ? ((TdApi.MessageDocument) m.content).caption : null;
        return t == null || t.text == null ? "" : t.text.trim();
    }

    /** Имя файла без расширения и подчёркиваний — как название. */
    private static String clean(String name) {
        if (name == null) return "";
        String n = name.replaceAll("\\.(mp4|mkv|mov|avi|webm|m4v)$", "").replace('_', ' ').trim();
        return n.matches("(?i)(video|img|vid)[ -]?\\d.*") ? "" : n;
    }

    private static String count(int n) { return n + " видео"; }

    static String time(int s) {
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String bytes(long b) {
        return b >= 1L << 30 ? String.format(Locale.forLanguageTag("ru"), "%.1f ГБ", b / (double) (1L << 30)) : (b >> 20) + " МБ";
    }

    // ---------- Карточки ----------

    private void addCard(int i) {
        Item it = items.get(i);
        float x = EDGE + (i % (int) COLS) * (CARD_W + GAP), y = (i / (int) COLS) * (CARD_H + GAP);
        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(false);
        ImageView pic = new ImageView(this);
        pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(IosPalette.GRAY6);
        bg.setCornerRadius(px(IosPalette.R_CARD));
        pic.setBackground(bg);
        card.addView(pic, new FrameLayout.LayoutParams(px(CARD_W), px(THUMB_H)));
        if (it.mini != null) {
            Bitmap b = BitmapFactory.decodeByteArray(it.mini, 0, it.mini.length);
            if (b != null) pic.setImageBitmap(b);
        }
        if (it.thumbId != 0) loadThumb(it.thumbId, pic);
        TextView num = badge(String.valueOf(i + 1));
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2);
        nl.leftMargin = nl.topMargin = px(IosPalette.S3);
        card.addView(num, nl);
        if (it.duration > 0) {
            TextView d = badge(time(it.duration));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.END);
            dl.rightMargin = px(IosPalette.S3);
            dl.topMargin = px(THUMB_H - IosPalette.S3) - px(IosPalette.FOOTNOTE + IosPalette.S2 + 4);
            card.addView(d, dl);
        }
        TextView t = text(IosPalette.CALLOUT, semibold, IosPalette.INK);
        t.setText(it.title);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(px(CARD_W), -2);
        tl.topMargin = px(THUMB_H + IosPalette.S3);
        card.addView(t, tl);
        TextView meta = text(IosPalette.FOOTNOTE, regular, IosPalette.TERTIARY);
        meta.setText(it.size > 0 ? bytes(it.size) : "");
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(px(CARD_W), -2);
        ml.topMargin = px(THUMB_H + IosPalette.S3 + 2 * IosPalette.CALLOUT + IosPalette.S2 + 6);
        card.addView(meta, ml);
        card.setPivotX(px(CARD_W) / 2f);
        card.setPivotY(px(THUMB_H) / 2f);
        grid.addView(card, at(x, y, CARD_W, CARD_H));
        cards.add(card);
    }

    private TextView badge(String s) {
        TextView b = text(IosPalette.FOOTNOTE, semibold, IosPalette.INK);
        b.setText(s);
        GradientDrawable g = new GradientDrawable();
        g.setColor(IosPalette.SCRIM);
        g.setCornerRadius(px(IosPalette.R_BADGE));
        b.setBackground(g);
        b.setPadding(px(IosPalette.S2), px(4), px(IosPalette.S2), px(4));
        return b;
    }

    private void loadThumb(int fileId, ImageView into) {
        tg.send(new TdApi.DownloadFile(fileId, 1, 0, 0, true), r -> {
            if (!(r instanceof TdApi.File) || !((TdApi.File) r).local.isDownloadingCompleted) return;
            String path = ((TdApi.File) r).local.path;
            new Thread(() -> {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 1;
                Bitmap b = BitmapFactory.decodeFile(path, o);
                if (b != null) into.post(() -> into.setImageBitmap(b));
            }, "tg-thumb").start();
        });
    }

    private void focus() {
        for (int i = 0; i < cards.size(); i++) {
            View c = cards.get(i);
            boolean on = i == index;
            ImageView pic = (ImageView) ((FrameLayout) c).getChildAt(0);
            GradientDrawable fg = new GradientDrawable();
            fg.setCornerRadius(px(IosPalette.R_CARD));
            fg.setColor(0);
            if (on) fg.setStroke(px(4), IosPalette.INK);
            pic.setForeground(fg);
            c.animate().scaleX(on ? 1.05f : 1f).scaleY(on ? 1.05f : 1f).alpha(on ? 1f : 0.75f).setDuration(160).start();
        }
        // Больше двух рядов — сдвигаем сетку так, чтобы выбранный ряд был виден.
        int row = index / (int) COLS;
        grid.animate().translationY(-px(Math.max(0, row - 1) * (CARD_H + GAP))).setDuration(200).start();
    }

    // ---------- Пульт ----------

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (cards.isEmpty()) return super.onKeyDown(code, e);
        int n = cards.size(), cols = (int) COLS;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (index > 0) index--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (index < n - 1) index++; break;
            case KeyEvent.KEYCODE_DPAD_UP: if (index - cols >= 0) index -= cols; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: index = Math.min(n - 1, index + cols); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                play(items.get(index));
                return true;
            default: return super.onKeyDown(code, e);
        }
        focus();
        return true;
    }

    private void play(Item it) {
        startActivity(new Intent(this, TgPlayerActivity.class)
                .putExtra("file", it.fileId).putExtra("size", it.size).putExtra("title", it.title)
                .putExtra("unique", it.unique).putExtra("duration", it.duration));
    }

    // ---------- Вёрстка ----------

    private int px(float v) { return Math.round(v * scale); }

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
        return lp;
    }
}
