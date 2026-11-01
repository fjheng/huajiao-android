package com.huajiao.calendar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把导出的 .ics 文件暴露给系统日历 / 分享面板。
 *
 * 为什么自己写一个而不是用 androidx 的 FileProvider：这个工程刻意零第三方依赖
 * （构建快、离线能编），而 ContentProvider 只需要实现 openFile 一个方法就够用。
 * exported=false + grantUriPermissions，只有拿到临时授权的应用能读，不会变成公共通道。
 */
public class IcsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.huajiao.calendar.ics";

    /** 导出目录：应用专属外部目录，不需要存储权限 */
    public static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        // 极少数情况下外部存储不可用（正在挂载/被弹出），退回内部目录，至少不崩
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "ics");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(name));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) throw new FileNotFoundException("缺少文件名");
        // 防目录穿越：只允许本目录下的单层文件名
        name = name.replace("/", "_").replace("\\", "_");
        File f = new File(dir(getContext()), name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "text/calendar";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
