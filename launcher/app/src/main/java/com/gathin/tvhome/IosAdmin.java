package com.gathin.tvhome;

import android.app.admin.DeviceAdminReceiver;

/** Пустой приёмник администратора: нужен, чтобы таймер сна мог погасить экран (lockNow). */
public class IosAdmin extends DeviceAdminReceiver { }
