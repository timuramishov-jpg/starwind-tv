package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/** Вырезает логотип из значка и собирает его тёмные версии: серую в покое и цветную в фокусе. */
final class IosIcons {
    static final int SURFACE = 0xFF131315, CARD = 0xFF0C0C0E, RIM = 0x1AFFFFFF, MONO_LOW = 118, MONO_HIGH = 244;
    private static final int SRC = 320, FALLBACK_ACCENT = 0xFF9A9AA2;

    static final class Art { Bitmap mono, color, ring; int accent; }
    static final class Logo { Bitmap color, mono; int accent; }

    private IosIcons() {}

    static Path squircle(float size) {
        Path p = new Path();
        float c = size / 2f;
        for (int i = 0; i <= 180; i++) {
            double t = i * Math.PI / 90, co = Math.cos(t), si = Math.sin(t);
            float x = (float) (c + c * Math.signum(co) * Math.pow(Math.abs(co), 0.4));
            float y = (float) (c + c * Math.signum(si) * Math.pow(Math.abs(si), 0.4));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    /** Логотип на прозрачном фоне: у адаптивных значков передний слой, у остальных срезается заливка от краёв. */
    static Logo logo(Drawable icon, Drawable banner) {
        Bitmap src = Bitmap.createBitmap(SRC, SRC, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(src);
        boolean keyed = false;
        if (icon instanceof AdaptiveIconDrawable) {
            Drawable fg = ((AdaptiveIconDrawable) icon).getForeground();
            if (fg != null) {
                int extra = SRC / 4;
                fg.setBounds(-extra, -extra, SRC + extra, SRC + extra);
                fg.draw(c);
                keyed = coverage(src) > 0.02f;
            }
            if (!keyed) { src.eraseColor(0); icon.setBounds(0, 0, SRC, SRC); icon.draw(c); }
        } else if (icon != null) {
            icon.setBounds(0, 0, SRC, SRC);
            icon.draw(c);
        } else if (banner != null) {
            int bw = Math.max(1, banner.getIntrinsicWidth()), bh = Math.max(1, banner.getIntrinsicHeight());
            int h = Math.round(SRC * bh / (float) bw), top = (SRC - h) / 2;
            banner.setBounds(0, top, SRC, top + h);
            banner.draw(c);
        }
        if (!keyed) key(src);
        Bitmap cropped = crop(src);
        Logo l = new Logo();
        l.color = cropped;
        l.mono = monochrome(cropped);
        l.accent = accent(cropped);
        return l;
    }

    /** Готовый силуэт логотипа: белая маска становится серой в покое и цветной в фокусе. */
    static Logo fromMask(Bitmap mask, int accent) {
        Logo l = new Logo();
        l.mono = tinted(mask, 0xFFB0B0B5);
        l.color = tinted(mask, accent);
        l.accent = accent;
        return l;
    }

    private static Bitmap tinted(Bitmap mask, int color) {
        Bitmap out = Bitmap.createBitmap(mask.getWidth(), mask.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        new Canvas(out).drawBitmap(mask, 0, 0, p);
        return out;
    }

    private static float coverage(Bitmap b) {
        int n = 0, step = 4, all = 0;
        for (int y = 0; y < b.getHeight(); y += step) for (int x = 0; x < b.getWidth(); x += step) {
            all++;
            if (Color.alpha(b.getPixel(x, y)) > 60) n++;
        }
        return n / (float) Math.max(1, all);
    }

    private static int dist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /** Заливка от всех краёв: идёт, пока цвет меняется плавно, поэтому снимает и ровный фон, и градиент. */
    private static void key(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] original = px.clone();
        boolean[] gone = new boolean[w * h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            queue[tail++] = x; gone[x] = true;
            int bottom = (h - 1) * w + x;
            if (!gone[bottom]) { queue[tail++] = bottom; gone[bottom] = true; }
        }
        for (int y = 1; y < h - 1; y++) {
            int l = y * w, r = y * w + w - 1;
            queue[tail++] = l; gone[l] = true;
            queue[tail++] = r; gone[r] = true;
        }
        while (head < tail) {
            int p = queue[head++], x = p % w, y = p / w, v = px[p];
            for (int k = 0; k < 4; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int q = ny * w + nx;
                if (gone[q]) continue;
                int o = px[q];
                if (Color.alpha(o) < 50 || (Color.alpha(v) >= 50 && dist(o, v) < 24)) { gone[q] = true; queue[tail++] = q; }
            }
        }
        int left = w * h - tail;
        // Съели почти всё или почти ничего: фон неотделим, логотип остаётся целым значком.
        if (left < w * h / 25 || tail < w * h / 12) return;
        for (int i = 0; i < px.length; i++) if (gone[i]) px[i] = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x;
            if (gone[i]) continue;
            int near = (gone[i - 1] ? 1 : 0) + (gone[i + 1] ? 1 : 0) + (gone[i - w] ? 1 : 0) + (gone[i + w] ? 1 : 0);
            if (near > 0) px[i] = (original[i] & 0xFFFFFF) | (Math.min(Color.alpha(original[i]), 255 - near * 45) << 24);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static Bitmap crop(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), l = w, t = h, r = -1, d = -1;
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) if (Color.alpha(b.getPixel(x, y)) > 40) {
            l = Math.min(l, x); t = Math.min(t, y); r = Math.max(r, x); d = Math.max(d, y);
        }
        if (r < l) return b;
        return Bitmap.createBitmap(b, l, t, Math.min(w - l, r - l + 2), Math.min(h - t, d - t + 2));
    }

    private static Bitmap monochrome(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float lo = 1, hi = 0;
        for (int v : px) if (Color.alpha(v) > 80) { float l = luma(v); lo = Math.min(lo, l); hi = Math.max(hi, l); }
        boolean flat = hi - lo < 0.12f;
        for (int i = 0; i < px.length; i++) {
            int a = Color.alpha(px[i]);
            float n = flat ? 1f : (luma(px[i]) - lo) / (hi - lo);
            int g = Math.round(MONO_LOW + (MONO_HIGH - MONO_LOW) * n);
            px[i] = Color.argb(a, g, g, g);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static float luma(int v) {
        return (0.3f * Color.red(v) + 0.59f * Color.green(v) + 0.11f * Color.blue(v)) / 255f;
    }

    private static int accent(Bitmap b) {
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < b.getHeight(); y += 3) for (int x = 0; x < b.getWidth(); x += 3) {
            int v = b.getPixel(x, y);
            if (Color.alpha(v) < 120) continue;
            Color.colorToHSV(v, hsv);
            if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue;
            double weight = hsv[1] * hsv[2];
            r += Color.red(v) * weight; g += Color.green(v) * weight; bl += Color.blue(v) * weight; n += weight;
        }
        if (n < 30) return FALLBACK_ACCENT;
        Color.colorToHSV(Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n)), hsv);
        hsv[1] = Math.max(0.55f, Math.min(0.92f, hsv[1]));
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static void drawLogo(Canvas c, Bitmap logo, float box, float cx, float cy) {
        float k = box / Math.max(logo.getWidth(), logo.getHeight());
        float w = logo.getWidth() * k, h = logo.getHeight() * k;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(logo, new Rect(0, 0, logo.getWidth(), logo.getHeight()),
                new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), p);
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private static Bitmap tileWith(Logo l, Bitmap logo, int size, boolean lit) {
        Bitmap raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(raw);
        c.drawColor(lit ? mix(SURFACE, l.accent, 0.16f) : SURFACE);
        drawLogo(c, logo, size * 0.60f, size / 2f, size / 2f);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        Canvas o = new Canvas(out);
        Path path = squircle(size);
        o.drawPath(path, p);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, size / 90f));
        rim.setColor(RIM);
        o.drawPath(path, rim);
        raw.recycle();
        return out;
    }

    static Art tile(Logo l, int size) {
        Art a = new Art();
        a.mono = tileWith(l, l.mono, size, false);
        a.color = tileWith(l, l.color, size, true);
        a.accent = l.accent;
        return a;
    }

    static Bitmap ring(int size, float width) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(Color.WHITE);
        new Canvas(out).drawPath(squircle(size), p);
        return out;
    }

    private static Bitmap card(Logo l, Bitmap logo, String name, Typeface tf, int w, int h, boolean lit) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float r = h * 0.085f;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(CARD);
        RectF box = new RectF(0, 0, w, h);
        c.drawRoundRect(box, r, r, fill);
        if (lit) {
            Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            int a = (l.accent & 0xFFFFFF) | 0x66000000;
            glow.setShader(new RadialGradient(w * 0.72f, h * 0.5f, w * 0.62f, a, l.accent & 0xFFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, r, r, glow);
        }
        drawLogo(c, logo, h * 0.56f, w * 0.72f, h * 0.5f);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(tf);
        t.setColor(lit ? Color.WHITE : 0xFFB8B8BE);
        t.setTextSize(h * 0.115f);
        String shown = name;
        while (t.measureText(shown) > w * 0.46f && shown.length() > 4) shown = shown.substring(0, shown.length() - 2);
        if (!shown.equals(name)) shown = shown.trim() + "…";
        c.drawText(shown, h * 0.11f, h * 0.87f, t);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2f);
        rim.setColor(RIM);
        c.drawRoundRect(new RectF(1, 1, w - 1, h - 1), r, r, rim);
        return out;
    }

    static Art cardArt(Logo l, String name, Typeface tf, int w, int h) {
        Art a = new Art();
        a.mono = card(l, l.mono, name, tf, w, h, false);
        a.color = card(l, l.color, name, tf, w, h, true);
        a.accent = l.accent;
        return a;
    }
}
