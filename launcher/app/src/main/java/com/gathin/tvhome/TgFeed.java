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
            try { write(c, collect(tg)); }
            catch (Exception e) { Log.w(TAG, "build: " + e); }
            finally { building = false; }
        }, "tg-feed").start();
    }

    private static List<JSONObject> collect(TgCore tg) throws Exception {
        tg.call(new TdApi.LoadChats(new TdApi.ChatListMain(), 100), 15_000);
        TdApi.Object r = tg.call(new TdApi.GetChats(new TdApi.ChatListMain(), 100), 15_000);
        List<JSONObject> out = new ArrayList<>();
        if (!(r instanceof TdApi.Chats)) return out;
        int channels = 0;
        for (long id : ((TdApi.Chats) r).chatIds) {
            if (channels >= 20) break;
            TdApi.Object c = tg.call(new TdApi.GetChat(id), 10_000);
            if (!(c instanceof TdApi.Chat)) continue;
            TdApi.Chat chat = (TdApi.Chat) c;
            if (!(chat.type instanceof TdApi.ChatTypeSupergroup) || !((TdApi.ChatTypeSupergroup) chat.type).isChannel) continue;
            channels++;
            TdApi.Object f = tg.call(new TdApi.SearchChatMessages(id, null, "", null, 0, 0, 4, new TdApi.SearchMessagesFilterVideo()), 15_000);
            if (!(f instanceof TdApi.FoundChatMessages)) continue;
            for (TdApi.Message m : ((TdApi.FoundChatMessages) f).messages) {
                TgActivity.Item it = TgActivity.itemOf(m);
                if (it == null) continue;
                String caption = m.content instanceof TdApi.MessageVideo && ((TdApi.MessageVideo) m.content).caption != null
                        ? ((TdApi.MessageVideo) m.content).caption.text.split("\n")[0].trim() : "";
                out.add(new JSONObject().put("chat", m.chatId).put("msg", m.id).put("date", m.date)
                        .put("title", !caption.isEmpty() ? caption : it.title.isEmpty() ? "Видео" : it.title)
                        .put("channel", chat.title).put("duration", it.duration).put("thumb", it.thumbId));
            }
        }
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
        if (items.isEmpty()) return;
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
        tmp.renameTo(new File(c.getFilesDir(), FILE));
        Log.i(TAG, items.size() + " videos");
    }
}
