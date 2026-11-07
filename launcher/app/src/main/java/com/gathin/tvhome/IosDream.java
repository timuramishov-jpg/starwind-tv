package com.gathin.tvhome;

import android.service.dreams.DreamService;

/** Заставка телевизора: какая — выбирается в шестерёнке («Заставка»), по умолчанию по очереди. */
public class IosDream extends DreamService {
    private IosSaver saver;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        saver = new IosSaver(this, IosSaver.chosen(this));
        setContentView(saver);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        saver.start();
    }

    @Override public void onDreamingStopped() {
        saver.stop();
        super.onDreamingStopped();
    }
}
