package com.huajiao.calendar;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 桌面小组件：今日课程。
 *
 * 数据来自网页层（课表拉取成功后通过 AndroidTool.pushWidget 推过来），
 * 存在 SharedPreferences 里一份 JSON。小组件本身不联网、不解析 HTML，
 * 只做「把 JSON 画成 RemoteViews」这一件事，所以刷新很快也不会出错。
 *
 * 跨天后旧数据会故意不显示：宁可提示「打开应用同步」，
 * 也不能把昨天的课当成今天的显示在桌面上。
 */
public class TodayWidgetProvider extends AppWidgetProvider {

    private static final String PREF = "huajiao_widget";
    private static final String KEY = "payload";
    private static final int MAX_ROWS = 6;

    public static void savePayload(Context ctx, String json) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, json == null ? "" : json)
                .apply();
    }

    public static int count(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TodayWidgetProvider.class));
        return ids == null ? 0 : ids.length;
    }

    public static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TodayWidgetProvider.class));
        if (ids == null) return;
        for (int id : ids) updateOne(ctx, mgr, id);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] appWidgetIds) {
        if (appWidgetIds == null) return;
        for (int id : appWidgetIds) updateOne(ctx, mgr, id);
    }

    @Override
    public void onEnabled(Context ctx) {
        updateAll(ctx);
    }

    private static void updateOne(Context ctx, AppWidgetManager mgr, int widgetId) {
        String raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");

        boolean dark = false;
        String label = "";
        String weekday = "";
        String date = "";
        JSONArray items = new JSONArray();

        try {
            if (raw != null && !raw.isEmpty()) {
                JSONObject o = new JSONObject(raw);
                dark = o.optBoolean("dark", false);
                label = o.optString("label", "");
                weekday = o.optString("weekday", "");
                date = o.optString("date", "");
                JSONArray arr = o.optJSONArray("items");
                if (arr != null) items = arr;
            }
        } catch (Exception ignored) {
            // 数据坏了就当没有，显示空态，不崩
        }

        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_today);

        rv.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                dark ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light);

        int textColor = Color.parseColor(dark ? "#E9EBEF" : "#16181D");
        int subColor = Color.parseColor(dark ? "#9AA0AA" : "#6B7280");
        int faintColor = Color.parseColor(dark ? "#6D747F" : "#A0A6B0");

        rv.setTextColor(R.id.widget_title, textColor);
        rv.setTextColor(R.id.widget_date, faintColor);
        rv.setTextColor(R.id.widget_empty, subColor);

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        boolean stale = !today.equals(date);
        boolean empty = stale || items.length() == 0;

        rv.setTextViewText(R.id.widget_title, "今日课程");
        if (stale) {
            rv.setTextViewText(R.id.widget_date, "");
            rv.setTextViewText(R.id.widget_empty, "打开花椒日历，同步今天的课");
        } else {
            rv.setTextViewText(R.id.widget_date, (label + " " + weekday).trim());
            rv.setTextViewText(R.id.widget_empty, "今天没有课");
        }

        rv.removeAllViews(R.id.widget_rows);

        if (empty) {
            rv.setViewVisibility(R.id.widget_rows, View.GONE);
            rv.setViewVisibility(R.id.widget_empty, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.widget_rows, View.VISIBLE);
            rv.setViewVisibility(R.id.widget_empty, View.GONE);

            int n = Math.min(items.length(), MAX_ROWS);
            for (int i = 0; i < n; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;

                RemoteViews row = new RemoteViews(ctx.getPackageName(), R.layout.widget_row);
                row.setTextViewText(R.id.row_time, it.optString("time", ""));
                row.setTextViewText(R.id.row_name, it.optString("name", ""));

                String loc = it.optString("location", "");
                row.setTextViewText(R.id.row_loc, loc);
                row.setViewVisibility(R.id.row_loc, loc.isEmpty() ? View.GONE : View.VISIBLE);

                row.setTextColor(R.id.row_time, subColor);
                row.setTextColor(R.id.row_name, textColor);
                row.setTextColor(R.id.row_loc, faintColor);

                int barColor;
                try {
                    barColor = Color.parseColor(it.optString("color", "#2F6BF5"));
                } catch (Exception e) {
                    barColor = Color.parseColor("#2F6BF5");
                }
                row.setInt(R.id.row_bar, "setBackgroundColor", barColor);

                rv.addView(R.id.widget_rows, row);
            }

            if (items.length() > MAX_ROWS) {
                RemoteViews more = new RemoteViews(ctx.getPackageName(), R.layout.widget_row);
                more.setTextViewText(R.id.row_time, "");
                more.setTextViewText(R.id.row_name, "还有 " + (items.length() - MAX_ROWS) + " 节…");
                more.setTextViewText(R.id.row_loc, "");
                more.setViewVisibility(R.id.row_loc, View.GONE);
                more.setViewVisibility(R.id.row_bar, View.INVISIBLE);
                more.setTextColor(R.id.row_name, faintColor);
                rv.addView(R.id.widget_rows, more);
            }
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("from", "widget");
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_root, pi);

        mgr.updateAppWidget(widgetId, rv);
    }
}
