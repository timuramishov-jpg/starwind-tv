package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Боковая панель справа: все меню лаунчера (настройки, поиск, приложение, Wi-Fi, подсказки) — одна и та же. */
final class IosMenu {
    private final IosHome h;
    /** Пункты и что делает каждый; INFO — строка-сведение, не выбирается. */
    final List<Runnable> acts = new ArrayList<>();
    final List<TextView> views = new ArrayList<>();
    int index;
    /** Что открыто: settings, wifi, search… или пусто. */
    String kind = "";
    LinearLayout listView;

    IosMenu(IosHome h) { this.h = h; }


    /** Все меню выезжают одной панелью справа, как настройки. */
    void show(String title, List<String> labels, List<Runnable> actions) { show(title, labels, actions, true); }

    /** Боковая панель. slide = false — панель уже на экране и только меняет содержимое. */
    void show(String title, List<String> labels, List<Runnable> actions, boolean slide) {
        kind = "";
        h.overlay.removeAllViews();
        acts.clear();
        acts.addAll(actions);
        views.clear();
        LinearLayout panel = new LinearLayout(h);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(h.px(IosPalette.S3), h.px(IosPalette.S6), h.px(IosPalette.S3), h.px(40));
        panel.setBackgroundColor(h.PANEL);
        TextView t = h.text(IosPalette.TITLE, h.semibold, h.INK);
        t.setText(title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(h.px(IosPalette.S3), 0, h.px(IosPalette.S3), h.px(IosPalette.S4));
        panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
        // Пункты лежат в отдельном окне: панель стоит на месте, листается только список.
        FrameLayout window = new FrameLayout(h);
        window.setClipChildren(true);
        LinearLayout list = new LinearLayout(h);
        list.setOrientation(LinearLayout.VERTICAL);
        // Высота списка — все строки целиком: иначе окно ужимает список до своей высоты и нижние строки обрезаются.
        window.addView(list, new FrameLayout.LayoutParams(-1, h.px(labels.size() * h.MENU_ROW)));
        panel.addView(window, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (String l : labels) {
            TextView r = h.text(IosPalette.BODY, h.regular, h.INK);
            r.setText(l);
            r.setSingleLine(true);
            r.setEllipsize(TextUtils.TruncateAt.END);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(h.px(IosPalette.S3), 0, h.px(IosPalette.S3), 0);
            list.addView(r, new LinearLayout.LayoutParams(-1, h.px(h.MENU_ROW)));
            views.add(r);
        }
        h.overlay.addView(panel, h.at(1920 - h.MENU_W, 0, h.MENU_W, 1080));
        listView = list;
        h.overlay.setVisibility(View.VISIBLE);
        if (slide) {
            panel.setTranslationX(h.px(h.MENU_W));
            panel.animate().translationX(0).setDuration(h.NORMAL).setInterpolator(h.ease).start();
        }
        index = 0;
        while (index < actions.size() && actions.get(index) == INFO) index++;
        if (index >= actions.size()) index = -1;
        paint();
    }

    void paint() {
        if (listView != null) {
            View window = (View) listView.getParent();
            float room = window.getHeight() > 0 ? window.getHeight() : h.px(1080 - 64 - 40 - 90);
            float bottom = h.px((Math.max(0, index) + 1) * h.MENU_ROW + h.MENU_ROW / 2);
            listView.animate().translationY(-Math.max(0, bottom - room)).setDuration(h.FAST).setInterpolator(h.ease).start();
        }
        for (int i = 0; i < views.size(); i++) {
            TextView r = views.get(i);
            boolean on = i == index, info = i < acts.size() && acts.get(i) == INFO;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(h.px(IosPalette.R_ROW));
            g.setColor(on ? h.ROW_ON : 0x00000000);
            r.setBackground(g);
            r.setTextColor(on ? h.ROW_ON_TEXT : info ? h.INK_2 : h.INK);
        }
    }

    void key(int key) {
        if (key == KeyEvent.KEYCODE_DPAD_UP && index == 0 && "h.search".equals(kind) && h.search.field != null) {
            index = -1;
            paint();
            h.search.field.requestFocus();
            return;
        }
        // Строки-сведения (INFO) не выбираются: курсор их перескакивает.
        int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        for (int i = index + step; step != 0 && i >= 0 && i < views.size(); i += step) {
            if (i < acts.size() && acts.get(i) == INFO) continue;
            index = i;
            break;
        }
        paint();
    }

    /** Строка-сведение в меню: показывает значение, но не выбирается. */
    static final Runnable INFO = new IosHome.Keep(() -> { });

    void close() {
        if (h.search.field != null) { h.search.hideKeyboard(h.search.field); h.search.field = null; }
        kind = "";
        h.feed.stopPolling();
        h.overlay.setVisibility(View.GONE);
        h.overlay.removeAllViews();
    }

    void appMenu() {
        if (h.focused == null || !(h.focused.getTag() instanceof IosHome.App)) return;
        final IosHome.App a = (IosHome.App) h.focused.getTag();
        final boolean inDock = h.dock.contains(a);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Открыть");
        actions.add(() -> h.launch(a));
        if (IosFeed.YOUTUBE.equals(a.pkg)) {
            labels.add(h.feed.signedIn() ? "Выйти из аккаунта YouTube" : "Войти в YouTube: лента подписок");
            actions.add(() -> { if (h.feed.signedIn()) { h.feed.signOut(); h.reload(); } else h.startYouTubeSignIn(); });
        }
        if (Arrays.asList(IosFeed.YOUTUBE, IosFeed.VK, IosFeed.RUTUBE, IosFeed.KINOPOISK, IosFeed.ZONA).contains(a.pkg)) {
            String m = h.feed.mode(a.pkg);
            boolean yt = IosFeed.YOUTUBE.equals(a.pkg);
            String now = m.equals("off") ? "выключена" : yt && (m.equals("popular") || !h.feed.signedIn()) ? "популярное" : yt ? "подписки" : "включена";
            String next = m.equals("off") ? "" : yt && m.isEmpty() && h.feed.signedIn() ? "popular" : "off";
            labels.add("Лента: " + now + " (ОК, сменить)");
            actions.add(() -> {
                h.feed.setMode(a.pkg, next);
                h.feedShown.remove(a.pkg);
                h.showSelected();
            });
        }
        labels.add("Переместить");
        actions.add(h::startMoving);
        labels.add(inDock ? "Убрать из дока" : "Добавить в док");
        actions.add(() -> {
            if (inDock) { h.dock.remove(a); h.others.add(0, a); }
            else if (h.dock.size() < h.MAX_DOCK) { h.others.remove(a); h.dock.add(a); }
            else h.note("В доке не больше " + h.MAX_DOCK + " приложений");
            h.saveLayout();
            h.reload();
        });
        labels.add("Скрыть с экрана");
        actions.add(() -> {
            h.dock.remove(a);
            h.others.remove(a);
            List<String> hidden = h.stored("hidden");
            hidden.add(a.pkg);
            h.store("hidden", hidden);
            h.saveLayout();
            h.reload();
        });
        final Intent info = SystemSettings.appInfo(h, a.pkg);
        if (info != null) {
            labels.add("О приложении");
            actions.add(() -> h.open(info));
        }
        if (!a.system) {
            labels.add("Удалить");
            actions.add(() -> h.open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        show(a.label, labels, actions);
    }
}
