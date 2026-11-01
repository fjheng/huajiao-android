package com.huajiao.calendar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机/更新后恢复。
 * AlarmManager 里的闹钟关机就没了，所以把计划存了一份在本地，开机重排。
 * ACTION_MY_PACKAGE_REPLACED 是覆盖安装（比如升级到新版本）后重新排，免得升级完提醒全丢。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        ReminderScheduler.restore(ctx);
        TodayWidgetProvider.updateAll(ctx);
    }
}
