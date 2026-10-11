# AGENTS.md

Reasonix Studio 的 Android 手机端控制客户端：Kotlin + WebView 壳，扫码配对后远程控制电脑端 Studio。

## Project

- **核心会话界面不在本仓库。** 本仓库只是原生壳（扫码配对、WebView 托管、原生能力接管、
  连接与设备管理），界面与业务逻辑由电脑端 Studio 的网页渲染并自动同步。改动本仓库不会
  影响页面内部的加载速度或交互逻辑 —— 诊断「页面内某操作慢」时先确认是否该在电脑端修。
- 技术栈：Kotlin 1.9.22 / AGP 8.2.2 / Gradle 8.2.1 / compileSdk 34 / minSdk 24；
  CameraX + ML Kit 扫码，Gson 存多设备历史，Room 与网络库均**未**使用。
- 包名 `io.reasonix.mobile`。入口 `app/src/main/java/io/reasonix/mobile/ui/MainActivity.kt`；
  `ReasonixApp` 仅是空 Application 占位。

## Commands

系统默认 `java` 是 **1.8**，而 AGP 8 需要 JDK 17 —— **每条 Gradle 命令都必须显式设 `JAVA_HOME`**。
本机 `./gradlew` 不可用（TLS 无法下载 Gradle 发行版，报 `PKIX path building failed`），
请改用已安装的 `gradle-8.2.1`：

```bash
export JAVA_HOME="D:/android_toolchain/jdk-17"
export ANDROID_HOME="D:/android_toolchain/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
G="D:/android_toolchain/gradle-8.2.1/bin/gradle.bat"

"$G" assembleDebug            # 产物 app/build/outputs/apk/debug/app-debug.apk
"$G" testDebugUnitTest        # 单测；结果 XML 在 app/build/test-results/testDebugUnitTest/
"$G" clean assembleDebug      # 全量重建（怀疑增量缓存时用）
"$G" assembleDebug -PversionName=1.2.0 -PversionCode=10200   # 模拟 CI 的版本注入
US_MAVEN=true "$G" testDebugUnitTest                         # 以 CI 的仓库源模式验证
```

Android SDK 在 `D:\android_toolchain\android-sdk`（`platforms/android-34` + `build-tools/34.0.0`），
已由 `local.properties` 指定。APK 版本号可用 build-tools 的 `aapt2 dump badging <apk>` 核对。

## Architecture

- `ui/MainActivity.kt` — 唯一承重类（约 1000 行）。WebView 配置与生命周期、渲染进程崩溃
  自恢复、返回键、悬浮菜单、文件选择与拍照上传、下载、网页媒体权限、多设备切换、SSL 策略。
- `ui/FloatingFabController.kt` — 悬浮按钮：拖拽吸附最近的边、位置持久化、软键盘弹出让位、
  静止 2 秒转半透明。这类原生悬浮控件会盖住网页内容，注意勿再引入同类遮挡。
- `model/PairingInfo.kt` — 配对 URL 解析、私有网段判定、凭据净化。**刻意不使用
  `android.net.Uri`**，以便在 JVM 单测中直接运行（`src/test/.../PairingInfoTest.kt`，28 个用例）。
- `storage/PreferenceManager.kt` — 当前连接、多设备历史（Gson/JSON）、悬浮按钮位置。
- `bridge/ReasonixJsBridge.kt` — 暴露给网页的 `ReasonixMobileNative`；`requestDisconnect`
  与 `requestNewScan` 有同源校验，非同源调用会被静默忽略。

## Conventions

- 注释与 UI 文案用**中文**；注释解释「为什么」（多数记录了一个已修的坑或有意的取舍），
  修改时沿用这种风格，不要退化成复述代码。
- 新增网页能力 = 覆写 `WebViewClient` / `WebChromeClient` 的方法，并在 `configureWebView()` 中接线。
- 版本号只读 `BuildConfig.VERSION_NAME`，不要硬编码到 Kotlin 里。
- 单测只覆盖不依赖 Android 框架的纯逻辑；引用 `android.*` 的类在 JVM 单测中会 "not mocked"。
- 改动网页侧可见行为时，同步更新 `README.md`（用户视角）与本文档（维护视角）。
- `_session_tmp/`、`build/`、`.gradle/`、`local.properties`、`dist/*.apk` 均已 gitignore；
  提交前确认 `git status` 干净，不要把临时探针脚本留在仓库里。

## Notes

### 构建与 CI 陷阱

- `settings.gradle` 有 `US_MAVEN` 开关：CI 设 `true` 只用 `google()` + `mavenCentral()` 官方源，
  本地不设则走阿里云镜像加速。原因：阿里云不镜像 `com.android.tools.utp`（测试平台依赖，
  解析测试依赖时才会拉取），且 **Gradle 遇 5xx 直接失败、不会回退到后续仓库** —— 曾因此
  让 CI 构建挂掉（502 Bad Gateway）。
- CI（`.github/workflows/android-release.yml`）**只在推送 `v*` tag 时触发**，推 `main` 不构建。
  要出包必须新建或移动 tag（移动已推送的 tag 需 `--force`）。
- 版本号由 tag 注入：`v1.2.3` → `versionName=1.2.3` / `versionCode=10203`
  （`app/build.gradle` 用 Groovy 的 `isInteger()/toInteger()`，**不是** Kotlin 的 `toIntOrNull()`）。
  本地不传 `-P` 时默认 `1.1.0` / `10100`。
- `app/debug.keystore` 入库是**有意为之**（固定 debug 签名，使本地与 CI 产物可互相覆盖安装、
  升级不丢数据）。口令为 Android 默认值，等同公开，**不可用于正式发布**。

### 待真机验证（本地只能保证编译与单测通过）

- `MainActivity.ENABLE_CREDENTIAL_HISTORY_STRIPPING`：页面加载后用 `history.replaceState`
  剥离地址与历史中的 `pair`/`token`。需确认「杀进程重开仍免扫码」；若失效改为 `false` 即可
  关闭该行为，其余逻辑不受影响。
- 悬浮按钮的拖拽手感、软键盘让位、静止半透明。
- 自签名证书仍能连接（SSL 例外已收紧为「出错主机 == 已配对主机 且属私有网段」）。
- 文件上传与拍照、`target=_blank`、页面下载（API<29 会提示改用电脑端）、多设备切换。
- 若电脑端页面引用了 `file://` 或 `content://` 资源，收紧 `allowFileAccess/allowContentAccess`
  后会出现资源加载失败。

### 已知未解决

- **「切换会话内容加载慢」**：已确认页面是单页应用内切换（不触发整页加载），瓶颈在电脑端页面
  的数据请求，**不在本仓库**。已做的壳层优化（移除强制硬件层缓存、把 Cookie 落盘从
  `onPageFinished` 移到 `onStop`）对「纯等待」无直接帮助。下一步定位手段：用
  `chrome://inspect` 从电脑附着到手机 WebView，确认慢在哪一条请求。
- `usesCleartextTraffic="true"` 全局放行明文：`network_security_config` 的 `<domain>` 不支持
  CIDR，无法只对 `192.168.*` 放开，属局域网 HTTP 的必然取舍，已在 README 记录。
