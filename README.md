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
   - 自动在后台将单次配对凭证兑换为长效安全 Cookie / Session 并在本地加密存储；
   - 退出 App 再次打开自动重连，无需每次重复扫码。

4. **原生系统体验优化**：
   - Reasonix 标志性暗夜黑金视觉配色（`#07080C` 与 `#E9B456`）；
   - 支持双击返回键防误触退出、沉浸式状态栏与导航栏；
   - 浮动操作菜单（随时重新扫码、刷新页面、断开连接设备）。

---

## 📁 项目结构

```
reasonix_mobile_android/
├── settings.gradle                      # 项目模块与 Maven 仓库源配置
├── build.gradle                         # 根工程构建插件配置
├── gradle.properties                    # JVM 与 AndroidX 配置
├── app/
│   ├── build.gradle                     # 模块依赖 (CameraX, ML Kit, OkHttp, Material)
│   ├── proguard-rules.pro               # 混淆与反射保留规则
│   └── src/main/
│       ├── AndroidManifest.xml          # 权限(相机、网络、振动)与 Activity 注册
│       ├── java/io/reasonix/mobile/
│       │   ├── ReasonixApp.kt           # Application 入口
│       │   ├── model/
│       │   │   └── PairingInfo.kt       # 扫码 URL 与配对凭证解析器
│       │   ├── storage/
│       │   │   └── PreferenceManager.kt # 最近连接设备与凭证持久化
│       │   ├── bridge/
│       │   │   └── ReasonixJsBridge.kt  # 原生 Kotlin 与 WebView JS 交互桥接
│       │   └── ui/
│       │       ├── BarcodeAnalyzer.kt   # ML Kit 二维码图像分析器
│       │       ├── ScanActivity.kt      # CameraX 原生相机扫码界面
│       │       ├── MainActivity.kt      # 主控制容器 (WebView + 设备状态管理)
│       │       └── ScanOverlayView.kt   # 自定义取景框与激光扫描动画 View
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml    # 主界面布局 (全屏 WebView + 悬浮菜单 + 状态层)
│           │   └── activity_scan.xml    # 扫码界面布局
│           ├── values/
│           │   ├── colors.xml           # 黑金调色板
│           │   ├── strings.xml          # 中文文案与提示
│           │   └── themes.xml           # 沉浸式暗黑主题
│           └── drawable/                # 矢量图标与按钮样式
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
   - 如需切换电脑或重新扫码，点击右下角浮动的金黄色悬浮按钮，在弹出菜单中选择 **“扫描新设备二维码”** 即可。
