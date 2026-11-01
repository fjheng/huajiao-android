package com.huajiao.calendar;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 教务站取数。
 *
 * 网页跑在 WebView 里，受同源策略限制，不能直接 fetch 教务站；
 * 所以由这一层用系统网络栈去取，结果交给 shouldInterceptRequest 返回给页面。
 *
 * 两处被动过：
 *  1. 证书校验放宽 —— 教务站证书链缺中间证书，系统默认校验会失败；
 *  2. 域名白名单 —— 这个类只允许访问 jwxt.ecjtu.edu.cn/weixin/，避免变成任意请求通道。
 */
public class JwxtClient {

    private static final String ALLOW_HOST = "jwxt.ecjtu.edu.cn";
    private static final String ALLOW_PATH_PREFIX = "/weixin/";
    private static final int TIMEOUT_MS = 15000;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final SSLSocketFactory relaxedFactory;

    private final HostnameVerifier relaxedVerifier = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    /** 一次请求的结果；图片只要 body + mime，课表页额外要 text */
    public static class Result {
        public boolean ok;
        public int status;
        public byte[] body = new byte[0];
        public String mime = "application/octet-stream";
        public String text = "";
        public String error = "";
    }

    public JwxtClient() {
        SSLSocketFactory factory = null;
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            factory = context.getSocketFactory();
        } catch (Exception ignored) {
            // 拿不到就退回系统默认校验，失败时页面会显示"网络请求失败"
        }
        relaxedFactory = factory;
    }

    public Result get(String target) {
        Result result = new Result();

        Uri uri = Uri.parse(target);
        if (!"https".equals(uri.getScheme())
                || !ALLOW_HOST.equals(uri.getHost())
                || uri.getPath() == null
                || !uri.getPath().startsWith(ALLOW_PATH_PREFIX)) {
            result.error = "目标地址不在白名单内";
            return result;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target).openConnection();
            if (conn instanceof HttpsURLConnection && relaxedFactory != null) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(relaxedFactory);
                https.setHostnameVerifier(relaxedVerifier);
            }
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*,*/*");
            conn.setInstanceFollowRedirects(true);

            result.status = conn.getResponseCode();
            InputStream in = result.status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            result.body = readAll(in);
            result.mime = mimeOf(conn.getContentType());
            result.text = new String(result.body, StandardCharsets.UTF_8);
            result.ok = result.status >= 200 && result.status < 300;
            if (!result.ok) {
                result.error = "教务系统返回 HTTP " + result.status;
            }
        } catch (Exception e) {
            result.ok = false;
            result.error = "网络请求失败：" + describe(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return result;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        in.close();
        return out.toByteArray();
    }

    /** "text/html; charset=utf-8" → "text/html" */
    private static String mimeOf(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return "application/octet-stream";
        }
        int semi = contentType.indexOf(';');
        String mime = semi > 0 ? contentType.substring(0, semi) : contentType;
        return mime.trim();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isEmpty()) ? e.getClass().getSimpleName() : message;
    }
}
