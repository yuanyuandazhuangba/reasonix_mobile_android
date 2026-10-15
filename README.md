# Reasonix Mobile Android Client (手机远程控制端)

专为 **Reasonix Studio** 打造的 Android 原生移动端控制 App。支持手机摄像头直接扫描电脑端 Studio 生成的配对二维码，实现局域网内极速直连并远程控制 Reasonix Studio。

---

## 🌟 核心特性

1. **界面与操作 100% 一致**：
   - 核心会话区采用定制原生 WebView 渲染电脑端 Reasonix Studio 原生响应式前端界面；
   - 包含完整的会话查看、发消息、批准执行动作、模型切换等全部功能；
   - 电脑端 Studio 升级界面，手机端自动实时同步，免去频繁发版。

2. **原生极速扫码配对**：
   - 采用 Android 官方 **CameraX** + Google **ML Kit Barcode Scanning** 引擎；
   - 毫秒级识别二维码，支持闪光灯补光、精准扫码取景框与金色激光扫描动画；
   - 扫码成功具备原生马达触觉振动反馈。

3. **智能协议解析与安全配对**：
   - 自动解析 Reasonix 配对凭据（`#pair=xxxx` 或 `#token=xxxx` 或外网 Tailscale/Cloud 授权）；
   - 配对凭据由电脑端页面脚本在 WebView 内兑换为长效 Cookie / Session，App 侧负责
     保持 Cookie 持久化，退出 App 再次打开自动重连，无需每次重复扫码；
   - **凭据不留存**：本地只保存去掉 `pair` / `token` 的地址，配对凭据亦被排除在云备份
     与设备迁移之外。地址栏/历史里的 fragment 由页面自己的启动脚本（`window.__rxAuth`）
     在兑换凭据后清除，App 侧不重复干预。

4. **原生系统体验优化**：
   - Reasonix 标志性暗夜黑金视觉配色（`#07080C` 与 `#E9B456`）；
   - 浮动菜单按钮不再遮挡页面右下角的提示词发送按钮：可拖拽并吸附到最近的屏幕
     侧边、位置自动记忆，软键盘弹出时自动让位，静止 2 秒后转为半透明；
   - 支持双击返回键防误触退出、沉浸式状态栏与导航栏；
   - 可记住多台电脑端，随时在浮动菜单中一键切换。

5. **网页能力完整对齐**：
   - 网页里的文件选择与拍照上传、`target=_blank` 新窗口、下载、麦克风/摄像头权限
     请求均由原生接管，不再是"点了没反应"。

6. **账号密码自动填充（可选，默认关闭）**：
   - Reasonix 的登录状态由账号网站（`reasonix.io`）自行保持，正常情况下**不会**走到
     登录页；只有当登录状态过期、需要重新输入账号密码时，本功能才省事 —— 因此它
     **默认关闭**，由你在悬浮菜单里按需开启；
   - 开启后：首次在登录页**手动登录成功**时 App 会记住该账号，之后再次进入登录页会
     自动填入邮箱与密码，**只填充、不自动提交**，登录按钮仍由你点击；
   - 关闭时会**一并清除**本机保存的账号密码；也可在菜单里单独选
     **「忘记已保存的账号密码」** 清除。
   - 凭据用 Android **Keystore 硬件密钥 + AES/GCM** 加密后存储，明文不落盘、
     不写日志、不参与云备份与设备迁移。

7. **会话与账号是两件事**：
   - 悬浮菜单里的 **「断开当前连接」** 只解除对这台电脑的接管，**保留** Reasonix 登录
     状态，因此重连通常无需重新登录；
   - **「退出 Reasonix 账号（清除登录状态）」** 才会清除本机保存的登录状态，下次连接
     需要重新登录账号。它**不会**清除已配对的电脑端，所以不需要重新扫码。

---

## ⚠️ 内核版本要求

网页端的远程连接使用 WebCrypto 的 **X25519** 算法做密钥交换，该算法**需要
Chromium 133 或更高版本**的浏览器内核。

- 部分手机（尤其华为等自带 WebView 的机型）内核较旧，连接时会失败并提示
  `Algorithm: Unrecognized name` 或「远程连接已断开」；
- App 启动时会检测内核版本，过低时会在界面上提示你升级
  **「Android System WebView」** 或 **「Chrome」**；
- 这是浏览器内核能力限制，App 自身无法绕过。可在
  `设置 → 应用 → Android System WebView` 查看当前版本（也可在 App 导出的
  诊断日志里看到 `WebView 内核：…` 一行）。

---

## 🔐 安全说明

本项目以**局域网内自用**为设计前提，以下几点是明确的取舍，请知悉：

- **明文流量**：`AndroidManifest.xml` 中 `android:usesCleartextTraffic="true"`。电脑端
  Studio 通过局域网 HTTP 提供服务，且 `network_security_config` 的 `<domain>` 不支持
  CIDR，无法只对 `192.168.*` 放开，故全局允许明文。请勿在不可信网络中暴露该端口。
- **自签名证书**：仅当证书出错的主机与已配对的私有网段主机一致时才放行，其余一律
  拒绝并在界面提示，避免 Tailscale / 公网场景被中间人接管。
- **调试签名**：仓库内置 `app/debug.keystore`（口令为 Android 默认值），目的是让本地
  与 CI 产出同一签名的 APK、支持相互覆盖安装。**该文件等同于公开密钥，请勿用于正式
  发布**；如需发布，应改用独立 release keystore 并经由 CI Secrets 注入。
- **JS 桥**：`ReasonixMobileNative` 对页面可见，其中会清除凭据的 `requestDisconnect`
  与拉起相机的 `requestNewScan` 会校验调用页面与已配对页面同源，非同源调用被忽略。
- **保存的账号密码（仅在你开启后才可能发生）**：自动填充**默认关闭**，关闭状态下 App
  完全不接触账号密码（不注入脚本、不捕获提交、不留存）。开启后，仅在手动登录成功时
  记录，使用 Android Keystore 设备密钥加密（AES/GCM），明文不落盘、不写日志、不参与
  云备份与设备迁移；仅在 `reasonix.io` 登录页可被读取，且**只填充、不自动提交**。
  关闭功能或选择「忘记已保存的账号密码」即清除。
- **会话 Cookie**：Reasonix 的登录状态由网站 Cookie 维持（App 不清除、只落盘），
  因此正常使用不会反复要求登录。「退出 Reasonix 账号」会清除 `reasonix.io` 各子域的
  登录态 Cookie，但**不动**已配对电脑端的凭据。

---

## 📁 项目结构

```
reasonix_mobile_android/
├── settings.gradle                      # 项目模块与 Maven 仓库源配置
├── build.gradle                         # 根工程构建插件配置
├── gradle.properties                    # JVM 与 AndroidX 配置
├── app/
│   ├── build.gradle                     # 模块依赖 (CameraX, ML Kit, Gson, Material)
│   ├── debug.keystore                   # 固定调试签名（本地与 CI 产出一致，便于覆盖安装）
│   ├── proguard-rules.pro               # 混淆与反射保留规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # 权限(相机/录音/网络/振动)、FileProvider 与 Activity 注册
│       │   ├── java/io/reasonix/mobile/
│       │   │   ├── ReasonixApp.kt           # Application 入口
│       │   │   ├── model/
│       │   │   │   └── PairingInfo.kt       # 配对 URL 解析与凭据净化（纯 Kotlin，可单测）
│       │   │   ├── storage/
│       │   │   │   ├── CredentialStore.kt   # 账号密码：Keystore 硬件密钥 + AES/GCM 加密存储
│       │   │   │   └── PreferenceManager.kt # 当前连接、多设备历史与悬浮按钮位置持久化
│       │   │   ├── util/
│       │   │   │   └── FileLogger.kt        # 异步文件日志（诊断导出用，512KB 滚动）
│       │   │   ├── bridge/
│       │   │   │   └── ReasonixJsBridge.kt  # 原生 Kotlin 与 WebView JS 交互桥接（含同源校验）
│       │   │   └── ui/
│       │   │       ├── BarcodeAnalyzer.kt   # ML Kit 二维码图像分析器
│       │   │       ├── FloatingFabController.kt # 悬浮菜单按钮：拖拽吸附/键盘让位/静止半透明
│       │   │       ├── MainActivity.kt      # 主控制容器 (WebView + 连接与设备管理)
│       │   │       ├── ScanActivity.kt      # CameraX 原生相机扫码界面
│       │   │       └── ScanOverlayView.kt   # 自定义取景框与激光扫描动画 View
│       │   └── res/
│       │       ├── layout/
│       │       │   ├── activity_main.xml    # 主界面布局 (全屏 WebView + 悬浮菜单 + 状态层)
│       │       │   └── activity_scan.xml    # 扫码界面布局
│       │       ├── values/
│       │       │   ├── colors.xml           # 黑金调色板
│       │       │   ├── strings.xml          # 中文文案与提示
│       │       │   └── themes.xml           # 沉浸式暗黑主题
│       │       ├── xml/
│       │       │   ├── backup_rules.xml         # 备份规则（排除配对凭据）
│       │       │   ├── data_extraction_rules.xml # 云备份/迁移规则（排除配对凭据）
│       │       │   └── file_paths.xml           # FileProvider 共享路径（拍照上传）
│       │       └── drawable/                # 矢量图标与按钮样式
│       └── test/java/io/reasonix/mobile/model/
│           └── PairingInfoTest.kt       # 配对解析的 JVM 单元测试
├── .github/workflows/
│   └── android-release.yml              # 推 v* tag 时跑测试、构建并发布 APK
└── README.md                            # 项目说明文档
```

---

## 🛠️ 构建与编译打包指南

### 方式一：使用 Android Studio 编译打包（最简单推荐）

1. **安装环境**：
   - 下载并安装 [Android Studio](https://developer.android.com/studio)（推荐 Hedgehog 或更高版本）；
   - 安装 JDK 17 或 JDK 21（Android Studio 内置）。

2. **导入工程**：
   - 打开 Android Studio，点击 **Open**；
   - 选择目录：`D:\project_xiangmu\reasonix_mobile_android`；
   - 等待 Gradle 自动完成依赖下载与项目索引同步（Sync Project）。

3. **直接运行到手机测试**：
   - 手机打开“开发者选项”并启用“USB 调试”；
   - 用数据线连接电脑，在 Android Studio 顶部工具栏选择你的手机；
   - 点击绿色的 **Run ('app')** 按钮，App 会自动编译并安装到手机上。

4. **打包生成 Release / Debug APK**：
   - 点击顶部菜单栏 **Build** -> **Build Bundle(s) / APK(s)** -> **Build APK(s)**；
   - 编译完成后，右下角弹窗点击 `locate`，即可在 `app/build/outputs/apk/debug/` 下拿到 `app-debug.apk`；
   - 若要签名发布正式版：点击 **Build** -> **Generate Signed Bundle / APK...**，选择 **APK**，创建或选择签名证书即可生成 Release APK。

---

### 方式二：使用 Gradle 命令行编译（无需 GUI）

在 `reasonix_mobile_android` 目录下执行：

```bash
# 编译 Debug 版 APK
./gradlew assembleDebug

# 运行单元测试（配对解析逻辑的边界回归）
./gradlew testDebugUnitTest

# 或者在 Windows CMD 中：
gradlew.bat assembleDebug
```

编译生成的 APK 文件位于：
`app/build/outputs/apk/debug/app-debug.apk`，直接传给手机安装即可！

---

## 📱 使用步骤

1. **电脑端开启手机访问**：
   - 打开电脑上的 **Reasonix Studio**；
   - 进入 **设置** -> **设备访问 / 手机访问**；
   - 开启 **允许手机访问**；
   - 点击 **显示配对二维码**。

2. **手机端扫码连接**：
   - 打开安装好的 **Reasonix Studio** 手机 App；
   - 首次打开点击主界面的 **“扫码连接电脑端”**（授予相机权限）；
   - 对准电脑屏幕上的配对二维码；
   - 识别成功后手机发出轻微振动，App 自动完成配对并进入控制主界面！

3. **日常使用与重连**：
   - 下次打开 App 会自动连回电脑端 Studio，无需再次扫码；
   - 如需重新扫码或断开连接，点击屏幕边缘的金黄色悬浮按钮，在弹出菜单中选择对应项即可；
   - 悬浮按钮可以**按住拖到任意位置**，松手后自动吸附到最近的侧边并记住位置 —— 若它
     挡住了页面上的发送按钮，拖开一次即可；打字时它会自动隐藏，静止 2 秒后转为半透明。
   - 连接过多台电脑后，弹出菜单会多出 **“切换已连接的设备”**，可直接切回之前那台，无需再次扫码。
