package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Лента Telegram для дока лаунчера: свежие видео из каналов, на которые подписан. Строится в процессе :tg
 * (там TDLib) и кладётся файлом tg_feed.json с путями к превью — лаунчер читает его, как ленты других сервисов.
 */
final class TgFeed {
    private static final String TAG = "TgFeed";
    static final String FILE = "tg_feed.json";
    private static volatile boolean building;

    private TgFeed() { }

    static void build(Context c, TgCore tg) {
        if (building) return;
        building = true;
        new Thread(() -> {
            try { write(c, collect(c, tg)); }
            catch (Exception e) { Log.w(TAG, "build: " + e); }
            finally { building = false; }
        }, "tg-feed").start();
    }

    /** Длинное видео: фильм, запись стрима, большой выпуск. Короткие ролики новостных каналов в ленту не идут. */
    static final int LONG_SECONDS = 15 * 60;
    static final long BIG_BYTES = 300L << 20;
    static final String CHANNELS = "tg_channels.json";

    static boolean isLong(TgActivity.Item it) {
        return it.duration >= LONG_SECONDS || (it.duration == 0 && it.size >= BIG_BYTES);
    }

    private static List<JSONObject> collect(Context ctx, TgCore tg) throws Exception {
        tg.call(new TdApi.LoadChats(new TdApi.ChatListMain(), 200), 15_000);
        TdApi.Object r = tg.call(new TdApi.GetChats(new TdApi.ChatListMain(), 200), 15_000);
        List<JSONObject> out = new ArrayList<>();
        if (!(r instanceof TdApi.Chats)) return out;
        JSONArray videoChannels = new JSONArray();
        int channels = 0;
        for (long id : ((TdApi.Chats) r).chatIds) {
            if (channels >= 60) break;
            TdApi.Object c = tg.call(new TdApi.GetChat(id), 10_000);
            if (!(c instanceof TdApi.Chat)) continue;
            TdApi.Chat chat = (TdApi.Chat) c;
            if (!(chat.type instanceof TdApi.ChatTypeSupergroup) || !((TdApi.ChatTypeSupergroup) chat.type).isChannel) continue;
            channels++;
            // Видео и файлы-видео (фильмы часто кладут документом); из последних 30 берём только длинные.
            List<TdApi.Message> found = new ArrayList<>();
            for (TdApi.SearchMessagesFilter filter : new TdApi.SearchMessagesFilter[]{new TdApi.SearchMessagesFilterVideo(), new TdApi.SearchMessagesFilterDocument()}) {
                TdApi.Object f = tg.call(new TdApi.SearchChatMessages(id, null, "", null, 0, 0, 30, filter), 15_000);
                if (f instanceof TdApi.FoundChatMessages) found.addAll(Arrays.asList(((TdApi.FoundChatMessages) f).messages));
            }
            int taken = 0;
            Collections.sort(found, (x, y) -> Integer.compare(y.date, x.date));
            for (TdApi.Message m : found) {
                TgActivity.Item it = TgActivity.itemOf(m);
                if (it == null || !isLong(it)) continue;
                if (taken == 0) videoChannels.put(id);
                if (taken++ >= 3) continue;  // не больше трёх свежих с канала — лента не из одного канала
                String caption = TgActivity.captionOf(m).split("\n")[0].trim();
                out.add(new JSONObject().put("chat", m.chatId).put("msg", m.id).put("date", m.date)
                        .put("title", !caption.isEmpty() ? caption : it.title.isEmpty() ? "Видео" : it.title)
                        .put("channel", chat.title).put("duration", it.duration).put("thumb", it.thumbId));
            }
        }
        // Каналы с длинными видео — для главной клиента («Мои каналы» без новостных).
        File tmp = new File(ctx.getFilesDir(), CHANNELS + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(videoChannels.toString().getBytes(StandardCharsets.UTF_8)); }
        tmp.renameTo(new File(ctx.getFilesDir(), CHANNELS));
        Collections.sort(out, (a, b) -> Long.compare(b.optLong("date"), a.optLong("date")));
        List<JSONObject> top = new ArrayList<>(out.subList(0, Math.min(24, out.size())));
        // Превью — заранее, синхронно: лаунчер берёт готовые файлы.
        for (JSONObject o : top) {
            int thumb = o.optInt("thumb");
            if (thumb == 0) continue;
            TdApi.Object f = tg.call(new TdApi.DownloadFile(thumb, 1, 0, 0, true), 20_000);
            if (f instanceof TdApi.File && ((TdApi.File) f).local.isDownloadingCompleted) o.put("image", ((TdApi.File) f).local.path);
        }
        return top;
    }

    private static void write(Context c, List<JSONObject> items) throws Exception {
        // Пустую ленту тоже пишем: иначе в доке остаются старые короткие ролики.
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
        tmp.renameTo(new File(c.getFilesDir(), FILE));
        Log.i(TAG, items.size() + " videos");
    }
}
