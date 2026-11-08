package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Карточки ленты: рамка и подписи, обложка рядом с выбранной, подсветка выбранной, затемнение остальных. */
final class IosCards {
    private final IosHome h;

    IosCards(IosHome h) { this.h = h; }

    /** Карточка ленты. Сама рамка и подписи создаются всегда, а обложка, плашка длительности
     *  и «дыхание» — только у карточек рядом с выбранной (fillCard): так длинная лента почти ничего не весит. */
    void add(IosHome.FeedItem item, int i) {
        FrameLayout card = new FrameLayout(h);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(h.PILL);
        bg.setCornerRadius(h.px(IosPalette.R_CARD));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setTag(item);
        float w = item.poster ? h.POSTER_W : h.CARD_W, x = h.MAIN_X + i * (w + h.CARD_GAP);
        h.cardsLayer.addView(card, h.at(x, h.CARDS_Y, w, h.CARD_H));
        h.cardViews.add(card);
        // Подпись под каждой карточкой: в покое приглушена, у выбранной яркая и с описанием.
        TextView name = h.text(item.poster ? IosPalette.CALLOUT : IosPalette.HEADLINE, h.medium, h.INK);
        name.setText(item.title == null ? "" : item.title);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAlpha(h.LABEL_REST);
        h.cardsLayer.addView(name, h.at(x + 4, h.CARDS_Y + h.CARD_H + 26, w - 8, 0));
        TextView sub = h.text(item.poster ? IosPalette.FOOTNOTE : IosPalette.CALLOUT, h.regular, h.INK_2);
        sub.setText(item.subtitle == null ? "" : item.subtitle);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setAlpha(0f);
        h.cardsLayer.addView(sub, h.at(x + 4, h.CARDS_Y + h.CARD_H + (item.poster ? 66 : 74), w - 8, 0));
        h.cardLabels.put(card, new TextView[]{name, sub});
        if (i < NEAR_CARDS) fill(card, item, true);
    }

    private static final int NEAR_CARDS = 6;

    /** Лента целиком не строится: рамки, подписи и обложки — только для первых карточек, остальные создаются,
     *  когда до них долистали. Пересборка ленты со всеми 30–40 карточками и стала полусекундным фризом при листании дока. */
    private List<IosHome.FeedItem> pending = new ArrayList<>();
    private int created;

    void start(List<IosHome.FeedItem> items, int upTo) {
        pending = items;
        created = 0;
        ensure(upTo);
    }

    void ensure(int upTo) {
        int end = Math.min(upTo, pending.size());
        while (created < end) { add(pending.get(created), created); created++; }
    }

    void fill(FrameLayout card, IosHome.FeedItem item, boolean breathe) {
        if (card.getChildCount() > 0) return;
        int accent = h.dock.isEmpty() ? h.INK_2 : h.dock.get(h.selected).logo.accent;
        GradientDrawable bg = (GradientDrawable) card.getBackground();
        ImageView pic = new ImageView(h);
        pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(pic, new FrameLayout.LayoutParams(-1, -1));
        if (item.image != null) {
            pic.setImageBitmap(item.image);
            item.onImage = () -> { pic.setImageBitmap(item.image); toneFor(card, item); };
        } else {
            // Пока обложка грузится, карточка мягко дышит.
            final ObjectAnimator pulse = breathe ? ObjectAnimator.ofFloat(card, "alpha", 1f, 0.55f) : null;
            if (pulse != null) {
                pulse.setDuration(h.SLOW * 2);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.start();
                card.setTag(R.id.pulse, pulse);
            }
            item.onImage = () -> {
                if (pulse != null) { pulse.cancel(); card.setAlpha(1f); }
                pic.setAlpha(0f);
                pic.setImageBitmap(item.image);
                pic.animate().alpha(1f).setDuration(h.NORMAL).setInterpolator(h.ease).start();
                toneFor(card, item);
            };
            item.onFail = () -> {
                if (pulse != null) pulse.cancel();
                card.setAlpha(1f);
                bg.setColor(IosPalette.tint(accent));
                TextView t = h.text(item.poster ? IosPalette.HEADLINE : IosPalette.TITLE, h.semibold, h.INK);
                t.setText(item.title);
                t.setGravity(Gravity.CENTER);
                t.setMaxLines(4);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(h.px(IosPalette.S4), h.px(IosPalette.S4), h.px(IosPalette.S4), h.px(IosPalette.S4));
                card.addView(t, new FrameLayout.LayoutParams(-1, -1));
            };
        }
        if (item.duration != null && !item.duration.isEmpty()) {
            TextView d = h.text(IosPalette.FOOTNOTE, h.semibold, h.INK);
            d.setText(item.duration);
            d.setPadding(h.px(12), h.px(4), h.px(12), h.px(4));
            GradientDrawable g = new GradientDrawable();
            g.setColor(IosPalette.SCRIM_STRONG);
            g.setCornerRadius(h.px(IosPalette.R_BADGE));
            d.setBackground(g);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            lp.rightMargin = lp.bottomMargin = h.px(IosPalette.S2);
            card.addView(d, lp);
        }
        if (item.fresh) {
            TextView n = h.text(IosPalette.FOOTNOTE, h.semibold, IosPalette.ON_LIGHT);
            n.setText("новое");
            n.setPadding(h.px(12), h.px(4), h.px(12), h.px(4));
            GradientDrawable g = new GradientDrawable();
            g.setColor(h.INK);
            g.setCornerRadius(h.px(IosPalette.R_BADGE));
            n.setBackground(g);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = lp.topMargin = h.px(IosPalette.S2);
            card.addView(n, lp);
        }
    }

    /** Обложка выбранной карточки догрузилась: фон перекрашивается в её цвет. */
    void toneFor(View card, IosHome.FeedItem item) {
        if (card != h.focused || h.zone != h.CARDS) return;
        int t = IosArt.coverTone(item.image);
        if (t != 0) h.washColor(t);
    }

    /** Убирает содержимое дальней карточки: обложку отдаём сборщику, рамка и подписи остаются. */
    void empty(FrameLayout card, IosHome.FeedItem item) {
        if (card.getChildCount() == 0) return;
        Object pulse = card.getTag(R.id.pulse);
        if (pulse instanceof ObjectAnimator) ((ObjectAnimator) pulse).cancel();
        card.setTag(R.id.pulse, null);
        card.removeAllViews();
        item.onImage = null;
        item.onFail = null;
        if (item.urls != null && item.urls.length > 0) item.image = null;
    }

    /** Обложки и содержимое живут только у карточек рядом с выбранной. */
    void lazy(int at) {
        ensure(at + NEAR_CARDS + 3);
        List<IosHome.FeedItem> list = new ArrayList<>();
        for (int k = 0; k < h.cardViews.size(); k++) {
            View c = h.cardViews.get(k);
            if (!(c.getTag() instanceof IosHome.FeedItem)) continue;
            IosHome.FeedItem it = (IosHome.FeedItem) c.getTag();
            list.add(it);
            int far = Math.abs(k - at);
            if (far <= NEAR_CARDS) fill((FrameLayout) c, it, false);
            else if (far > NEAR_CARDS + 2) empty((FrameLayout) c, it);
        }
        h.feed.picturesAround(list, at);
    }

    /** Цвет обложки для подсветки фона: насыщенные точки весят больше, итог приглушён на треть,
     *  чтобы фон жил вместе с карточкой, но не спорил с ней. 0 — обложки ещё нет. */

    /** Тонкая, почти невидимая линия по краю выбранной карточки, как у окон macOS: отделяет её от фона без «рамки». */
    private Drawable hairline() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(h.px(IosPalette.R_CARD));
        d.setStroke(Math.max(1, h.px(1.5f)), IosPalette.HAIRLINE);
        return d;
    }

    View lit;
    private Drawable line;

    /** Пока лента в фокусе, остальные карточки притушены, а уехавшие влево прячутся за доком. */
    void dim(View on, int position) {
        if (line == null) line = hairline();
        if (on != null && lit != null && h.cardViews.contains(lit)) {
            // Внутри ленты меняются только две карточки: прежняя гаснет, новая загорается.
            paint(lit, false, true);
            paint(on, true, true);
        } else for (View c : h.cardViews) paint(c, c == on, on != null);
        lit = on;
    }

    void paint(View c, boolean lit, boolean inFeed) {
        c.animate().alpha(!inFeed || lit ? 1f : 0.5f).setDuration(h.FAST).start();
        if (c instanceof FrameLayout) c.setForeground(lit ? line : null);
        TextView[] l = h.cardLabels.get(c);
        if (l != null) {
            l[0].animate().alpha(lit ? 1f : inFeed ? h.LABEL_DIM : h.LABEL_REST).setDuration(h.FAST).start();
            l[1].animate().alpha(lit ? 1f : 0f).setDuration(h.FAST).start();
        }
    }
}
