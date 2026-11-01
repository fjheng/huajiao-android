package com.huajiao.calendar;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 花椒日历 · 安卓壳
 *
 * 界面是一份内嵌 HTML（assets/index.html），和网页版是同一份代码。
 *
 * 关键点：页面是「假装」从一个 https 站点加载的。
 * loadUrl 一个不存在的域名，再由 shouldInterceptRequest 把请求全拦下来，
 * 主文档返回 assets 里的 HTML，取数请求转给原生。
 * 这样页面有正常的 https origin，localStorage 能存住凭证，
 * 而且 fetch('/api/proxy?...') 是同源请求，不需要像鸿蒙那样再写一层 JS 桥。
 *
 * 另一件事是「顶框」：Android 15（targetSdk 35）会强制全面屏绘制，
 * 网页会画到状态栏底下，顶栏就和手机的时间/电量叠在一起。
 * 所以这里拿到系统栏高度，用 WebView 容器的 padding 把内容整体下移，
 * 上面那条空白就是顶框，颜色跟着页面底色/日夜模式走。
 */
public class MainActivity extends Activity {

    /** 虚拟源站：页面以为自己在一个 https 站点上，实际请求全被拦截 */
    private static final String BASE_URL = "https://appassets.local/";
    private static final String PAGE_URL = BASE_URL + "index.html";
    private static final String BASE_HOST = "appassets.local";

    private static final String PATH_PROXY = "/api/proxy";
    private static final String PATH_IMAGE = "/api/image";

    private static final String COLOR_LIGHT = "#F2F3F5";
    private static final String COLOR_DARK = "#0F1115";

    private static final String ASSET = "index.html";

    /** 通知权限请求码（Android 13+） */
    public static final int REQ_NOTIF = 1001;

    private FrameLayout container;
    private WebView web;
    private JwxtClient client;

    /** 页面当前的日夜状态；null 表示还没收到网页消息，按系统走 */
    private Boolean pageDark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        client = new JwxtClient();

        container = new FrameLayout(this);
        container.setBackgroundColor(backgroundColor());

        web = new WebView(this);
        configure(web);
        web.setWebViewClient(new BridgeClient());
        web.addJavascriptInterface(new ThemeBridge(), "AndroidTheme");
        web.addJavascriptInterface(new ToolBridge(this), "AndroidTool");

        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(container);

        enableEdgeToEdge();
        applyInsets();
        applySystemBars();

        // 通知渠道提前建好：等第一条提醒到点再建就来不及了
        ReminderScheduler.ensureChannel(this);

        web.loadUrl(PAGE_URL);
    }

    /** 通知权限问完，告诉网页刷新一下权限提示 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF && web != null) {
            web.evaluateJavascript(
                    "window.dispatchEvent(new Event('huajiao:perm'))", null);
        }
    }

    /**
     * 统一走全面屏绘制。
     * Android 15（targetSdk 35）本来就会强制全面屏，老系统上主动打开，
     * 这样各代机型的 inset 行为一致，顶框高度也就一致，
     * 不会出现「有的机型已经让出状态栏、又多加一份 padding」的双重留白。
     */
    private void enableEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        // 课表和凭证都存在 localStorage 里，不开这个每次打开都要重新绑定
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        view.setBackgroundColor(backgroundColor());
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    /**
     * 给容器留出系统栏空间：
     * 顶部那条空白 = 顶框，网页内容永远不会压到状态栏和刘海；
     * 底部避开手势条，底部导航才按得到。
     * 非全面屏的机型系统已经让出位置，这里拿到的 inset 是 0，不会叠加。
     */
    private void applyInsets() {
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        container.requestApplyInsets();
    }

    /** 网页里切日夜模式时回调过来，把状态栏/导航栏和窗口底色一起换掉 */
    private final class ThemeBridge {
        @JavascriptInterface
        public void setTheme(String theme) {
            final boolean dark = "dark".equals(theme);
            runOnUiThread(() -> {
                if (pageDark != null && pageDark == dark) return;
                pageDark = dark;
                int background = backgroundColor();
                if (container != null) container.setBackgroundColor(background);
                if (web != null) web.setBackgroundColor(background);
                applySystemBars();
            });
        }
    }

    /** 拦截页面发往虚拟源站的取数请求，改由原生去访问教务站 */
    private final class BridgeClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return intercept(request.getUrl().toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return intercept(url);
        }

        /** 页面里的外链（GitHub、教务说明页等）交给系统浏览器，别在 WebView 里跑丢 */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return openOutside(request.getUrl().toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return openOutside(url);
        }
    }

    /** 只有自家虚拟源站留在 WebView，其余 http(s) 一律甩给系统浏览器 */
    private boolean openOutside(String url) {
        if (url == null || url.startsWith(BASE_URL)) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private WebResourceResponse intercept(String url) {
        if (url == null || !url.startsWith(BASE_URL)) {
            return null;
        }
        Uri uri = Uri.parse(url);
        if (!BASE_HOST.equals(uri.getHost())) {
            return null;
        }
        String path = uri.getPath();
        if (path == null) {
            return null;
        }

        // 主文档也由这里返回。走 loadUrl 而不是 loadDataWithBaseURL，
        // 是为了让页面的 origin 就是 https://appassets.local，
        // localStorage 才稳定可用（否则凭证每次启动都得重填）。
        if ("/index.html".equals(path) || "/".equals(path)) {
            return htmlResponse(readAsset(ASSET));
        }

        // 页面里 Service Worker 和 favicon 会请求不存在的地址，挡掉免得每次启动都报网络错误
        if ("/sw.js".equals(path) || "/favicon.ico".equals(path)) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        }

        boolean isImage = PATH_IMAGE.equals(path);
        if (!isImage && !PATH_PROXY.equals(path)) {
            return null;
        }

        String target = uri.getQueryParameter("url");
        if (target == null || target.isEmpty()) {
            return jsonResponse(errorJson("缺少 url 参数"));
        }

        JwxtClient.Result result = client.get(target);
        if (!result.ok) {
            return jsonResponse(errorJson(result.error));
        }
        if (isImage) {
            return new WebResourceResponse(result.mime, null, new ByteArrayInputStream(result.body));
        }
        return jsonResponse(okJson(result));
    }

    /** 返回结构必须和页面里 api.js 期待的一致：{ok, status, html} / {ok, error} */
    private String okJson(JwxtClient.Result result) {
        try {
            JSONObject body = new JSONObject();
            body.put("ok", true);
            body.put("status", result.status);
            body.put("html", result.text);
            return body.toString();
        } catch (Exception e) {
            return errorJson("返回数据组装失败");
        }
    }

    private String errorJson(String message) {
        try {
            JSONObject body = new JSONObject();
            body.put("ok", false);
            body.put("error", message == null || message.isEmpty() ? "请求失败" : message);
            return body.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"请求失败\"}";
        }
    }

    private WebResourceResponse htmlResponse(String html) {
        return new WebResourceResponse(
                "text/html",
                "UTF-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private WebResourceResponse jsonResponse(String json) {
        return new WebResourceResponse(
                "application/json",
                "UTF-8",
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<!doctype html><meta charset=\"utf-8\">"
                    + "<body style=\"font:16px/1.7 sans-serif;padding:32px;color:#333\">"
                    + "<h3>应用资源缺失</h3><p>assets/" + name + " 读不到，请重新构建。</p>";
        }
    }

    /** 页面传来的日夜状态优先；没有就跟随系统 */
    private boolean isDark() {
        if (pageDark != null) {
            return pageDark;
        }
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int backgroundColor() {
        return Color.parseColor(isDark() ? COLOR_DARK : COLOR_LIGHT);
    }

    /** 状态栏/导航栏跟着页面底色走，深色模式下换成浅色图标 */
    private void applySystemBars() {
        int background = backgroundColor();
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);

        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (!isDark()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
        if (web != null) {
            web.setBackgroundColor(backgroundColor());
        }
        if (container != null) {
            container.setBackgroundColor(backgroundColor());
        }
    }
}
