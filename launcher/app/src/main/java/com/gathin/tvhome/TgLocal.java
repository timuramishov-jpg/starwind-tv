package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Лаунчер просит обновить ленту Telegram для дока. Только изнутри приложения (не экспортирован), процесс :tg. */
public final class TgLocal extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { TgCore.get(c).feed(); }
}
