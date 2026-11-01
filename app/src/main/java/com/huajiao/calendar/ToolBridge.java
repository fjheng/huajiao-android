package com.huajiao.calendar;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页层调用原生能力的唯一入口（window.AndroidTool）。
 *
 * 这些方法在 JavaBridge 线程被调用，凡是碰 UI 的都 runOnUiThread；
 * 返回值统一是 JSON 字符串，网页侧 lib/native.js 负责解析。
 */
public class ToolBridge {

    private final MainActivity activity;

    ToolBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** {notif, exact, sdk, widget} */
    @JavascriptInterface
    public String state() {
        JSONObject o = new JSONObject();
        try {
            o.put("notif", ReminderScheduler.notificationsEnabled(activity));
            o.put("exact", ReminderScheduler.exactAlarmAllowed(activity));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("widget", TodayWidgetProvider.count(activity) > 0);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public void requestNotif() {
        if (Build.VERSION.SDK_INT < 33) {
            openNotifSettings();
            return;
        }
        activity.runOnUiThread(() -> {
            if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(activity, "通知权限已经开着了", Toast.LENGTH_SHORT).show();
                return;
            }
            activity.requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, MainActivity.REQ_NOTIF);
        });
    }

    @JavascriptInterface
    public void openNotifSettings() {
        activity.runOnUiThread(() -> {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + activity.getPackageName()));
            }
            try {
                activity.startActivity(i);
            } catch (Exception e) {
                toast("打不开系统设置，请手动到设置里开启通知");
            }
        });
    }

    @JavascriptInterface
    public void openExactSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 用系统浏览器打开外链（设置页的 GitHub 主页）；只放行 http(s) */
    @JavascriptInterface
    public void openUrl(String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return;
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            } catch (Exception e) {
                toast("没有可用的浏览器");
            }
        });
    }

    /** [{"at":毫秒,"name":"","time":"","location":""}] */
    @JavascriptInterface
    public String schedule(String json) {
        List<ReminderScheduler.Item> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ReminderScheduler.Item it = new ReminderScheduler.Item();
                it.at = o.optLong("at");
                it.name = o.optString("name");
                it.time = o.optString("time");
                it.location = o.optString("location");
                list.add(it);
            }
        } catch (Exception e) {
            return "{\"ok\":false,\"count\":0}";
        }
        int n = ReminderScheduler.schedule(activity, list);
        return "{\"ok\":true,\"count\":" + n + "}";
    }

    @JavascriptInterface
    public void cancelAll() {
        ReminderScheduler.cancelAll(activity);
    }

    @JavascriptInterface
    public String pushWidget(String json) {
        TodayWidgetProvider.savePayload(activity, json);
        TodayWidgetProvider.updateAll(activity);
        return "{\"ok\":true}";
    }

    @JavascriptInterface
    public String pinWidget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "{\"ok\":false,\"reason\":\"unsupported\"}";
        }
        AppWidgetManager mgr = AppWidgetManager.getInstance(activity);
        if (!mgr.isRequestPinAppWidgetSupported()) {
            return "{\"ok\":false,\"reason\":\"unsupported\"}";
        }
        ComponentName cn = new ComponentName(activity, TodayWidgetProvider.class);
        activity.runOnUiThread(() -> {
            try {
                mgr.requestPinAppWidget(cn, null, null);
            } catch (Exception ignored) {
            }
        });
        return "{\"ok\":true}";
    }

    /** 写 .ics → 存一份到下载目录 → 交给系统日历打开 */
    @JavascriptInterface
    public String exportIcs(String name, String content) {
        JSONObject result = new JSONObject();
        try {
            String safe = sanitize(name);
            File dir = IcsProvider.dir(activity);
            File file = new File(dir, safe);
            try (OutputStream os = new FileOutputStream(file)) {
                os.write(content.getBytes("UTF-8"));
            }

            copyToDownloads(safe, content);

            Uri uri = IcsProvider.uriFor(safe);
            String path = file.getAbsolutePath();
            activity.runOnUiThread(() -> share(uri, path));

            result.put("ok", true);
            result.put("path", path);
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("message", "写入失败：" + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return result.toString();
    }

    // ---------- 内部 ----------

    private void share(Uri uri, String path) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/calendar")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(view);
            toast("已生成日历文件，在日历里确认导入");
            return;
        } catch (Exception ignored) {
            // 设备上没有直接吃 text/calendar 的应用，退到分享面板
        }

        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/calendar")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "花椒课表")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "用日历导入课表");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(chooser);
        } catch (Exception e) {
            toast("已保存到：" + path);
        }
    }

    /** Android 10+ 额外存一份到「下载」，用户自己能找到文件 */
    private void copyToDownloads(String name, String content) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/calendar");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri item = activity.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (item == null) return;

            try (OutputStream os = activity.getContentResolver().openOutputStream(item)) {
                if (os != null) os.write(content.getBytes("UTF-8"));
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            activity.getContentResolver().update(item, cv, null, null);
        } catch (Exception ignored) {
            // 存不进下载目录不影响主流程（应用目录那份还在）
        }
    }

    private static String sanitize(String name) {
        String n = (name == null || name.trim().isEmpty()) ? "huajiao" : name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (!n.toLowerCase().endsWith(".ics")) n = n + ".ics";
        return n;
    }

    private void toast(String msg) {
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
