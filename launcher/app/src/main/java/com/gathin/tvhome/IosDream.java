package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/** Заставка телевизора: какая — выбирается в шестерёнке («Заставка»), по умолчанию по очереди. */
public class IosDream extends DreamService {
    private IosSaver saver;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Интерактивная: влево-вправо листают заставки, остальные кнопки будят телевизор.
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        saver = new IosSaver(this, IosSaver.chosen(this));
        setContentView(saver);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        saver.start();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int step = IosSaver.stepFor(e.getKeyCode());
        if (step != 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && saver != null) IosSaver.remember(this, saver, step);
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override public void onDreamingStopped() {
        saver.stop();
        super.onDreamingStopped();
    }
}
