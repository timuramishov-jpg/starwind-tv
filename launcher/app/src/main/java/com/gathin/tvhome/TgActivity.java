package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Свой клиент Telegram для видео. Главная: «Продолжить просмотр» и «Мои каналы»; канал — его видео карточками,
 * подгружаются по мере листания; ссылка с пульта — видео из поста. ОК — смотреть, «Меню» на видео — выбрать качество,
 * «Назад» — на шаг вверх. Вход по QR, сеть только через VPN (TgCore). Вёрстка — палитра и сетка IosPalette.
 */
public final class TgActivity extends Activity {
    private static final float EDGE = IosPalette.EDGE, GAP = IosPalette.S4;
    private static final int COLS = 4;
    private static final float CARD_W = (1920 - 2 * EDGE - (COLS - 1) * GAP) / COLS, THUMB_H = CARD_W * 9 / 16, CARD_H = THUMB_H + 124;
    private static final float TOP = 200, SECTION = 64;

    /** Одно видео: файл, облегчённые версии, откуда оно. */
    static final class Item {
        int fileId, duration, thumbId, height;
        long size, chatId, messageId;
        String title = "", unique = "", meta = "";
        byte[] mini;
        final List<Item> alts = new ArrayList<>();
    }

    /** Плитка на экране: видео или канал. */
    private static final class Tile {
        Item video;
        long channel;
        String channelTitle;
        View view;
        float x, y;
    }

    private enum Page { HOME, CHANNEL, POST }

    private TgCore tg;
    private SharedPreferences prefs;
    private FrameLayout root, content, panel;
    private TextView title, sub, center;
    private ImageView qr;
    private final List<Tile> tiles = new ArrayList<>();
    private int index, panelIndex = -1;
    private Page page = Page.HOME;
    private long channelId, nextFrom;
    private boolean loadingMore;
    private float contentBottom;
    private String link = "", shown = "";
    private float scale;
    private Typeface regular, semibold;
    private final Runnable onState = this::render;
    private List<Item> panelItems;

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

        content = new FrameLayout(this);
        root.addView(content, at(0, TOP, 1920, 0));
        // Шапка поверх ленты: при прокрутке карточки уходят под неё.
        FrameLayout head = new FrameLayout(this);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{IosPalette.BLACK, IosPalette.BLACK, 0});
        head.setBackground(fade);
        root.addView(head, at(0, 0, 1920, TOP));
        title = text(IosPalette.TITLE, semibold, IosPalette.INK);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, at(EDGE, IosPalette.S6, 1920 - 2 * EDGE, 0));
        sub = text(IosPalette.CALLOUT, regular, IosPalette.SECONDARY);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(sub, at(EDGE, IosPalette.S6 + 60, 1920 - 2 * EDGE, 0));

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
        root.addView(center, at(260, 470, 1400, 0));

        panel = new FrameLayout(this);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

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

    @Override protected void onResume() {
        super.onResume();
        // Вернулись из плеера: на главной обновить «Продолжить просмотр».
        if (page == Page.HOME && "ready".equals(tg.state) && !tiles.isEmpty()) showHome();
    }

    @Override protected void onDestroy() {
        tg.unlisten(onState);
        super.onDestroy();
    }

    private void take(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u != null) { link = u.toString(); page = Page.POST; shown = ""; }
    }

    // ---------- Состояние входа ----------

    private void render() {
        String s = tg.state;
        qr.setVisibility(View.GONE);
        center.setText("");
        if (!"ready".equals(s)) { clear(); title.setText("Telegram"); }
        switch (s) {
            case "api":
                sub.setText("Нужен ключ приложения");
                say((tg.hint.isEmpty() ? "" : tg.hint + "\n\n") + "Пульт в телефоне → вкладка «Телевизор» → Telegram:\nвставь api_id и api_hash");
                break;
            case "novpn":
                sub.setText("Нет VPN");
                say("Telegram работает только через VPN.\nВключи его — и всё продолжится само.");
                break;
            case "qr":
                sub.setText("Вход в аккаунт");
                qr.setImageBitmap(IosQr.make(tg.qrLink, px(488)));
                qr.setVisibility(View.VISIBLE);
                say("В Telegram на телефоне: Настройки → Устройства → Подключить устройство.\nНаведи камеру на этот код.");
                break;
            case "password":
                sub.setText("Облачный пароль");
                say((tg.hint.isEmpty() ? "" : tg.hint + "\n\n") + "На аккаунте стоит облачный пароль.\nВведи его в пульте: вкладка «Телевизор» → Telegram.");
                break;
            case "ready": {
                String want = page + ":" + link + ":" + channelId;
                if (!want.equals(shown)) {
                    shown = want;
                    if (page == Page.POST && !link.isEmpty()) load(link);
                    else if (page == Page.CHANNEL) openChannel(channelId, title.getText().toString());
                    else showHome();
                }
                break;
            }
            case "error":
                sub.setText("Ошибка");
                say(tg.hint);
                break;
            default:
                sub.setText("Подключаюсь…");
        }
        if (!"ready".equals(s)) shown = "";
    }

    private void say(String t) {
        center.setText(t);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) center.getLayoutParams();
        lp.topMargin = px(qr.getVisibility() == View.VISIBLE ? 790 : 470);
        center.setLayoutParams(lp);
    }

    private void clear() {
        content.removeAllViews();
        content.setTranslationY(0);
        tiles.clear();
        index = 0;
        setBottom(0);
    }

    /** Высота ленты — по содержимому: иначе карточки ниже экрана обрезаются даже после прокрутки. */
    private void setBottom(float y) {
        contentBottom = y;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
        lp.height = px(Math.max(1080 - TOP, y + IosPalette.S6));
        content.setLayoutParams(lp);
    }

    // ---------- Главная: продолжить просмотр и мои каналы ----------

    private void showHome() {
        page = Page.HOME;
        title.setText("Telegram");
        sub.setText("Ссылку на пост можно прислать с пульта");
        clear();
        List<long[]> recent = recent();
        final List<Item> cont = new ArrayList<>();
        final int[] left = {recent.size()};
        Runnable channels = () -> loadChannels(cont);
        if (recent.isEmpty()) { channels.run(); return; }
        for (long[] r : recent) {
            tg.send(new TdApi.GetChat(r[0]), ig -> tg.send(new TdApi.GetMessage(r[0], r[1]), m -> {
                if (m instanceof TdApi.Message) {
                    Item it = itemOf((TdApi.Message) m);
                    if (it != null) cont.add(it);
                }
                if (--left[0] == 0) {
                    // Порядок — как в списке «недавних», а не как пришли ответы.
                    List<Item> sorted = new ArrayList<>();
                    for (long[] rr : recent) for (Item it : cont) if (it.messageId == rr[1] && it.chatId == rr[0]) sorted.add(it);
                    cont.clear();
                    cont.addAll(sorted);
                    channels.run();
                }
            }));
        }
    }

    private void loadChannels(List<Item> cont) {
        tg.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 200), ignored ->
            tg.send(new TdApi.GetChats(new TdApi.ChatListMain(), 200), r -> {
                if (!(r instanceof TdApi.Chats)) { drawHome(cont, new ArrayList<>()); return; }
                long[] ids = ((TdApi.Chats) r).chatIds;
                List<TdApi.Chat> chans = new ArrayList<>();
                final int[] left = {ids.length};
                if (left[0] == 0) { drawHome(cont, chans); return; }
                final TdApi.Chat[] all = new TdApi.Chat[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    final int at = i;
                    tg.send(new TdApi.GetChat(ids[i]), c -> {
                        if (c instanceof TdApi.Chat) all[at] = (TdApi.Chat) c;
                        if (--left[0] == 0) {
                            for (TdApi.Chat ch : all)
                                if (ch != null && ch.type instanceof TdApi.ChatTypeSupergroup && ((TdApi.ChatTypeSupergroup) ch.type).isChannel) chans.add(ch);
                            drawHome(cont, chans.subList(0, Math.min(24, chans.size())));
                        }
                    });
                }
            }));
    }

    private void drawHome(List<Item> cont, List<TdApi.Chat> chans) {
        if (page != Page.HOME) return;
        clear();
        float y = 0;
        if (!cont.isEmpty()) {
            y = section("Продолжить просмотр", y);
            for (int i = 0; i < Math.min(COLS, cont.size()); i++) addVideo(cont.get(i), i, y);
            y += CARD_H + GAP;
        }
        if (!chans.isEmpty()) {
            y = section("Мои каналы", y);
            for (int i = 0; i < chans.size(); i++) addChannel(chans.get(i), i, y);
            y += ((chans.size() + COLS - 1) / COLS) * (CARD_H + GAP);
        }
        setBottom(y);
        if (tiles.isEmpty()) say("Здесь будут каналы, на которые ты подписан.\nА пост можно открыть ссылкой с пульта.");
        index = 0;
        focus();
    }

    private float section(String name, float y) {
        TextView t = text(IosPalette.HEADLINE, semibold, IosPalette.SECONDARY);
        t.setText(name);
        content.addView(t, at(EDGE, y + IosPalette.S2, 0, 0));
        return y + SECTION;
    }

    private void addChannel(TdApi.Chat ch, int i, float y0) {
        Tile t = new Tile();
        t.channel = ch.id;
        t.channelTitle = ch.title;
        t.x = EDGE + (i % COLS) * (CARD_W + GAP);
        t.y = y0 + (i / COLS) * (CARD_H + GAP);
        FrameLayout card = new FrameLayout(this);
        View bg = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(IosPalette.GRAY6);
        g.setCornerRadius(px(IosPalette.R_CARD));
        bg.setBackground(g);
        card.addView(bg, new FrameLayout.LayoutParams(px(CARD_W), px(THUMB_H)));
        ImageView ava = new ImageView(this);
        int s = px(150);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(s, s);
        al.leftMargin = (px(CARD_W) - s) / 2;
        al.topMargin = (px(THUMB_H) - s) / 2;
        card.addView(ava, al);
        if (ch.photo != null) {
            if (ch.photo.minithumbnail != null) {
                Bitmap m = BitmapFactory.decodeByteArray(ch.photo.minithumbnail.data, 0, ch.photo.minithumbnail.data.length);
                if (m != null) ava.setImageBitmap(circle(m));
            }
            loadImage(ch.photo.small.id, ava, true);
        } else {
            TextView letter = text(IosPalette.DISPLAY, semibold, IosPalette.SECONDARY);
            letter.setText(ch.title.isEmpty() ? "?" : ch.title.substring(0, 1).toUpperCase());
            letter.setGravity(Gravity.CENTER);
            card.addView(letter, new FrameLayout.LayoutParams(px(CARD_W), px(THUMB_H)));
        }
        TextView name = text(IosPalette.CALLOUT, semibold, IosPalette.INK);
        name.setText(ch.title);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(px(CARD_W), -2);
        nl.topMargin = px(THUMB_H + IosPalette.S3);
        card.addView(name, nl);
        place(t, card);
    }

    // ---------- Канал: его видео, с подгрузкой ----------

    private void openChannel(long chatId, String name) {
        page = Page.CHANNEL;
        channelId = chatId;
        shown = page + ":" + link + ":" + channelId;
        title.setText(name);
        sub.setText("Видео канала");
        clear();
        nextFrom = 0;
        tg.send(new TdApi.OpenChat(chatId), r -> { });
        moreChannel();
    }

    private void moreChannel() {
        if (loadingMore) return;
        loadingMore = true;
        tg.send(new TdApi.SearchChatMessages(channelId, null, "", null, nextFrom, 0, 40, new TdApi.SearchMessagesFilterVideo()), r -> {
            loadingMore = false;
            if (page != Page.CHANNEL) return;
            if (!(r instanceof TdApi.FoundChatMessages)) {
                if (tiles.isEmpty()) sub.setText(r instanceof TdApi.Error ? "Telegram: " + ((TdApi.Error) r).message : "Видео не нашлись");
                return;
            }
            TdApi.FoundChatMessages f = (TdApi.FoundChatMessages) r;
            boolean first = tiles.isEmpty();
            for (TdApi.Message m : f.messages) {
                Item it = itemOf(m);
                if (it == null) continue;
                if (it.title.isEmpty()) it.title = firstLine(captionOf(m));
                if (it.title.isEmpty()) it.title = "Видео";
                addVideo(it, tiles.size(), 0);
            }
            nextFrom = f.nextFromMessageId;
            setBottom(((tiles.size() + COLS - 1) / COLS) * (CARD_H + GAP));
            sub.setText(f.totalCount > 0 ? f.totalCount + " видео" : "Видео канала");
            if (first && !tiles.isEmpty()) { index = 0; focus(); }
            if (tiles.isEmpty()) say("В канале нет видео");
        });
    }

    // ---------- Пост по ссылке ----------

    private void load(String url) {
        page = Page.POST;
        sub.setText("Открываю пост…");
        clear();
        tg.send(new TdApi.GetMessageLinkInfo(url), r -> {
            if (!(r instanceof TdApi.MessageLinkInfo) || ((TdApi.MessageLinkInfo) r).message == null) {
                sub.setText("Пост не открылся");
                say(r instanceof TdApi.Error ? "Telegram: " + ((TdApi.Error) r).message : "Ссылка не ведёт на пост с видео или канал закрыт.");
                return;
            }
            TdApi.Message m = ((TdApi.MessageLinkInfo) r).message;
            tg.send(new TdApi.GetChat(m.chatId), c -> { if (c instanceof TdApi.Chat) title.setText(((TdApi.Chat) c).title); });
            if (m.mediaAlbumId == 0) { showPost(Arrays.asList(m)); return; }
            // Альбом: соседние сообщения канала (номера идут подряд, у TDLib — с шагом 2^20) с тем же альбомом.
            long[] ids = new long[21];
            for (int k = -10; k <= 10; k++) ids[k + 10] = m.id + ((long) k << 20);
            tg.send(new TdApi.GetMessages(m.chatId, ids), rr -> {
                List<TdApi.Message> album = new ArrayList<>();
                if (rr instanceof TdApi.Messages)
                    for (TdApi.Message x : ((TdApi.Messages) rr).messages) if (x != null && x.mediaAlbumId == m.mediaAlbumId) album.add(x);
                if (album.isEmpty()) album.add(m);
                showPost(album);
            });
        });
    }

    private void showPost(List<TdApi.Message> messages) {
        if (page != Page.POST) return;
        clear();
        String caption = "";
        List<Item> items = new ArrayList<>();
        for (TdApi.Message m : messages) {
            Item it = itemOf(m);
            if (it != null) items.add(it);
            if (caption.isEmpty()) caption = captionOf(m);
        }
        if (items.isEmpty()) { sub.setText("В посте нет видео"); return; }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).title.isEmpty()) items.get(i).title = "Видео " + (i + 1);
            addVideo(items.get(i), i, 0);
        }
        setBottom(((items.size() + COLS - 1) / COLS) * (CARD_H + GAP));
        String first = firstLine(caption);
        sub.setText((first.isEmpty() ? "" : first + "  ·  ") + items.size() + " видео");
        index = 0;
        focus();
    }

    // ---------- Видео из сообщения ----------

    static Item itemOf(TdApi.Message m) {
        Item it = new Item();
        it.chatId = m.chatId;
        it.messageId = m.id;
        if (m.content instanceof TdApi.MessageVideo) {
            TdApi.MessageVideo mv = (TdApi.MessageVideo) m.content;
            TdApi.Video v = mv.video;
            fill(it, v.video);
            it.duration = v.duration;
            it.height = v.height;
            it.title = clean(v.fileName);
            it.mini = v.minithumbnail == null ? null : v.minithumbnail.data;
            it.thumbId = v.thumbnail == null ? 0 : v.thumbnail.file.id;
            // Облегчённые версии (Telegram делает их сам для больших видео) — для выбора качества.
            if (mv.alternativeVideos != null)
                for (TdApi.AlternativeVideo a : mv.alternativeVideos) {
                    if (a.video == null || a.height >= v.height) continue;
                    Item alt = new Item();
                    fill(alt, a.video);
                    alt.height = a.height;
                    alt.duration = it.duration;
                    alt.chatId = it.chatId;
                    alt.messageId = it.messageId;
                    it.alts.add(alt);
                }
        } else if (m.content instanceof TdApi.MessageDocument) {
            TdApi.Document d = ((TdApi.MessageDocument) m.content).document;
            if (d.mimeType == null || !d.mimeType.startsWith("video/")) return null;
            fill(it, d.document);
            it.title = clean(d.fileName);
            it.mini = d.minithumbnail == null ? null : d.minithumbnail.data;
            it.thumbId = d.thumbnail == null ? 0 : d.thumbnail.file.id;
        } else return null;
        it.meta = (it.height > 0 ? it.height + "p  ·  " : "") + (it.size > 0 ? bytes(it.size) : "")
                + (m.date > 0 ? "  ·  " + new java.text.SimpleDateFormat("d MMM", new Locale("ru")).format(new java.util.Date(m.date * 1000L)) : "");
        return it;
    }

    private static void fill(Item it, TdApi.File f) {
        it.fileId = f.id;
        it.size = f.size > 0 ? f.size : f.expectedSize;
        it.unique = f.remote == null ? "" : f.remote.uniqueId;
    }

    static String captionOf(TdApi.Message m) {
        TdApi.FormattedText t = m.content instanceof TdApi.MessageVideo ? ((TdApi.MessageVideo) m.content).caption
                : m.content instanceof TdApi.MessageDocument ? ((TdApi.MessageDocument) m.content).caption : null;
        return t == null || t.text == null ? "" : t.text.trim();
    }

    private static String firstLine(String s) { return s.split("\n")[0].trim(); }

    private static String clean(String name) {
        if (name == null) return "";
        String n = name.replaceAll("(?i)\\.(mp4|mkv|mov|avi|webm|m4v)$", "").replace('_', ' ').trim();
        return n.matches("(?i)(video|img|vid)[ -]?\\d.*|mp4|mov|mkv|video") ? "" : n;
    }

    static String time(int s) {
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String bytes(long b) {
        return b >= 1L << 30 ? String.format(new Locale("ru"), "%.1f ГБ", b / (double) (1L << 30)) : (b >> 20) + " МБ";
    }

    // ---------- Недавние: «Продолжить просмотр» ----------

    private List<long[]> recent() {
        List<long[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new long[]{o.getLong("c"), o.getLong("m")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Записать в «недавние» (из плеера). */
    static void remember(SharedPreferences p, long chat, long msg) {
        try {
            JSONArray a = new JSONArray(p.getString("recent", "[]")), out = new JSONArray();
            out.put(new JSONObject().put("c", chat).put("m", msg));
            for (int i = 0; i < a.length() && out.length() < 12; i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.getLong("c") != chat || o.getLong("m") != msg) out.put(o);
            }
            p.edit().putString("recent", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void forget(SharedPreferences p, long chat, long msg) {
        try {
            JSONArray a = new JSONArray(p.getString("recent", "[]")), out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.getLong("c") != chat || o.getLong("m") != msg) out.put(o);
            }
            p.edit().putString("recent", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ---------- Карточка видео ----------

    private void addVideo(Item it, int i, float y0) {
        Tile t = new Tile();
        t.video = it;
        t.x = EDGE + (i % COLS) * (CARD_W + GAP);
        t.y = y0 + (i / COLS) * (CARD_H + GAP);
        FrameLayout card = new FrameLayout(this);
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
        if (it.thumbId != 0) loadImage(it.thumbId, pic, false);
        if (it.duration > 0) {
            TextView d = badge(time(it.duration));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.END);
            dl.rightMargin = px(IosPalette.S3);
            dl.topMargin = px(THUMB_H - IosPalette.S3 - IosPalette.FOOTNOTE - IosPalette.S2 - 4);
            card.addView(d, dl);
        }
        // Досмотрено до середины — полоска внизу превью, как в YouTube.
        int pos = it.unique.isEmpty() ? 0 : prefs.getInt("pos_" + it.unique, 0);
        if (pos > 0 && it.duration > 0) {
            View track = new View(this);
            track.setBackgroundColor(IosPalette.SCRIM_SOFT);
            FrameLayout.LayoutParams tl0 = new FrameLayout.LayoutParams(px(CARD_W - 2 * IosPalette.S3), px(6));
            tl0.leftMargin = px(IosPalette.S3);
            tl0.topMargin = px(THUMB_H - IosPalette.S2 - 6);
            card.addView(track, tl0);
            View done = new View(this);
            done.setBackgroundColor(IosPalette.BLUE);
            FrameLayout.LayoutParams dl0 = new FrameLayout.LayoutParams(Math.max(px(6), Math.round(px(CARD_W - 2 * IosPalette.S3) * Math.min(1f, pos / (float) it.duration))), px(6));
            dl0.leftMargin = px(IosPalette.S3);
            dl0.topMargin = px(THUMB_H - IosPalette.S2 - 6);
            card.addView(done, dl0);
        }
        TextView name = text(IosPalette.CALLOUT, semibold, IosPalette.INK);
        name.setText(it.title);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(px(CARD_W), -2);
        nl.topMargin = px(THUMB_H + IosPalette.S3);
        card.addView(name, nl);
        TextView meta = text(IosPalette.FOOTNOTE, regular, IosPalette.TERTIARY);
        meta.setText(it.meta + (it.alts.isEmpty() ? "" : "  ·  Меню — качество"));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(px(CARD_W), -2);
        ml.topMargin = px(THUMB_H + IosPalette.S3 + 2 * 32 + IosPalette.S2);
        card.addView(meta, ml);
        place(t, card);
    }

    private void place(Tile t, FrameLayout card) {
        card.setPivotX(px(CARD_W) / 2f);
        card.setPivotY(px(THUMB_H) / 2f);
        card.setAlpha(0.75f);
        content.addView(card, at(t.x, t.y, CARD_W, CARD_H));
        t.view = card;
        tiles.add(t);
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

    private void loadImage(int fileId, ImageView into, boolean round) {
        tg.send(new TdApi.DownloadFile(fileId, 1, 0, 0, true), r -> {
            if (!(r instanceof TdApi.File) || !((TdApi.File) r).local.isDownloadingCompleted) return;
            String path = ((TdApi.File) r).local.path;
            new Thread(() -> {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 1;
                Bitmap b = BitmapFactory.decodeFile(path, o);
                if (b != null) {
                    Bitmap out = round ? circle(b) : b;
                    into.post(() -> into.setImageBitmap(out));
                }
            }, "tg-image").start();
        });
    }

    private static Bitmap circle(Bitmap src) {
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, p);
        return out;
    }

    // ---------- Фокус и прокрутка ----------

    // ---------- Подготовка выбранного видео ----------

    private final android.os.Handler prepHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable prepare = () -> {
        if (index < 0 || index >= tiles.size() || tiles.get(index).video == null) return;
        Item it = tiles.get(index).video;
        if (it.size > (50L << 20)) TgCore.get(this).prefetch(it.fileId, it.size);
    };

    private void focus() {
        // Задержался на видео — заранее качаем его начало и хвост с описанием: ОК — и сразу играет.
        prepHandler.removeCallbacks(prepare);
        prepHandler.postDelayed(prepare, 900);
        for (int i = 0; i < tiles.size(); i++) {
            View c = tiles.get(i).view;
            boolean on = i == index;
            View face = ((FrameLayout) c).getChildAt(0);
            GradientDrawable fg = new GradientDrawable();
            fg.setCornerRadius(px(IosPalette.R_CARD));
            fg.setColor(0);
            if (on) fg.setStroke(px(4), IosPalette.INK);
            face.setForeground(fg);
            c.animate().scaleX(on ? 1.05f : 1f).scaleY(on ? 1.05f : 1f).alpha(on ? 1f : 0.75f).setDuration(160).start();
        }
        if (tiles.isEmpty()) return;
        // Выбранная карточка — во втором «ряду» экрана: видно и её, и что дальше.
        Tile t = tiles.get(index);
        float view = 1080 - TOP;
        float shift = Math.max(0, Math.min(t.y - SECTION, Math.max(0, contentBottom - view + IosPalette.S6)));
        if (t.y + CARD_H - shift > view) shift = t.y + CARD_H - view + IosPalette.S4;
        content.animate().translationY(-px(shift)).setDuration(200).start();
        // В канале — подгружаем следующую порцию, когда дошли до предпоследнего ряда.
        if (page == Page.CHANNEL && nextFrom != 0 && index >= tiles.size() - 2 * COLS) moreChannel();
    }

    /** Ближайшая плитка в соседнем ряду (ряды на главной — разных разделов). */
    private int vertical(int dir) {
        Tile cur = tiles.get(index);
        int best = -1;
        float bestRow = Float.MAX_VALUE, bestDx = Float.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            float dy = (t.y - cur.y) * dir;
            if (dy <= 1) continue;
            float dx = Math.abs(t.x - cur.x);
            if (dy < bestRow - 1 || (Math.abs(dy - bestRow) <= 1 && dx < bestDx)) { bestRow = dy; bestDx = dx; best = i; }
        }
        return best < 0 ? index : best;
    }

    // ---------- Пульт ----------

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (panel.getVisibility() == View.VISIBLE) return panelKey(code) || super.onKeyDown(code, e);
        if (code == KeyEvent.KEYCODE_BACK && page != Page.HOME && "ready".equals(tg.state)) {
            link = "";
            showHome();
            shown = page + ":" + link + ":" + channelId;
            return true;
        }
        if (tiles.isEmpty()) return super.onKeyDown(code, e);
        int n = tiles.size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (index > 0 && tiles.get(index - 1).y == tiles.get(index).y) index--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (index < n - 1 && tiles.get(index + 1).y == tiles.get(index).y) index++; break;
            case KeyEvent.KEYCODE_DPAD_UP: index = vertical(-1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: index = vertical(1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: {
                Tile t = tiles.get(index);
                if (t.video != null) play(t.video);
                else openChannel(t.channel, t.channelTitle);
                return true;
            }
            case KeyEvent.KEYCODE_MENU: {
                Tile t = tiles.get(index);
                if (t.video != null) quality(t.video);
                return true;
            }
            default: return super.onKeyDown(code, e);
        }
        focus();
        return true;
    }

    private void play(Item it) {
        prepHandler.removeCallbacks(prepare);
        tg.stopPrefetch();
        startActivity(new Intent(this, TgPlayerActivity.class)
                .putExtra("file", it.fileId).putExtra("size", it.size).putExtra("title", it.title)
                .putExtra("unique", it.unique).putExtra("duration", it.duration)
                .putExtra("chat", it.chatId).putExtra("message", it.messageId));
    }

    // ---------- Качество: оригинал или облегчённые версии ----------

    private void quality(Item it) {
        panelItems = new ArrayList<>();
        panelItems.add(it);
        panelItems.addAll(it.alts);
        panel.removeAllViews();
        panel.setBackgroundColor(IosPalette.SCRIM);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(IosPalette.S3), px(IosPalette.S6), px(IosPalette.S3), px(IosPalette.S5));
        box.setBackgroundColor(IosPalette.GRAY6);
        TextView h = text(IosPalette.TITLE, semibold, IosPalette.INK);
        h.setText("Качество");
        h.setPadding(px(IosPalette.S3), 0, px(IosPalette.S3), px(IosPalette.S4));
        box.addView(h);
        for (int i = 0; i < panelItems.size(); i++) {
            Item q = panelItems.get(i);
            TextView r = text(IosPalette.BODY, regular, IosPalette.INK);
            r.setText((i == 0 ? "Оригинал" : "Облегчённое") + (q.height > 0 ? ", " + q.height + "p" : "") + (q.size > 0 ? "  ·  " + bytes(q.size) : ""));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(IosPalette.S3), 0, px(IosPalette.S3), 0);
            box.addView(r, new LinearLayout.LayoutParams(-1, px(78)));
        }
        if (panelItems.size() == 1) {
            TextView none = text(IosPalette.CALLOUT, regular, IosPalette.SECONDARY);
            none.setText("У этого видео только оригинал");
            none.setPadding(px(IosPalette.S3), px(IosPalette.S4), px(IosPalette.S3), 0);
            box.addView(none);
        }
        panel.addView(box, at(1920 - 620, 0, 620, 1080));
        panel.setVisibility(View.VISIBLE);
        panelIndex = 0;
        paintPanel();
    }

    private void paintPanel() {
        LinearLayout box = (LinearLayout) panel.getChildAt(0);
        for (int i = 0; i < panelItems.size(); i++) {
            TextView r = (TextView) box.getChildAt(i + 1);
            boolean on = i == panelIndex;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(IosPalette.R_ROW));
            g.setColor(on ? IosPalette.INK : 0);
            r.setBackground(g);
            r.setTextColor(on ? IosPalette.ON_LIGHT : IosPalette.INK);
        }
    }

    private boolean panelKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: panelIndex = Math.max(0, panelIndex - 1); paintPanel(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: panelIndex = Math.min(panelItems.size() - 1, panelIndex + 1); paintPanel(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                Item q = panelItems.get(panelIndex), base = panelItems.get(0);
                panel.setVisibility(View.GONE);
                Item play = q;
                if (q != base) { play.title = base.title; play.unique = base.unique; }
                play(play);
                return true;
            }
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                panel.setVisibility(View.GONE);
                return true;
            default: return true;
        }
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
