package com.gathin.tvhome;

import android.graphics.Color;

/**
 * Единственный источник цветов всей сборки: лаунчер, заставки, рисунки, пульт в телефоне (remote.html повторяет эти значения).
 * Правила:
 *  1. Нейтрали — только чёрный и шесть серых по шкале iOS (тёмная тема). Других серых не бывает.
 *  2. Текст — три уровня: INK (основной), SECONDARY (пояснения), TERTIARY (подсказки, неактивное).
 *  3. Цвет — только системные акценты iOS. У каждого сервиса свой акцент из этого же набора:
 *     красный YouTube и красный Браузера — один и тот же RED, синий ВК и синий файлов — один BLUE.
 *  4. Затемнения — три ступени SCRIM. Прозрачность акцента — две ступени: GLOW и TINT.
 *  5. Новые цвета в коде не пишутся: только ссылки сюда. Проверяет PaletteTest.
 */
final class IosPalette {
    private IosPalette() { }

    // Нейтрали
    static final int BLACK = 0xFF000000;
    static final int GRAY6 = 0xFF1C1C1E;   // карточки, панели
    static final int GRAY5 = 0xFF2C2C2E;   // поднятое: выбранная плашка, кнопки
    static final int GRAY4 = 0xFF3A3A3C;   // дорожки, выключенные тумблеры
    static final int GRAY3 = 0xFF48484A;   // разделители, крупные линии
    static final int GRAY2 = 0xFF636366;   // неактивные значки
    static final int GRAY1 = 0xFF8E8E93;   // значки в покое

    // Текст
    static final int INK = 0xFFF5F5F7;
    static final int SECONDARY = 0xFFAEAEB2;
    static final int TERTIARY = 0xFF6E6E73;
    /** Текст на светлой плашке (выбранная строка меню, бейдж). */
    static final int ON_LIGHT = 0xFF0B0B0D;

    // Акценты iOS, тёмная тема
    static final int RED = 0xFFFF453A;
    static final int ORANGE = 0xFFFF9F0A;
    static final int YELLOW = 0xFFFFD60A;
    static final int GREEN = 0xFF30D158;
    static final int TEAL = 0xFF40C8E0;
    static final int BLUE = 0xFF0A84FF;
    static final int INDIGO = 0xFF5E5CE6;
    static final int PURPLE = 0xFFBF5AF2;
    static final int PINK = 0xFFFF375F;

    static final int[] ACCENTS = {RED, ORANGE, YELLOW, GREEN, TEAL, BLUE, INDIGO, PURPLE, PINK};

    // Затемнения
    static final int SCRIM_STRONG = 0xD9000000;  // под текстом на фото
    static final int SCRIM = 0xB3000000;         // под меню
    static final int SCRIM_SOFT = 0x66000000;    // тень, плашка длительности
    /** Тонкая линия по краю выбранного: как у окон macOS. */
    static final int HAIRLINE = 0x4DFFFFFF;
    /** Стеклянная карточка поверх фото или фона. */
    static final int GLASS = 0x1FFFFFFF;

    // Шрифт: SF Pro, шесть размеров на весь экран (в точках макета 1920×1080). Других размеров нет.
    static final float DISPLAY = 88, TITLE = 44, HEADLINE = 34, BODY = 30, CALLOUT = 26, FOOTNOTE = 22;
    // Скругления: значок-бейдж, строка и поле, карточка, плашка-пилюля и крупные панели.
    static final float R_BADGE = 10, R_ROW = 20, R_CARD = 26, R_PILL = 40;

    // Отступы: сетка 8 точек (4 — только для тонкой подгонки внутри мелких элементов). Других отступов нет.
    static final float S1 = 8, S2 = 16, S3 = 24, S4 = 32, S5 = 48, S6 = 64, S7 = 96;
    /** Поле экрана слева и справа: всё начинается и кончается на одной линии. */
    static final float EDGE = 56;

    /** Акцент с прозрачностью: свечение (GLOW) и подложка (TINT). */
    static int glow(int accent) { return (accent & 0x00FFFFFF) | 0x77000000; }
    static int tint(int accent) { return (accent & 0x00FFFFFF) | 0x55000000; }
    static int alpha(int color, float a) { return (color & 0x00FFFFFF) | (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24); }

    /** Любой цвет → ближайший акцент палитры; бледное и серое → GRAY1. Для значков приложений без своего акцента. */
    static int snap(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.25f || hsv[2] < 0.25f) return GRAY1;
        int best = GRAY1;
        float bestD = Float.MAX_VALUE;
        float[] a = new float[3];
        for (int c : ACCENTS) {
            Color.colorToHSV(c, a);
            float d = Math.abs(hsv[0] - a[0]);
            d = Math.min(d, 360 - d);
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }
}
