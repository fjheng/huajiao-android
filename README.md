# 花椒日历 · 安卓版

学校教务课表的第三方客户端。WebView 壳 + 单文件前端（`app/src/main/assets/index.html`），
**零第三方依赖**：不引 androidx，网络用 `HttpURLConnection`，`FileProvider` 自己实现了替代品。

当前版本 **2.1**（versionCode 7，minSdk 24 / targetSdk 35）。

## 功能

- **课表**：日视图 / 周视图，左右滑动翻周，记一次周次就能推算整个学期
- **拓展中心**
  - 考试安排：扫整学期，倒计时到分，已考完的单独分组灰显
  - 月视图：6×7 格，点日期直接跳当天
  - 学期统计：第 1 周 → 第 20 周列表，点进去看那一周；课程门数、每周课时、全学期次数、楼栋分布
  - 上课提醒：本地通知，提前 5~120 分钟可调，`AlarmManager` 精确闹钟（无权限时自动降级）
  - 上课时间：夏季 / 冬季两套作息，第 3、4 节还能按教学楼分档
  - 桌面小组件：今日课程 4×2，数据由网页侧推送，不联网
  - 导出 .ics：按周展开 VEVENT（绕开 RRULE 处理断档和单双周），带 VALARM
  - 教学日历：学期校历图片，可缩放

## 构建

常规方式（需要 Android SDK + JDK 17）：

```bash
./gradlew assembleDebug
```

`local.properties` 里的 `sdk.dir` 需要指向自己的 SDK，这个文件没有进版本库。

仓库里另有一个离线构建脚本 `build.sh`（aapt2 → javac → d8 → zipalign → apksigner），
在没有 Gradle 网络环境时用，产物在 `apk/`。

## 目录

```
app/src/main/assets/index.html     前端全部代码（自制模块系统，单文件）
app/src/main/java/.../             安卓壳：WebView、桥、闹钟、小组件、ContentProvider
app/src/main/res/                  资源：小组件布局、图标、主题
apk/                               已签名的安装包（2.1 最新，old/ 里是历史版本）
README.txt                         详细说明：每个功能的实现取舍、已知限制、常见问题
```

## 说明

- 非官方应用，课表数据来自学校教务的微信端接口，接口随时可能变；变了要改的是前端里的 parser。
- 只在本机保存 weiXinID 和课表缓存，提醒、小组件、导出的日历都不上传。
- 用 Android Debug 证书签名，能直接装、能发同学，但不能上架应用市场。
