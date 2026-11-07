package com.gathin.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Поиск лаунчера в боковой панели: поле ввода, недавние запросы, результаты по всем сервисам. */
final class IosSearch {
    private final IosHome h;

    IosSearch(IosHome h) { this.h = h; }

    /** Поиск с пульта телевизора: поле ввода, клавиатура Яндекса умеет и голос. */
    EditText field;

    /** Поиск в боковой панели лаунчера: поле ввода в стиле меню и недавние запросы. Клавиатура — системная, другой нет. */
    void ask() {
        List<String> recent = h.stored("searches");
        List<Runnable> actions = new ArrayList<>();
        for (String q : recent) actions.add(() -> run(q));
        h.showMenu("Поиск", recent, actions);
        h.menuKind = "search";
        LinearLayout panel = (LinearLayout) ((View) h.menuPanel.getParent()).getParent();
        EditText field = new EditText(h);
        field.setSingleLine(true);
        field.setHint("Фильм, ролик, канал");
        field.setHintTextColor(IosPalette.TERTIARY);
        field.setTextColor(h.INK);
        field.setTypeface(h.regular);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, h.px(IosPalette.BODY));
        field.setPadding(h.px(IosPalette.S3), 0, h.px(IosPalette.S3), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(IosPalette.GRAY5);
        bg.setCornerRadius(h.px(IosPalette.R_ROW));
        field.setBackground(bg);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setOnEditorActionListener((v, action, ev) -> {
            String q = field.getText().toString().trim();
            if (q.isEmpty()) return true;
            List<String> recentQ = h.stored("searches");
            recentQ.remove(q);
            recentQ.add(0, q);
            while (recentQ.size() > 8) recentQ.remove(recentQ.size() - 1);
            h.store("searches", recentQ);
            hideKeyboard(field);
            run(q);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h.px(84));
        lp.bottomMargin = h.px(IosPalette.S3);
        panel.addView(field, 1, lp);
        if (!recent.isEmpty()) {
            TextView head = h.text(IosPalette.FOOTNOTE, h.medium, IosPalette.SECONDARY);
            head.setText("Недавние");
            head.setPadding(h.px(IosPalette.S3), 0, h.px(IosPalette.S3), h.px(12));
            panel.addView(head, 2, new LinearLayout.LayoutParams(-1, -2));
        }
        this.field = field;
        h.menuIndex = -1;
        h.paintMenu();
        field.requestFocus();
        h.handler.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) h.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null && field.isAttachedToWindow()) imm.showSoftInput(field, 0);
        }, 150);
    }

    void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) h.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** Пока курсор в поле поиска, кнопки идут полю; вниз — к недавним запросам, назад — закрыть. */
    boolean key(KeyEvent e) {
        if (field == null || !h.overlayOpen() || !"search".equals(h.menuKind) || !field.hasFocus()) return false;
        int key = e.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) h.closeMenu();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && !h.menuViews.isEmpty()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                hideKeyboard(field);
                field.clearFocus();
                h.root.requestFocus();
                h.menuIndex = 0;
                h.paintMenu();
            }
            return true;
        }
        return h.superDispatch(e);
    }

    void run(String q) {
        if (q.isEmpty()) return;
        h.note("Ищу «" + q + "»");
        h.feed.search(q, found -> {
            if (found.isEmpty()) { h.note("Ничего не нашлось"); return; }
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (IosHome.FeedItem it : found) {
                labels.add(it.subtitle.split(" · ")[0] + ": " + it.title);
                actions.add(() -> {
                    try { h.startActivity(it.open); } catch (Exception e) { h.note("Не открылось"); }
                });
            }
            h.showMenu("Поиск: " + q, labels, actions);
        });
    }
}
