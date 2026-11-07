package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Своя экранная клавиатура в стиле лаунчера: тёмная панель, клавиши-пилюли, выбранная — белая, как строка меню.
 * Русская, английская и цифры; стрелки пульта ходят по клавишам, ОК нажимает. Работает в отдельном лёгком
 * процессе, чтобы не будить весь лаунчер. Пока она открыта, страница пульта в телефоне показывает поле ввода:
 * набранное на телефоне приходит сюда целиком, с кириллицей.
 */
public final class IosKeyboard extends InputMethodService {
    static final String TYPE = "com.gathin.tvhome.TYPE";
    private static final String[][] RU = {{"й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х", "ъ"},
            {"ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э"}, {"⇧", "я", "ч", "с", "м", "и", "т", "ь", "б", "ю", "⌫"}};
    private static final String[][] EN = {{"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"}, {"⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"}};
    private static final String[][] NUM = {{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", ";", "(", ")", "₽", "&", "@", "\""}, {".", ",", "?", "!", "'", "#", "%", "+", "=", "⌫"}};
    private static final String SPACE = "пробел", LANG = "lang", NUMS = "123", DONE = "Готово";

    private Keys view;
    private String[][] layout = RU;
    private boolean caps, english;
    private BroadcastReceiver phone;

    @Override public View onCreateInputView() {
        view = new Keys(this);
        return view;
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        layout = english ? EN : RU;
        caps = (info.inputType & EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0;
        if (view != null) view.build();
        typing(true);
        // Текст с телефона: присылает сервер пульта (от имени оболочки, у неё есть DUMP).
        if (phone == null) {
            phone = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String key = getSharedPreferences("ios_remote", MODE_MULTI_PROCESS).getString("key", "");
                    if (key.isEmpty() || !key.equals(i.getStringExtra("k"))) return;
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) return;
                    String t = i.getStringExtra("t");
                    if (t != null && !t.isEmpty()) ic.commitText(t, 1);
                    if (i.getBooleanExtra("done", false)) done();
                }
            };
            registerReceiver(phone, new IntentFilter(TYPE), "android.permission.DUMP", null);
        }
    }

    @Override public void onFinishInputView(boolean finishing) {
        super.onFinishInputView(finishing);
        typing(false);
    }

    @Override public void onDestroy() {
        if (phone != null) unregisterReceiver(phone);
        phone = null;
        typing(false);
        super.onDestroy();
    }

    /** Флажок для страницы пульта: сейчас на телевизоре ждут текст. */
    private void typing(boolean on) {
        try {
            File d = new File(getExternalFilesDir(null), "remote");
            d.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(d, "typing"))) { o.write((on ? "1" : "0").getBytes()); }
        } catch (Exception ignored) { }
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (!isInputViewShown() || view == null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: view.move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: view.move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: view.move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: view.move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: press(view.focused()); return true;
            case KeyEvent.KEYCODE_DEL: press("⌫"); return true;
            default: return super.onKeyDown(code, e);
        }
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (isInputViewShown() && (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) return true;
        return super.onKeyUp(code, e);
    }

    private void press(String k) {
        InputConnection ic = getCurrentInputConnection();
        if (k == null || ic == null) return;
        switch (k) {
            case "⌫": ic.deleteSurroundingText(1, 0); break;
            case "⇧": caps = !caps; break;
            case SPACE: ic.commitText(" ", 1); break;
            case LANG: english = !english; layout = english ? EN : RU; view.build(); break;
            case NUMS: layout = layout == NUM ? (english ? EN : RU) : NUM; view.build(); break;
            case DONE: done(); return;
            default:
                ic.commitText(caps ? k.toUpperCase() : k, 1);
                if (caps) caps = false;
        }
        view.invalidate();
    }

    private void done() {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info == null ? EditorInfo.IME_ACTION_DONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (ic != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action);
        else if (ic != null) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        requestHideSelf(0);
    }

    /** Панель клавиш: рисуется целиком на холсте, без отдельных View на каждую клавишу. */
    private final class Keys extends View {
        private final float s;
        private final List<List<String>> rows = new ArrayList<>();
        private final List<List<RectF>> boxes = new ArrayList<>();
        private int row = 1, col = 4;
        private final Typeface regular, semibold;

        Keys(Context c) {
            super(c);
            s = c.getResources().getDisplayMetrics().widthPixels / 1920f;
            regular = c.getResources().getFont(R.font.sf_regular);
            semibold = c.getResources().getFont(R.font.sf_semibold);
            setBackgroundColor(IosPalette.alpha(IosPalette.GRAY6, 0.97f));
            build();
        }

        void build() {
            rows.clear();
            for (String[] r : layout) { List<String> l = new ArrayList<>(); for (String k : r) l.add(k); rows.add(l); }
            List<String> last = new ArrayList<>();
            last.add(layout == NUM ? (english ? "ABC" : "АБВ") : NUMS);
            if (layout != NUM) last.add(LANG);
            last.add(SPACE);
            last.add(DONE);
            rows.add(last);
            row = Math.min(row, rows.size() - 1);
            col = Math.min(col, rows.get(row).size() - 1);
            requestLayout();
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(430 * s));
        }

        String focused() {
            String k = rows.get(row).get(col);
            return k.equals("ABC") || k.equals("АБВ") ? NUMS : k;
        }

        void move(int dr, int dc) {
            if (dr != 0) {
                int r = Math.max(0, Math.min(rows.size() - 1, row + dr));
                if (r != row && !boxes.isEmpty()) {
                    float x = boxes.get(row).get(col).centerX();
                    int best = 0;
                    for (int i = 0; i < boxes.get(r).size(); i++)
                        if (Math.abs(boxes.get(r).get(i).centerX() - x) < Math.abs(boxes.get(r).get(best).centerX() - x)) best = i;
                    row = r;
                    col = best;
                }
            } else col = Math.max(0, Math.min(rows.get(row).size() - 1, col + dc));
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float width = getWidth(), keyH = 78 * s, gap = 14 * s, top = 30 * s, side = 160 * s;
            boxes.clear();
            Paint key = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setTextAlign(Paint.Align.CENTER);
            for (int r = 0; r < rows.size(); r++) {
                List<String> keys = rows.get(r);
                boolean bottom = r == rows.size() - 1;
                float unit = (width - 2 * side - 11 * gap) / 12, y = top + r * (keyH + gap);
                // Ширина клавиш: обычные — одна доля, пробел — пять, служебные — полторы-две.
                float total = 0;
                for (String k : keys) total += weight(k, bottom);
                float rowW = total * unit + (keys.size() - 1) * gap, x = (width - rowW) / 2;
                List<RectF> line = new ArrayList<>();
                for (int i = 0; i < keys.size(); i++) {
                    String k = keys.get(i);
                    RectF box = new RectF(x, y, x + weight(k, bottom) * unit, y + keyH);
                    line.add(box);
                    boolean on = r == row && i == col, special = k.length() > 1 || k.equals("⇧") || k.equals("⌫");
                    key.setColor(on ? IosPalette.INK : special ? IosPalette.GRAY4 : IosPalette.GRAY5);
                    c.drawRoundRect(box, IosPalette.R_ROW * s, IosPalette.R_ROW * s, key);
                    text.setColor(on ? IosPalette.ON_LIGHT : k.equals("⇧") && caps ? IosPalette.ORANGE : IosPalette.INK);
                    text.setTypeface(special ? semibold : regular);
                    text.setTextSize((special ? 26 : 34) * s);
                    String label = k.equals(LANG) ? (english ? "РУС" : "ENG") : k.equals(SPACE) ? (english ? "space" : "пробел")
                            : caps && !special ? k.toUpperCase() : k;
                    c.drawText(label, box.centerX(), box.centerY() - (text.descent() + text.ascent()) / 2, text);
                    x += box.width() + gap;
                }
                boxes.add(line);
            }
        }

        private float weight(String k, boolean bottom) {
            if (!bottom) return k.equals("⇧") || k.equals("⌫") ? 1.5f : 1f;
            return k.equals(SPACE) ? 5.5f : 2f;
        }
    }
}
