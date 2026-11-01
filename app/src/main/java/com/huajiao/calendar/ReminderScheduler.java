package com.huajiao.calendar;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 上课提醒的排期与通知。
 *
 * 页面把「未来 14 天该提醒的时刻」一次性算好交过来，这里只负责：
 *   1. 把上一次排的闹钟全部撤销（避免换周后新旧叠加）；
 *   2. 逐个 setExactAndAllowWhileIdle；
 *   3. 把这份计划写进 SharedPreferences，重启后能原样恢复。
 *
 * 为什么不重复触发（repeat）：课程有单双周、断档周，规律不一致，
 * 逐条排虽然占点内存，但绝不会在没课的那周乱响。
 */
public final class ReminderScheduler {

    public static final String CHANNEL_ID = "class_remind";
    private static final String CHANNEL_NAME = "上课提醒";

    private static final String PREF = "huajiao_reminders";
    private static final String KEY_PLAN = "plan";
    private static final String KEY_COUNT = "count";

    /** 单次最多排这么多条，够覆盖两周的满课表 */
    private static final int MAX_ITEMS = 150;

    private static final String ACTION = "com.huajiao.calendar.REMIND";

    private ReminderScheduler() {}

    public static class Item {
        public long at;
        public String name = "";
        public String time = "";
        public String location = "";
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("上课前的提醒通知");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /**
     * 重新排全部提醒。
     * @return 实际排上的条数
     */
    public static int schedule(Context ctx, List<Item> items) {
        ensureChannel(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return 0;

        // 先清掉上一轮
        int prev = sp(ctx).getInt(KEY_COUNT, 0);
        for (int i = 0; i < prev; i++) {
            am.cancel(buildIntent(ctx, i, null));
        }

        long now = System.currentTimeMillis();
        JSONArray saved = new JSONArray();
        int n = 0;

        for (Item it : items) {
            if (it.at <= now + 5000L) continue;
            setAlarm(am, it.at, buildIntent(ctx, n, it));
            saved.put(toJson(it));
            n++;
            if (n >= MAX_ITEMS) break;
        }

        sp(ctx).edit().putInt(KEY_COUNT, n).putString(KEY_PLAN, saved.toString()).apply();
        return n;
    }

    public static void cancelAll(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        int prev = sp(ctx).getInt(KEY_COUNT, 0);
        if (am != null) {
            for (int i = 0; i < prev; i++) {
                am.cancel(buildIntent(ctx, i, null));
            }
        }
        sp(ctx).edit().putInt(KEY_COUNT, 0).putString(KEY_PLAN, "[]").apply();
    }

    /** 开机等场景：把上次保存的计划原样重排（过期的自动跳过） */
    public static int restore(Context ctx) {
        String raw = sp(ctx).getString(KEY_PLAN, "[]");
        List<Item> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.at = o.optLong("at");
                it.name = o.optString("name");
                it.time = o.optString("time");
                it.location = o.optString("location");
                if (it.at > 0) list.add(it);
            }
        } catch (Exception e) {
            return 0;
        }
        if (list.isEmpty()) return 0;
        return schedule(ctx, list);
    }

    private static JSONObject toJson(Item it) {
        JSONObject o = new JSONObject();
        try {
            o.put("at", it.at);
            o.put("name", it.name);
            o.put("time", it.time);
            o.put("location", it.location);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static PendingIntent buildIntent(Context ctx, int id, Item it) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.setAction(ACTION);
        if (it != null) {
            intent.putExtra("name", it.name);
            intent.putExtra("time", it.time);
            intent.putExtra("location", it.location);
            intent.putExtra("at", it.at);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, id, intent, flags);
    }

    private static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 没给「精确闹钟」权限：退化成不精确闹钟，可能晚几分钟但不会崩
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** 通知是否被允许（Android 13 起是运行时权限，之前看开关） */
    public static boolean notificationsEnabled(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33) {
            return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    public static boolean exactAlarmAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am == null || am.canScheduleExactAlarms();
    }
}
