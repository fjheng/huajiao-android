package com.huajiao.calendar;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 闹钟到点：弹一条「还有 N 分钟上课」的通知 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;

        String name = safe(intent.getStringExtra("name"), "上课提醒");
        String time = safe(intent.getStringExtra("time"), "");
        String location = safe(intent.getStringExtra("location"), "");

        ReminderScheduler.ensureChannel(ctx);

        String title = name;
        StringBuilder text = new StringBuilder();
        if (!time.isEmpty()) text.append(time).append(" 上课");
        if (!location.isEmpty()) text.append(text.length() > 0 ? " · " : "").append(location);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("from", "reminder");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(ctx, ReminderScheduler.CHANNEL_ID);
        } else {
            builder = new Notification.Builder(ctx);
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        builder.setSmallIcon(R.drawable.ic_stat_class)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        try {
            // 用一个递增 id，多条提醒不会互相覆盖
            nm.notify((int) (System.currentTimeMillis() % 1000000), builder.build());
        } catch (SecurityException e) {
            // 没有通知权限：什么都不做，别让闹钟把应用崩掉
        }
    }

    private static String safe(String v, String fallback) {
        return v == null ? fallback : v;
    }
}
