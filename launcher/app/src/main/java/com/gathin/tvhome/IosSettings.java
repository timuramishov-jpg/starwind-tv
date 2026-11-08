package com.gathin.tvhome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Настройки лаунчера в боковой панели: пункты, переключатели на месте, Wi-Fi, системные экраны. */
final class IosSettings {
    private final IosHome h;

    IosSettings(IosHome h) { this.h = h; }

    private Runnable keep(Runnable r) { return new IosHome.Keep(r); }

    /** Стрелка влево для строк-выборов, по порядку пунктов (null — у строки нет выбора). */
    private final List<Runnable> prevs = new ArrayList<>();

    /** Значения таймера сна, заставки при простое: по стрелкам ← → в обе стороны. */
    static final int[] SLEEP = {0, 15, 30, 60, 90, 120}, IDLE = {0, 2, 5, 10, 15, 30};

    static int step(int[] values, int now, int by) {
        int at = 0;
        for (int i = 0; i < values.length; i++) if (values[i] <= now) at = i;
        return values[Math.max(0, Math.min(values.length - 1, at + by))];
    }

    private void add(List<String> labels, List<Runnable> actions, String label, Runnable act, Runnable prev) {
        while (prevs.size() < labels.size()) prevs.add(null);
        labels.add(label);
        actions.add(act);
        prevs.add(prev);
    }

    /** Настройки лаунчера. Системные экраны прошивки — только в отдельном пункте «Системные настройки». */
    private void settingsItems(List<String> labels, List<Runnable> actions) {
        labels.add("Wi-Fi и интернет");
        actions.add(this::showWifi);
        long sleepAt = IosSleep.at(h), left = sleepAt == 0 ? 0 : (sleepAt - System.currentTimeMillis() + 59_999) / 60000;
        add(labels, actions, IosMenu.choice("Таймер сна", left == 0 ? "выключен" : "через " + left + " мин"),
                keep(() -> { IosSleep.schedule(h, step(SLEEP, (int) left, 1)); refreshSettings(); }),
                keep(() -> { IosSleep.schedule(h, left > 0 && left < SLEEP[1] ? 0 : step(SLEEP, (int) left, -1)); refreshSettings(); }));
        SharedPreferences saverPrefs = h.getSharedPreferences("ios_saver", 0);
        int saverPick = saverPrefs.getInt("style", -1);
        add(labels, actions, IosMenu.choice("Заставка", saverPick < 0 ? "по очереди" : IosSaver.NAMES[saverPick]),
                keep(() -> { saverPrefs.edit().putInt("style", saverPick + 1 >= IosSaver.NAMES.length ? -1 : saverPick + 1).apply(); refreshSettings(); }),
                keep(() -> { saverPrefs.edit().putInt("style", saverPick < 0 ? IosSaver.NAMES.length - 1 : saverPick - 1).apply(); refreshSettings(); }));
        int idle = saverPrefs.getInt("idle", 5);
        add(labels, actions, IosMenu.choice("Заставка при простое", idle == 0 ? "никогда" : "через " + idle + " мин"),
                keep(() -> { saverPrefs.edit().putInt("idle", step(IDLE, idle, 1)).apply(); refreshSettings(); }),
                keep(() -> { saverPrefs.edit().putInt("idle", step(IDLE, idle, -1)).apply(); refreshSettings(); }));
        labels.add("Показать заставку");
        actions.add(() -> h.startActivity(new Intent(h, IosSaverActivity.class)
                .putExtra("style", saverPick < 0 ? 0 : saverPick)));
        boolean ads = IosTools.adsBlocked(h), fast = IosTools.fastAnim(h);
        labels.add(IosMenu.toggle("Блокировка рекламы", ads));
        actions.add(keep(() -> tweak("dns", !ads)));
        labels.add(IosMenu.toggle("Быстрые анимации", fast));
        actions.add(keep(() -> tweak("anim", !fast)));
        boolean ownKeys = String.valueOf(Settings.Secure.getString(h.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD)).startsWith(h.getPackageName());
        labels.add(IosMenu.toggle("Своя клавиатура вместо Яндекса", ownKeys));
        actions.add(keep(() -> new Thread(() -> {
            // Выбор запоминаем: обновление лаунчера сбрасывает клавиатуру на Яндекс, лаунчер вернёт выбранную.
            h.prefs.edit().putBoolean("own_keyboard", !ownKeys).apply();
            try {
                IosAdb.get(h).shell("ime enable " + h.getPackageName() + "/.IosKeyboard; ime set "
                        + (ownKeys ? "ru.yandex.androidkeyboard.tv/ru.yandex.androidkeyboard.TvYandexKeyboardService" : h.getPackageName() + "/.IosKeyboard"));
            } catch (Exception e) { Log.w("IosHome", "ime: " + e); }
            h.handler.post(this::refreshSettings);
        }, "ime").start()));
        labels.add("Очистить кэш приложений");
        actions.add(keep(() -> {
            h.note("Чищу кэш…");
            new Thread(() -> { boolean ok = h.tools.trim(); h.handler.post(() -> h.note(ok ? "Кэш очищен" : "Нет доступа к отладке")); }, "trim").start();
        }));
        labels.add("Пульт в телефоне");
        actions.add(h::showRemoteCode);
        labels.add("Обновить лаунчер, версия " + IosUpdate.installed(h));
        actions.add(keep(() -> {
            h.note("Проверяю обновление…");
            IosUpdate.check(h, m -> h.note(m));
        }));
        // Аккаунт Яндекса всего телевизора: за ним следуют Кинопоиск и другие приложения Яндекса.
        labels.add("Аккаунт Яндекса");
        actions.add(() -> h.open(new Intent("com.yandex.tv.passport.MANAGE_ACCOUNTS").setPackage("com.yandex.tv.services")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        labels.add("Системные настройки");
        actions.add(this::showSystemSettings);
        for (final String pkg : h.stored("hidden")) {
            String name = pkg;
            try {
                PackageManager pm = h.getPackageManager();
                name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException ignored) { }
            labels.add("Вернуть " + name);
            actions.add(() -> {
                List<String> hidden = h.stored("hidden");
                hidden.remove(pkg);
                h.store("hidden", hidden);
                h.reload();
            });
        }
    }

    void show() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        prevs.clear();
        settingsItems(labels, actions);
        h.menu.show("Настройки", labels, actions);
        h.menu.prevs.addAll(prevs);
        h.menu.kind = "settings";
    }

    /** Переключатель сработал: подписи меняются на месте, меню не переоткрывается. */
    private void refreshSettings() {
        IosRemote.prefs(h);
        if (!h.overlayOpen() || !"settings".equals(h.menu.kind)) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        prevs.clear();
        settingsItems(labels, actions);
        if (labels.size() != h.menu.views.size()) { int at = h.menu.index; show(); h.menu.index = Math.min(at, h.menu.views.size() - 1); h.menu.paint(); return; }
        h.menu.acts.clear();
        h.menu.acts.addAll(actions);
        h.menu.prevs.clear();
        h.menu.prevs.addAll(prevs);
        for (int i = 0; i < labels.size(); i++) h.menu.setLabel(i, labels.get(i));
    }

    /** Экраны прошивки собраны в одном месте и так и названы: всё остальное — в стиле лаунчера. */
    private void showSystemSettings() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String[][] rows = {{"Сети Wi-Fi и пароль", Settings.ACTION_WIFI_SETTINGS}, {"Bluetooth и пульты", Settings.ACTION_BLUETOOTH_SETTINGS},
                {"Звук", Settings.ACTION_SOUND_SETTINGS}, {"Изображение", Settings.ACTION_DISPLAY_SETTINGS},
                {"Приложения", Settings.ACTION_APPLICATION_SETTINGS}, {"Все настройки телевизора", Settings.ACTION_SETTINGS}};
        for (String[] row : rows) {
            labels.add(row[0]);
            actions.add(() -> h.open(SystemSettings.intent(h, row[1])));
        }
        h.menu.show("Системные настройки", labels, actions);
    }

    /** Wi-Fi в стиле лаунчера: сеть, сигнал, скорость, адрес, VPN. Сменить сеть — в системных настройках. */
    void showWifi() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Проверяю сеть…");
        actions.add(IosMenu.INFO);
        h.menu.show("Wi-Fi и интернет", labels, actions);
        h.menu.kind = "wifi";
        new Thread(() -> {
            List<String> info = h.tools.wifi();
            h.handler.post(() -> {
                if (!h.overlayOpen() || !"wifi".equals(h.menu.kind)) return;
                List<String> l = new ArrayList<>(info);
                List<Runnable> a = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) a.add(IosMenu.INFO);
                l.add("VPN: " + (h.vpnOn() ? "включён" : "выключен"));
                a.add(IosMenu.INFO);
                l.add("Сменить сеть");
                a.add(() -> h.open(SystemSettings.intent(h, Settings.ACTION_WIFI_SETTINGS)));
                h.menu.show("Wi-Fi и интернет", l, a, false);
                h.menu.kind = "wifi";
                h.menu.index = l.size() - 1;
                h.menu.paint();
            });
        }, "wifi").start();
    }

    private void tweak(String what, boolean on) {
        new Thread(() -> {
            String r;
            try { r = h.tools.tweak(what, on ? "1" : "0"); } catch (Exception e) { r = IosTools.say("Не получилось"); }
            String msg = r.replaceAll("^\\{\"say\":\"|\"\\}$", "");
            h.handler.post(() -> { h.note(msg); refreshSettings(); });
        }, "tweak").start();
    }
}
