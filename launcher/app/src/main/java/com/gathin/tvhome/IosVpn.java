package com.gathin.tvhome;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Экран VPN: три карточки (тумблер, связь, сервер), обновляются на месте; замеры отклика, скорости и страны. */
final class IosVpn {
    private final IosHome h;
    private String shown = "", stats = "", speed = "";

    IosVpn(IosHome h) { this.h = h; }

    void vpnCommand(boolean connect) {
        h.prefs.edit().putBoolean("vpn_user_off", !connect).apply();
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("happ://" + (connect ? "connect" : "disconnect") + "_without_ui"))
                .setClassName(IosHome.HAPP, IosHome.HAPP + ".feature.deeplink.DeeplinkInterceptorActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        h.open(i);
        for (long delay : new long[]{700, 1800, 3500, 6000}) h.handler.postDelayed(this::refreshVpn, delay);
    }

    void refreshVpn() {
        if (h.dock.isEmpty() || !(IosHome.HAPP.equals(h.dock.get(h.selected).pkg) || IosHome.SOTA.equals(h.dock.get(h.selected).pkg))) return;
        String state = h.vpnOn() + "|" + h.prefs.getString("vpn_place", "") + "|" + stats + "|" + speed + "|" + h.dock.get(h.selected).pkg;
        if (state.equals(shown)) return;
        // Карточки VPN обновляются на месте: ничего не пересобирается и не прыгает.
        IosHome.FeedItem[] fresh = vpnItems(h.dock.get(h.selected));
        if (h.cardViews.size() != fresh.length) { h.showSelected(); return; }
        for (int i = 0; i < fresh.length; i++) {
            View card = h.cardViews.get(i);
            if (!(card.getTag() instanceof IosHome.FeedItem)) { h.showSelected(); return; }
            IosHome.FeedItem old = (IosHome.FeedItem) card.getTag();
            old.title = fresh[i].title;
            old.subtitle = fresh[i].subtitle;
            old.image = fresh[i].image;
            old.action = fresh[i].action;
            TextView[] l = h.cardLabels.get(card);
            if (l != null) { l[0].setText(old.title); l[1].setText(old.subtitle); }
            if (card instanceof FrameLayout && ((FrameLayout) card).getChildCount() > 0 && ((FrameLayout) card).getChildAt(0) instanceof ImageView)
                ((ImageView) ((FrameLayout) card).getChildAt(0)).setImageBitmap(old.image);
        }
    }

    /** Экран VPN: три узкие карточки, как афиши. Тумблер, связь (страна и отклик), смена сервера. */
    void showVpn(IosHome.App a) {
        IosHome.FeedItem[] all = vpnItems(a);
        for (int i = 0; i < all.length; i++) h.cards.add(all[i], i);
        if (h.vpnOn()) { checkExit(); checkSpeed(); checkDownload(false); }
    }

    private IosHome.FeedItem[] vpnItems(IosHome.App a) {
        boolean on = h.vpnOn(), own = IosHome.HAPP.equals(a.pkg);
        int accent = a.logo.accent;
        shown = on + "|" + h.prefs.getString("vpn_place", "") + "|" + stats + "|" + speed + "|" + a.pkg;
        String iso = h.prefs.getString("vpn_iso", ""), where = h.prefs.getString("vpn_place", "");
        String[] parts = stats.split("\\|");
        boolean measured = on && parts.length == 3;
        int grade = measured ? Integer.parseInt(parts[2]) : -1;

        IosHome.FeedItem toggle = new IosHome.FeedItem();
        toggle.poster = true;
        toggle.title = on ? "VPN включён" : "VPN выключен";
        toggle.subtitle = own ? "Happ" : a.label;
        toggle.image = h.pics.switchArt(on, accent);
        toggle.action = () -> { if (own) vpnCommand(!on); else h.launch(a); };

        IosHome.FeedItem link = new IosHome.FeedItem();
        link.poster = true;
        link.title = !on ? "Нет подключения" : where.isEmpty() ? "Определяю страну" : where;
        link.subtitle = !on ? "Сначала включи VPN" : measured ? parts[1] + (speed.isEmpty() ? "" : " · " + speed) : "Измеряю";
        link.image = h.pics.linkArt(on && !iso.isEmpty() ? flagOf(iso) : "", grade, measured ? parts[0] : on ? "…" : "—", accent, vpnHistory());
        link.action = () -> {
            h.prefs.edit().remove("vpn_place").remove("vpn_iso").apply();
            h.feed.resetProbe();
            stats = "";
            refreshVpn();
            if (h.vpnOn()) { checkExit(); checkSpeed(); checkDownload(true); }
        };

        IosHome.FeedItem server = new IosHome.FeedItem();
        server.poster = true;
        server.title = "Сменить сервер";
        server.subtitle = own ? "Откроется Happ" : "Откроется приложение";
        server.image = h.pics.serverArt(accent);
        server.action = () -> h.launch(a);
        return new IosHome.FeedItem[]{toggle, link, server};
    }

    /** История отклика VPN за час: «время:мс» через запятую в настройках. */
    private List<long[]> vpnHistory() {
        List<long[]> out = new ArrayList<>();
        long from = System.currentTimeMillis() - 3600_000L;
        for (String part : h.prefs.getString("vpn_hist", "").split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            try {
                long t = Long.parseLong(kv[0]), ms = Long.parseLong(kv[1]);
                if (t >= from && ms > 0) out.add(new long[]{t, ms});
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private void rememberPing(int ms) {
        if (ms <= 0) return;
        StringBuilder b = new StringBuilder();
        for (long[] p : vpnHistory()) b.append(p[0]).append(':').append(p[1]).append(',');
        b.append(System.currentTimeMillis()).append(':').append(ms);
        h.prefs.edit().putString("vpn_hist", b.toString()).apply();
    }

    /** Отклик через VPN: пять запросов подряд, среднее, разброс и потери. */
    void checkSpeed() {
        h.feed.probe((avg, spread, lost) -> {
            int grade = lost > 1 || avg < 0 ? 0 : spread > 120 || avg > 600 ? 1 : spread > 50 || avg > 250 ? 2 : 3;
            String word = grade == 0 ? "Связь рвётся" : grade == 1 ? "Нестабильно" : grade == 2 ? "Стабильно" : "Отлично";
            stats = (avg < 0 ? "нет ответа" : avg + " мс") + "|" + word + "|" + grade;
            rememberPing(avg);
            refreshVpn();
        });
    }

    /** Скорость загрузки через VPN: 4 МБ с серверов Google (они идут через VPN), не чаще раза в 10 минут. */
    private void checkDownload(boolean force) {
        h.feed.download(force, mbit -> {
            speed = mbit <= 0 ? "" : String.format(new Locale("ru"), "%.0f Мбит/с", mbit);
            refreshVpn();
        });
    }

    private static String flagOf(String iso) {
        return new String(Character.toChars(0x1F1E6 + iso.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + iso.charAt(1) - 'A'));
    }

    /** Страну показывает адрес, с которого виден телевизор через VPN. */
    private void checkExit() {
        h.feed.exit((iso, city) -> {
            // Только страна: город у VPN-серверов часто неточный и не влезает в подпись.
            String place = new Locale("", iso).getDisplayCountry(new Locale("ru"));
            if (place.equals(h.prefs.getString("vpn_place", ""))) return;
            h.prefs.edit().putString("vpn_iso", iso).putString("vpn_place", place).apply();
            if (!h.dock.isEmpty() && (IosHome.HAPP.equals(h.dock.get(h.selected).pkg) || IosHome.SOTA.equals(h.dock.get(h.selected).pkg))) refreshVpn();
        });
    }
}
