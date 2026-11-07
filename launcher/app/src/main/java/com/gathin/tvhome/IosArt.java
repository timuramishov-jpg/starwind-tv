package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.List;

/** Рисунки лаунчера на холсте: карточки экрана VPN, цвет обложки, «краска» логотипов.
 *  Вынесено из IosHome: рисование отдельно от экрана и пульта. */
final class IosArt {
    private static final int INK = IosPalette.INK, PILL = IosPalette.GRAY6;
    private static final float POSTER_W = 330, CARD_H = 495;
    private final float s;
    private final Typeface medium, semibold;

    IosArt(float scale, Typeface medium, Typeface semibold) {
        this.s = scale;
        this.medium = medium;
        this.semibold = semibold;
    }

    private int px(float v) { return Math.round(v * s); }

    /** Доля «краски» в картинке: сколько её площади реально закрашено (по прозрачности). */
    static float inkShare(Bitmap b) {
        if (b == null) return 0;
        int w = Math.min(96, b.getWidth()), h = Math.max(1, Math.round(b.getHeight() * (float) w / b.getWidth()));
        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
        long sum = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) sum += Color.alpha(s.getPixel(x, y));
        if (s != b) s.recycle();
        return sum / 255f / (w * h);
    }

    Bitmap art() {
        Bitmap b = Bitmap.createBitmap(px(POSTER_W), px(CARD_H), Bitmap.Config.ARGB_8888);
        b.eraseColor(PILL);
        return b;
    }

    Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    void glow(Canvas c, float cx, float cy, float r, int accent) {
        Paint g = paint(accent);
        g.setShader(new RadialGradient(cx, cy, r, IosPalette.glow(accent), accent & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, g);
    }

    /** Вертикальный тумблер: включённый светится цветом секции, бегунок наверху. */
    Bitmap switchArt(boolean on, int accent) {
        Bitmap b = art();
        Canvas c = new Canvas(b);
        float cx = b.getWidth() / 2f, cy = b.getHeight() / 2f, w = px(150), h = px(270);
        if (on) glow(c, cx, cy, px(260), accent);
        c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, w / 2, w / 2, paint(on ? accent : IosPalette.GRAY4));
        float r = w / 2 - px(12), ky = on ? cy - h / 2 + w / 2 : cy + h / 2 - w / 2;
        c.drawCircle(cx, ky + px(5), r, paint(IosPalette.SCRIM_SOFT));
        c.drawCircle(cx, ky, r, paint(INK));
        return b;
    }

    /** Связь: флаг страны сверху, под ним полукруг отклика, число внутри с запасом от дуги, «мс» под ним. */
    Bitmap linkArt(String flag, int grade, String value, int accent, List<long[]> history) {
        Bitmap b = art();
        Canvas c = new Canvas(b);
        float cx = b.getWidth() / 2f;
        if (!flag.isEmpty()) {
            Paint f = paint(INK);
            f.setTextSize(px(76));
            f.setTextAlign(Paint.Align.CENTER);
            c.drawText(flag, cx, px(112), f);
        }
        drawHistory(c, accent, history);
        float cy = px(296), r = px(104);
        Paint arc = paint(IosPalette.GRAY4);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(px(14));
        arc.setStrokeCap(Paint.Cap.ROUND);
        android.graphics.RectF box = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(box, 180, 180, false, arc);
        if (grade >= 0) {
            arc.setColor(grade >= 2 ? accent : grade == 1 ? IosPalette.ORANGE : IosPalette.RED);
            c.drawArc(box, 180, 180 * (grade + 1) / 4f, false, arc);
        }
        java.util.regex.Matcher ms = java.util.regex.Pattern.compile("(\\d+) мс").matcher(value);
        Paint t = paint(INK);
        t.setTypeface(semibold);
        t.setTextAlign(Paint.Align.CENTER);
        if (ms.matches()) {
            t.setTextSize(px(56));
            c.drawText(ms.group(1), cx, cy - px(14), t);
            Paint unit = paint(IosPalette.SECONDARY);
            unit.setTypeface(medium);
            unit.setTextSize(px(22));
            unit.setTextAlign(Paint.Align.CENTER);
            c.drawText("мс", cx, cy + px(22), unit);
        } else {
            t.setTextSize(px(value.length() > 3 ? 26 : 48));
            c.drawText(value, cx, cy - px(14), t);
        }
        return b;
    }

    /** Отклик за последний час тонкой линией внизу карточки: видно, ровная связь или скачет. */
    private void drawHistory(Canvas c, int accent, List<long[]> h) {
        float left = px(30), right = c.getWidth() - px(30), top = px(372), bottom = px(452);
        Paint label = paint(IosPalette.GRAY1);
        label.setTextSize(px(20));
        label.setTypeface(medium);
        c.drawText("отклик за час", left, px(478), label);
        Paint base = paint(IosPalette.GRAY4);
        base.setStrokeWidth(px(2));
        c.drawLine(left, bottom, right, bottom, base);
        if (h.size() < 2) return;
        long now = System.currentTimeMillis(), from = now - 3600_000L;
        long max = 100;
        for (long[] p : h) max = Math.max(max, p[1]);
        android.graphics.Path line = new android.graphics.Path();
        boolean first = true;
        for (long[] p : h) {
            float x = left + (right - left) * (p[0] - from) / 3600_000f;
            float y = bottom - (bottom - top) * Math.min(1f, p[1] / (float) max);
            if (first) { line.moveTo(x, y); first = false; } else line.lineTo(x, y);
        }
        Paint stroke = paint(accent);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(px(4));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(line, stroke);
        Paint top10 = paint(IosPalette.GRAY1);
        top10.setTextSize(px(18));
        top10.setTextAlign(Paint.Align.RIGHT);
        c.drawText(max + " мс", right, top - px(8), top10);
    }

    /** Смена сервера: стопка из трёх серверов, верхний светится. */
    Bitmap serverArt(int accent) {
        Bitmap b = art();
        Canvas c = new Canvas(b);
        float cx = b.getWidth() / 2f, w = px(200), h = px(56), gap = px(22), top = b.getHeight() / 2f - (3 * h + 2 * gap) / 2;
        glow(c, cx, top + h / 2, px(180), accent);
        for (int i = 0; i < 3; i++) {
            float y = top + i * (h + gap);
            c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, px(16), px(16), paint(i == 0 ? accent : IosPalette.GRAY4));
            c.drawCircle(cx - w / 2 + px(30), y + h / 2, px(9), paint(i == 0 ? INK : IosPalette.TERTIARY));
            Paint line = paint(i == 0 ? INK : IosPalette.TERTIARY);
            c.drawRoundRect(cx - w / 2 + px(56), y + h / 2 - px(5), cx + w / 2 - px(28), y + h / 2 + px(5), px(5), px(5), line);
        }
        return b;
    }

    static int coverTone(Bitmap b) {
        if (b == null || b.isRecycled()) return 0;
        Bitmap s = Bitmap.createScaledBitmap(b, 24, 14, true);
        float r = 0, g = 0, bl = 0, wsum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < s.getHeight(); y++) for (int x = 0; x < s.getWidth(); x++) {
            int c = s.getPixel(x, y);
            Color.colorToHSV(c, hsv);
            float w = 0.05f + hsv[1] * hsv[2] * (hsv[2] > 0.15f ? 1f : 0.2f);
            r += Color.red(c) * w; g += Color.green(c) * w; bl += Color.blue(c) * w; wsum += w;
        }
        if (s != b) s.recycle();
        int avg = Color.rgb(Math.round(r / wsum), Math.round(g / wsum), Math.round(bl / wsum));
        Color.colorToHSV(avg, hsv);
        hsv[1] = Math.min(0.75f, hsv[1] * 0.67f);
        hsv[2] = Math.max(0.55f, Math.min(0.9f, hsv[2] * 1.4f));
        return Color.HSVToColor(hsv);
    }
}
