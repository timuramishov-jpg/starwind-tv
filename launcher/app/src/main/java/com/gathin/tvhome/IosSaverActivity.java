package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/** Посмотреть заставку прямо сейчас: открывается из шестерёнки, любая кнопка закрывает. */
public class IosSaverActivity extends Activity {
    private IosSaver saver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        saver = new IosSaver(this, getIntent().getIntExtra("style", IosSaver.chosen(this)));
        setContentView(saver);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() { super.onResume(); saver.start(); }

    @Override protected void onPause() { saver.stop(); super.onPause(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
