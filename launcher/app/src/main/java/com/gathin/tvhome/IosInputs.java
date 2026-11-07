package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Входы телевизора: меню источников и подсказка «Подключено: HDMI. ОК — перейти». */
final class IosInputs {
    private final IosHome h;
    private TvInputManager.TvInputCallback watch;
    private String pending;
    private long until;

    IosInputs(IosHome h) { this.h = h; }

    private TvInputManager manager() { return (TvInputManager) h.getSystemService(Context.TV_INPUT_SERVICE); }

    /** Подключили приставку или ноутбук по HDMI — предлагаем перейти на этот вход (15 секунд, ОК). */
    void watch() {
        TvInputManager tim = manager();
        if (tim == null) return;
        final Map<String, Integer> was = new HashMap<>();
        try { for (TvInputInfo info : tim.getTvInputList()) if (info.isPassthroughInput()) was.put(info.getId(), tim.getInputState(info.getId())); }
        catch (Exception ignored) { }
        watch = new TvInputManager.TvInputCallback() {
            @Override public void onInputStateChanged(String id, int state) {
                Integer before = was.put(id, state);
                if (state != TvInputManager.INPUT_STATE_CONNECTED || (before != null && before == TvInputManager.INPUT_STATE_CONNECTED)) return;
                if (!h.hasWindowFocus()) return;
                String name = id;
                try { name = String.valueOf(tim.getTvInputInfo(id).loadLabel(h)); } catch (Exception ignored) { }
                pending = id;
                until = SystemClock.elapsedRealtime() + 15000;
                h.note("Подключено: " + name + ". ОК — перейти");
            }
        };
        tim.registerCallback(watch, h.handler);
    }

    void unwatch() {
        TvInputManager tim = manager();
        if (tim != null && watch != null) tim.unregisterCallback(watch);
    }

    /** ОК сразу после подсказки — переходим на подключённый вход. */
    boolean openPending() {
        if (pending == null || SystemClock.elapsedRealtime() >= until) return false;
        String id = pending;
        pending = null;
        h.open(intent(id));
        return true;
    }

    private static Intent intent(String id) {
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id).buildUpon().scheme("live-tv").build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    void show() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        TvInputManager tim = manager();
        if (tim != null) {
            List<TvInputInfo> list = new ArrayList<>(tim.getTvInputList());
            Collections.sort(list, (a, b) -> String.valueOf(a.loadLabel(h)).compareToIgnoreCase(String.valueOf(b.loadLabel(h))));
            for (final TvInputInfo info : list) {
                String label = String.valueOf(info.loadLabel(h));
                if (!info.isPassthroughInput() || info.isHidden(h) || label.startsWith("Playback")) continue;
                labels.add(label);
                actions.add(() -> h.open(intent(info.getId())));
            }
        }
        final Intent live = h.getPackageManager().getLeanbackLaunchIntentForPackage("com.yandex.tv.live");
        if (live != null) {
            labels.add("Телеканалы");
            actions.add(() -> h.open(live));
        }
        if (labels.isEmpty()) {
            h.note("Источники не найдены");
            return;
        }
        h.showMenu("Источники", labels, actions);
    }
}
