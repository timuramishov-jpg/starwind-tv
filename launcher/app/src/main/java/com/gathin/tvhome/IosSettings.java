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

    /** Настройки лаунчера. Системные экраны прошивки — только в отдельном пункте «Системные настройки». */
    private void settingsItems(List<String> labels, List<Runnable> actions) {
        labels.add("Wi-Fi и интернет");
        actions.add(this::showWifi);
        long sleepAt = IosSleep.at(h), left = sleepAt == 0 ? 0 : (sleepAt - System.currentTimeMillis() + 59_999) / 60000;
        labels.add(sleepAt == 0 ? "Таймер сна: выключен" : "Таймер сна: через " + left + " мин");
        actions.add(keep(() -> {
            // Каждое нажатие — следующий шаг: 30, 60, 90 минут, выключить.
            long step = left == 0 ? 30 : left <= 30 ? 60 : left <= 60 ? 90 : 0;
            IosSleep.schedule(h, step);
            refreshSettings();
        }));
        SharedPreferences saverPrefs = h.getSharedPreferences("ios_saver", 0);
        int saverPick = saverPrefs.getInt("style", -1);
        labels.add("Заставка: " + (saverPick < 0 ? "по очереди" : IosSaver.NAMES[saverPick]));
        actions.add(keep(() -> {
            int next = saverPick + 1 >= IosSaver.NAMES.length ? -1 : saverPick + 1;
            saverPrefs.edit().putInt("style", next).apply();
            refreshSettings();
        }));
        int idle = saverPrefs.getInt("idle", 5);
        labels.add("Заставка при простое: " + (idle == 0 ? "никогда" : "через " + idle + " мин"));
        actions.add(keep(() -> {
            int next = idle == 2 ? 5 : idle == 5 ? 10 : idle == 10 ? 15 : idle == 15 ? 0 : 2;
            saverPrefs.edit().putInt("idle", next).apply();
            refreshSettings();
        }));
        labels.add("Показать заставку");
        actions.add(() -> h.startActivity(new Intent(h, IosSaverActivity.class)
                .putExtra("style", saverPick < 0 ? 0 : saverPick)));
        boolean ads = IosTools.adsBlocked(h), fast = IosTools.fastAnim(h);
        labels.add("Блокировка рекламы: " + (ads ? "включена" : "выключена"));
        actions.add(keep(() -> tweak("dns", !ads)));
        labels.add("Быстрые анимации: " + (fast ? "включены" : "выключены"));
        actions.add(keep(() -> tweak("anim", !fast)));
        boolean ownKeys = String.valueOf(Settings.Secure.getString(h.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD)).startsWith(h.getPackageName());
        labels.add("Клавиатура: " + (ownKeys ? "Starwind" : "Яндекс"));
        actions.add(keep(() -> new Thread(() -> {
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
        settingsItems(labels, actions);
        h.showMenu("Настройки", labels, actions);
        h.menuKind = "settings";
    }

    /** Переключатель сработал: подписи меняются на месте, меню не переоткрывается. */
    private void refreshSettings() {
        if (!h.overlayOpen() || !"settings".equals(h.menuKind)) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        settingsItems(labels, actions);
        if (labels.size() != h.menuViews.size()) { int at = h.menuIndex; show(); h.menuIndex = Math.min(at, h.menuViews.size() - 1); h.paintMenu(); return; }
        for (int i = 0; i < labels.size(); i++) h.menuViews.get(i).setText(labels.get(i));
        h.menuActions.clear();
        h.menuActions.addAll(actions);
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
        h.showMenu("Системные настройки", labels, actions);
    }

    /** Wi-Fi в стиле лаунчера: сеть, сигнал, скорость, адрес, VPN. Сменить сеть — в системных настройках. */
    void showWifi() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Проверяю сеть…");
        actions.add(h.INFO);
        h.showMenu("Wi-Fi и интернет", labels, actions);
        h.menuKind = "wifi";
        new Thread(() -> {
            List<String> info = h.tools.wifi();
            h.handler.post(() -> {
                if (!h.overlayOpen() || !"wifi".equals(h.menuKind)) return;
                List<String> l = new ArrayList<>(info);
                List<Runnable> a = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) a.add(h.INFO);
                l.add("VPN: " + (h.vpnOn() ? "включён" : "выключен"));
                a.add(h.INFO);
                l.add("Сменить сеть");
                a.add(() -> h.open(SystemSettings.intent(h, Settings.ACTION_WIFI_SETTINGS)));
                h.showMenu("Wi-Fi и интернет", l, a, false);
                h.menuKind = "wifi";
                h.menuIndex = l.size() - 1;
                h.paintMenu();
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
