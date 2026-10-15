# AGENTS.md

Reasonix Studio 的 Android 手机端控制客户端：Kotlin + WebView 壳，扫码配对后远程控制电脑端 Studio。

---

## ✅ 桥自检日志（已真机验证 2026-10-15）：桥可用性首次可直接证明

### 为什么做

**背景**：JS 桥的线程安全修复（详下「待真机验证」第 1 条③）长期无法被真机日志验证，因为它
依赖登录页注入脚本（`LoginDiag`），而 **`autoFillCredentials` 默认关闭 → 不注入 → 无诊断**。
所以才加了桥自检 —— 它现已真机验证通过（见下方「验证与判据」里的真机结果）。

当时的决定性证据：`2026-10-15 08:24` 那份日志**确实进过登录页**（`08:24:02 → /login/`，
`08:24:24` 离开，用户是卸载重装后运行的，跑的就是新代码），却一条 `LoginDiag` 都没有。
这是**设计使然，不是故障** —— 看代码即可确认：

```kotlin
private fun handleLoginPageFinished(url: String) {
    if (!isLoginPageUrl(url)) return
    if (!preferenceManager.autoFillCredentials) return   // ← 默认 false，直接返回
    injectLoginAutofill()                                 // ← 脚本没注入，故无 LoginDiag
}
```

### 目标（已达成，见下）

让「桥是否可用」在日志里可见，且**不依赖开关、不改用户可见行为、不必等登录态过期**。

### 实现（代码已落地；本地 `assembleDebug`、`compileDebugKotlin --rerun-tasks` 与单测均通过，真机判据见本节末尾）

**1. `bridge/ReasonixJsBridge.kt` 新增自检方法**（加一个新的日志标签 `SELF_CHECK_TAG = "BridgeCheck"`）：

```kotlin
/**
 * 桥自检：证明 @JavascriptInterface 方法能被正常调用。
 *
 * 为什么需要：桥方法运行在 WebView **私有后台线程**，若在方法体里碰 WebView 会抛
 * IllegalStateException 并被 WebView 静默吞掉 —— 曾导致所有桥功能失效却毫无日志线索
 * （详见类注释与「待真机验证」）。本方法只回传自身可被调用的证据、不含敏感信息，
 * 让桥可用性在任何页面都能被验证。
 *
 * 故意**不做同源/域校验**：否则校验逻辑自身出问题时，自检也会跟着失灵，无法区分
 * 「桥不可用」与「非同源」。
 */
@JavascriptInterface
fun bridgeSelfCheck(): String {
    FileLogger.i(SELF_CHECK_TAG, "桥自检成功（@JavascriptInterface 调用正常）")
    return "ok"
}
```

**2. `ui/MainActivity.kt` 在 `onPageFinished` 注入自检脚本**（`url` 非 about:blank 时），
与登录填充脚本互不影响：

```js
(function () {
  try {
    var b = window.ReasonixMobileNative;
    if (!b || typeof b.bridgeSelfCheck !== 'function') {
      console.error('[桥自检] window.ReasonixMobileNative 不存在或方法缺失');
      return;
    }
    var r = b.bridgeSelfCheck();
    if (r !== 'ok') {
      console.error('[桥自检] 调用未返回预期值：' + r);
    }
  } catch (e) {
    console.error('[桥自检] 调用抛异常：' + e);
  }
})();
```

关键点：**JS 侧要同时覆盖 `catch` 与返回值两条路径** —— 桥方法抛
`IllegalStateException` 时，不同 WebView 版本可能表现为「JS 收到 `undefined`」或
「JS 侧抛异常」。失败信息经已有的 `onConsoleMessage`（ERROR 通道 + 3 秒去重）落盘，
因此**不必**再新增桥方法。

### 验证与判据

装新 APK → 打开 App 到任意页面 → 悬浮菜单「导出诊断日志」，看：

| 日志表现 | 含义 |
|---|---|
| 出现 `BridgeCheck` 的「桥自检成功」 | **桥线程安全修复生效**（本项目首次能直接证明） |
| 只有 `[桥自检] …` 的 ERROR | 桥仍不可用，ERROR 文本会指出断在哪一环 |
| 两者都没有 | 注入没执行到 → 查 `onPageFinished` 里的接线 |

**真机结果（2026-10-15 10:25，命中第一行判据）：**

```
10-15 10:25:34.519 INFO App: App 启动，版本 1.2.0
10-15 10:25:34.928 INFO Main: WebView 内核：com.google.android.webview@138.0.7204.179
10-15 10:25:36.022 INFO Main: 页面开始加载 https://studio.reasonix.io/?device=68…
10-15 10:25:37.326 INFO BridgeCheck: 桥自检成功（@JavascriptInterface 调用正常）
```

即：桥自检成功、且**没有** `[桥自检]` 的 ERROR。这一次日志同时证明了两件事 ——
① 桥的 `@JavascriptInterface` 方法确实能被页面调用；② 方法体不再抛
`IllegalStateException`（③ 号根因的修复生效），因此同一根因导致的
`requestDisconnect` / `requestNewScan` 静默失效也应一并恢复。

**它没有证明的**：账号密码自动填充的端到端链路。那次日志里没有 `/login/`，
说明登录态未过期、注入脚本根本没被走过 —— 剩余待验项见文末「待真机验证」。

---

## 提交与发布状态（2026-10-15：main 与 tag v1.2.0 指向同一提交）

- `main` 与 tag `v1.2.0` **指向同一提交**（提交信息
  `feat: 账号密码自动填充、诊断日志与桥自检（v1.2.0）`，本段文档更新已并入该提交）。
- 推送 tag 即触发 CI（`.github/workflows/android-release.yml`）构建并发布正式 Release，
  版本由 tag 注入 → `versionName=1.2.0` / `versionCode=10200`：
  https://github.com/yuanyuandazhuangba/reasonix_mobile_android/releases/tag/v1.2.0
- 本地 `dist/Reasonix_Mobile_v1.2.0.apk`（sha256 `0fd368faef48…`）与 Release 附件源自
  同一提交、同一固定 debug 签名，可互相覆盖安装。
- `v1.2.0` 是**轻量 tag**，而 `v1.0.0` / `v1.1.0` 是注解 tag（`ls-remote` 里带 `^{}`）。

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
- `storage/CredentialStore.kt` — 用户主动保存的 Reasonix 账号密码，用 Android Keystore
  的 AES/GCM 加密后落盘（密钥不出安全硬件，零新增依赖，minSdk 24 即可）。
- `storage/PreferenceManager.kt` — 当前连接、多设备历史（Gson/JSON）、悬浮按钮位置。
- `bridge/ReasonixJsBridge.kt` — 暴露给网页的 `ReasonixMobileNative`；`requestDisconnect`
  与 `requestNewScan` 有同源校验；`getLoginCredential` / `stageLoginCredential` 只对
  `reasonix.io` 域放行（登录页在该外部域，凭据绝不能被其他页面取走）。
  **桥绝不持有 `WebView` 实例**：`@JavascriptInterface` 方法跑在 WebView 的私有后台线程，
  而 WebView 的方法只能在 UI 线程调用（`getUrl()` 会 `checkThread()` 并无条件抛
  `IllegalStateException`）。页面地址一律经 `currentUrlProvider` 读 MainActivity 里由
  UI 线程维护的 `currentPageUrl` 缓存（见下）。**改这个文件时不要引入任何 WebView 调用。**

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

- 账号密码自动填充（**默认关闭**，菜单里可开启）：开启后，首次在 `reasonix.io/login/`
  手动登录成功后应被记住，之后重开自动填入（只填充，登录按钮仍需用户点击）。
  关闭时会一并清除已存凭据；菜单另有「忘记已保存的账号密码」。
  此功能**连修三轮才触及根因**，务必看完再动：
  ① 旧版把「填充」与「装 submit 监听」耦合在同一个 `try`/提前 `return` 里；
  ② 改成两步隔离后真机仍不保存，于是把「监听绑 `#login-form` 元素不可靠」与
     「保存只挂 `onPageFinished` 而 `/remote/` 只存活约 1.2 秒」也都改了 —— **仍然无效**；
  ③ **真正根因**：`ReasonixJsBridge` 的同源/域校验里读了 `webView.url`，而
     `@JavascriptInterface` 方法是 WebView 在**私有后台线程**上回调的，`getUrl()` 会
     `checkThread()` 并**无条件抛** `IllegalStateException` —— 桥上每个方法一进去就抛，
     且异常被 WebView 静默吞掉。表现为「功能完全不生效、日志里一条线索都没有」。
     真机日志的决定性矛盾：**已有「另有 N 条相同内容已折叠」日志（证明新 APK 生效），
     却一条 `LoginDiag` 都没有**。
     修法：MainActivity 用 `@Volatile currentPageUrl` 缓存页面地址（`onPageStarted` +
     `doUpdateVisitedHistory` 写入），桥只读缓存，**彻底不碰 WebView**。同一根因还让
     `requestDisconnect` / `requestNewScan` 一直静默失效，一并恢复。
  **桥可用性已真机证明**（2026-10-15 10:25 的 `BridgeCheck` 日志，见文首）—— ③ 号根因的
  修复确实生效。但**自动填充的端到端仍未被走过**：那次日志里没有 `/login/`，说明登录态
  未过期，注入脚本 → 捕获提交 → 保存凭据这条链路一次都没被执行。仍需复验（看导出日志里
  `LoginDiag` 与 `已记住登录账号 xxx***` 两行即可判定），且判据只在**真正进入过登录页**、
  并开启了自动填充时才成立。
- 会话与账号的分离：菜单「断开当前连接」只 `clearCurrentConnection()`（保登录态）；
  「退出 Reasonix 账号」走 `clearReasonixAuthCookies()`，只清
  `REASONIX_AUTH_ORIGINS`（reasonix.io / id.reasonix.io / studio.reasonix.io）三个域的
  Cookie，**不用 `removeAllCookies`**（那会连带清掉已配对电脑端的凭据、逼用户重新扫码）。
- 登录成功即刻落盘会话：`onPageStarted` 里由 `wasOnLoginPage` 判断「刚离开登录页」，
  执行 `persistCookies()`。此前只在配对完成与 `onStop` 落盘，登录后立刻强杀 App 会丢会话。
- 悬浮按钮的拖拽手感、软键盘让位、静止半透明。
- 自签名证书仍能连接（SSL 例外已收紧为「出错主机 == 已配对主机 且属私有网段」）。
- 文件上传与拍照、`target=_blank`、页面下载（API<29 会提示改用电脑端）、多设备切换。
- 若电脑端页面引用了 `file://` 或 `content://` 资源，收紧 `allowFileAccess/allowContentAccess`
  后会出现资源加载失败。

### 已知未解决

- **旧内核手机「远程连接已断开」+ `Failed to execute 'generateKey' on 'SubtleCrypto':
  Algorithm: Unrecognized name`**：**已定位为 WebView 内核版本问题，不在本仓库**。
  电脑端页面的远程连接握手调用 `crypto.subtle.generateKey({name:'X25519'},…)`
  （本机 `frontend-next/dist/assets/index-*.js` 内，全 bundle 仅此一处 `generateKey`，
  **无特性检测、无 P-256 降级**），而 WebCrypto 的 **X25519 需 Chromium 133+**；
  旧内核直接抛错，页面随即报「远程连接已断开，请重新连接」。
  **实测对照（决定性）**：华为 `com.huawei.webview@114.0.5.302`（Chrome 114）必现；
  同一 App、同一账号在 `com.google.android.webview@138.0.7204.179`（Chrome 138）上正常。
  App 侧已加诊断：启动时用 `WebView.getCurrentWebViewPackage()` 记录内核版本，低于
  133 写 WARN 日志并 Toast 提示升级（`logWebViewEngine` / `notifyOutdatedWebViewOnce`，
  常量 `MIN_CHROMIUM_FOR_X25519`）。**App 无法绕过** —— X25519 是页面自己发起的
  WebCrypto 调用，原生无从替代；根治需电脑端加 P-256 ECDH 降级或引导用户升级 WebView。
- **内核较新手机上 `Failed to load module script: … MIME type of "text/html"` +
  `Failed to fetch dynamically imported module: …/Markdown-*.js`**：**云端部署问题，不在本仓库**。
  实测 `https://studio.reasonix.io/assets/Markdown-COaoHQuq.js` 返回 **HTTP 200 但
  `Content-Type: text/html`**（服务端把不存在的静态资源 fallback 成了 SPA 首页 HTML），
  浏览器按 ES module 严格 MIME 校验拒绝执行，Markdown 块降级为纯文本
  （页面自己会打 `[reasonix] a block fell back to its source text`）。
  属电脑端/云端资源与版本不一致；App 侧可做的仅是清缓存重试（FAB 菜单「刷新」）。
- **`onConsoleMessage` 日志刷屏（已修）**：页面出故障时同一句错误会在极短时间内刷几十条
  （实测 16ms 内 30+ 条），每条都 append 进 `filesDir/logs/reasonix.log`，512KB 上限很快
  触发滚动、把有用记录挤掉。现已按「级别+来源+内容」在 3 秒窗口内去重：首次记一条，
  窗口内只累计并补记「另有 N 条相同内容已折叠」。
- **`Cannot read properties of undefined (reading 'filter')`（手机端初始化时必现）**：
  **根因在电脑端前端，不在本仓库**。断点在入口 bundle `index-bQUFPwcc.js` 的「设备访问」
  组件（`phonepop`）：`let v=o.st.devices.filter(e=>e.online).length+o.st.cloudDevices.length`，
  同一行上方却用 `o.st?.devices` 保护，且全 bundle 无 `devices` 字段构造代码 ——
  即前端对服务端 `/share` 返回的 `devices` 无条件信任。`/share` 返回 404 时
  `shareStatus()` 给 `null`，被 `if(!o.st) return null` 挡住不崩；但**返回 200 却缺
  `devices` 时必崩**。页面**没有全局错误边界**（唯一一处只包住 Markdown 懒加载块），
  渲染期抛错会卸载整棵 React 树，所以表现是整块界面消失。
  决定性证据：**用微信扫码登录手机网页端同样复现**（那不是本 App，也不带本 App 的 UA）。
  排除过的假设：①与本 App 的版本号/UA 无关（前端产物里搜不到 `ReasonixMobile`/`getAppVersion`）；
  ②与 `allowContentAccess` 无关。App 侧已加 `onConsoleMessage` 把页面 JS 错误并入日志，
  便于下次直接取证而无需再翻混淆产物。
- **「切换会话内容加载慢」**：已确认页面是单页应用内切换（不触发整页加载），瓶颈在电脑端页面
  的数据请求，**不在本仓库**。已做的壳层优化（移除强制硬件层缓存、把 Cookie 落盘从
  `onPageFinished` 移到 `onStop`）对「纯等待」无直接帮助。下一步定位手段：用
  `chrome://inspect` 从电脑附着到手机 WebView，确认慢在哪一条请求。
- `usesCleartextTraffic="true"` 全局放行明文：`network_security_config` 的 `<domain>` 不支持
  CIDR，无法只对 `192.168.*` 放开，属局域网 HTTP 的必然取舍，已在 README 记录。
