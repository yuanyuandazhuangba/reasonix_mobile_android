package io.reasonix.mobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.reasonix.mobile.BuildConfig
import io.reasonix.mobile.R
import io.reasonix.mobile.bridge.ReasonixJsBridge
import io.reasonix.mobile.databinding.ActivityMainBinding
import io.reasonix.mobile.model.PairingInfo
import io.reasonix.mobile.storage.CredentialStore
import io.reasonix.mobile.storage.PreferenceManager
import io.reasonix.mobile.storage.SavedDevice
import io.reasonix.mobile.util.FileLogger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.HashMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var webView: WebView
    private lateinit var fabController: FloatingFabController
    private var lastBackPressTime = 0L

    /** 渲染进程被系统回收（低内存等）后只自动恢复一次，避免反复重建形成死循环。 */
    private var rendererRecovered = false

    /**
     * 网页文件选择回调。WebView 的 `onShowFileChooser` 是异步的，必须把回调挂起直到
     * 用户选完文件再交还，否则页面的 `<input type="file">` 会永久卡在"等待选择"状态。
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 拍照上传时相机的输出目标，需要在选择结果回调中转成 content:// 交给页面。 */
    private var cameraOutputUri: Uri? = null

    /** 相机输出文件本身，用于在用户取消时清理可能残留的空文件。 */
    private var cameraOutputFile: File? = null

    /**
     * 上传挂起期间渲染进程被回收时，记下待恢复的地址，等文件选择结果返回后再重建。
     * 为 null 表示没有待处理的重建。
     */
    private var pendingRebuildUrl: String? = null

    /**
     * 用户在 `reasonix.io` 登录页提交过的账号密码（仅内存，不落盘）。
     *
     * 提交不等于登录成功（密码可能错），因此先暂存在这里；等页面确实离开登录页时，
     * 才认为这次登录成功，再交给 [CredentialStore] 加密保存。这样保证记住的一定是
     * 被服务端接受过的凭据。
     *
     * 加 `@Volatile`：由 JS 桥线程写入（页面提交时回调），由 UI 线程读取并落盘，
     * 不加的话新值不保证对另一线程可见，可能出现「刚暂存就读到 null」。
     */
    @Volatile
    private var pendingLoginCredential: Pair<String, String>? = null

    /** 内核过旧的提示只弹一次，避免每次页面重建都重复打扰。 */
    private var outdatedWebViewNotified = false

    /**
     * 当前页面 URL 的缓存，供 JS 桥在后台线程安全读取。
     *
     * 为什么必须缓存、而不是让桥直接读 `webView.url`：`@JavascriptInterface` 暴露的方法
     * 由 WebView 在**它自己的私有后台线程**上调用（AOSP 对 `addJavascriptInterface` 的
     * 文档明确说明「JavaScript interacts with Java object on a private, background
     * thread of this WebView」），而 WebView 的所有方法都要求在其创建线程（UI 线程）上
     * 调用 —— `WebViewChromium.getUrl()` 会先 `checkThread()` 并**无条件抛**
     * `IllegalStateException("Calling methods on another thread than the UI thread.")`。
     *
     * 后果极其隐蔽：桥上每个方法一进去就抛异常，而异常发生在 JavaScriptInterface 调用
     * 内部、被 WebView 静默吞掉 —— 表现为「功能完全不生效，日志里却一条线索都没有」。
     * 曾因此连续误判三轮，以为是注入脚本或页面改版的问题。
     *
     * 改由 UI 线程的导航回调写入本字段，桥只读它，彻底不触碰 WebView 实例。
     */
    @Volatile
    private var currentPageUrl: String = ""

    /**
     * 上一个整页导航是否停在登录页，用于识别「刚刚登录成功」这一时刻。
     *
     * 为什么要记住：离开登录页就意味着服务端接受了这次凭据。此时要补做两件事 ——
     * 把新拿到的会话 Cookie 落盘（否则登录成功后立刻杀掉 App，下次还得重新登录），
     * 以及把暂存的账号密码加密保存（仅在用户开启了自动填充时）。
     */
    @Volatile
    private var wasOnLoginPage = false

    /**
     * 页面控制台消息的去重状态：key 为「级别+来源+内容」，value 为窗口内被压掉的条数。
     *
     * 为什么需要：页面出故障时会成批刷同一句错误 —— 实测内核较新的机器上，
     * `Markdown-*.js` 的 MIME 错误在 16ms 内刷了 30+ 条，512KB 的日志瞬间被灌满并
     * 触发滚动，把真正有用的记录挤掉。按内容在时间窗内去重后，日志能保住首条
     * 与「重复了多少次」这两个关键信息，又不至于淹没其他记录。
     */
    private val consoleDedupe = HashMap<String, ConsoleDedupeEntry>()

    /** 在窗口结束时补记折叠条数，并清掉对应 key，避免 map 无限增长。 */
    private val consoleDedupeHandler = Handler(Looper.getMainLooper())

    private class ConsoleDedupeEntry(var suppressed: Int)

    /** 网页视频全屏时占位的原生视图与其回调。 */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /**
     * 网页请求的媒体权限结果。WebView 的 `onPermissionRequest` 拿到系统授权结果后
     * 必须显式 grant/deny，否则请求会悬停在"等待授权"状态。
     */
    private var pendingPermissionRequest: PermissionRequest? = null

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val request = pendingPermissionRequest ?: return@registerForActivityResult
        pendingPermissionRequest = null
        // 只放行页面真正请求、且用户已授予的那些资源
        val granted = request.resources.filter { res ->
            webPermissionToAndroid(res)?.let { grants[it] == true } ?: false
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private val scanQrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedUrl = result.data?.getStringExtra(ScanActivity.EXTRA_SCANNED_URL)
            if (!scannedUrl.isNullOrEmpty()) {
                connectToUrl(scannedUrl)
            }
        }
    }

    /** 网页文件选择结果：可能是系统文件选择器，也可能是相机拍照的输出。 */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null

        val uris: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> {
                // 用户取消：拍照场景可能已建出空文件，顺手清理
                FileLogger.i(TAG, "文件选择被取消")
                cleanupEmptyCaptureFile()
                null
            }

            // 相机拍照时系统不回传 data，需取预先约定的输出位置；
            // 而在选择器里选了文件时 data 一定存在，优先采用，避免误用空的拍照文件。
            result.data != null -> extractResultUris(result.data)
            cameraOutputUri != null -> {
                // 文件已交给页面使用，不能再纳入清理范围
                cameraOutputFile = null
                arrayOf(cameraOutputUri as Uri)
            }

            else -> null
        }
        cameraOutputUri = null
        FileLogger.i(TAG, "文件选择返回：${uris?.size ?: 0} 个文件")
        // 取消时也必须回调 null，否则页面选择框会永远卡住
        callback.onReceiveValue(uris)

        // 上传挂起期间渲染进程被回收过：此刻选择已结束，可以安全重建并恢复页面了
        pendingRebuildUrl?.let { url ->
            pendingRebuildUrl = null
            FileLogger.i(TAG, "选择已结束，执行此前延后的 WebView 重建")
            scheduleRebuildWebView(url)
        }
    }

    /**
     * 重建 WebView 并重新加载页面。
     *
     * 放在下一帧执行：`onRenderProcessGone` 与文件选择回调都在 WebView 的回调栈上，
     * 直接在栈内 destroy + 新建同一容器里的 WebView 容易引发重入问题。
     */
    private fun scheduleRebuildWebView(targetUrl: String?) {
        FileLogger.i(TAG, "重建 WebView 并重新加载页面")
        Handler(Looper.getMainLooper()).post {
            rebuildWebView()
            if (!targetUrl.isNullOrEmpty()) {
                loadStudioUrl(targetUrl)
                Toast.makeText(
                    this@MainActivity,
                    R.string.reconnecting,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showEmptyState(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        webView = binding.webView

        setupFab()
        setupWindowInsets()
        configureWebView(webView)
        setupUIEvents()
        setupBackPressHandler()

        // 尝试自动恢复上次连接
        val savedUrl = preferenceManager.lastConnectedUrl
        if (!savedUrl.isNullOrEmpty()) {
            FileLogger.i(TAG, "MainActivity 创建，自动恢复上次连接")
            loadStudioUrl(savedUrl)
        } else {
            FileLogger.i(TAG, "MainActivity 创建，无历史连接，显示空状态")
            showEmptyState(true)
        }
    }

    /**
     * 悬浮按钮改为可拖拽、可记忆位置，并在软键盘弹出时让位。
     * 详见 [FloatingFabController]。
     */
    private fun setupFab() {
        fabController = FloatingFabController(
            fab = binding.fabMenu,
            container = binding.rootCoordinator,
            preferenceManager = preferenceManager,
            onClick = { showActionDialog() }
        )
        fabController.bind()
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // 软键盘可见性用于让悬浮按钮避让正在输入的提示词框
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            fabController.applyWindowInsets(insets)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(target: WebView) {
        target.apply {
            // 刻意不调用 setLayerType(LAYER_TYPE_HARDWARE)：
            // WebView 本身已由系统硬件加速渲染，再强制整层 software/hardware layer
            // 会把页面内容缓存成一大张纹理，内容频繁更新（会话流式输出、列表滚动）
            // 时需要反复重绘整层，反而更慢且更占内存。保持默认即可。
            overScrollMode = View.OVER_SCROLL_NEVER

            // 让渲染进程保持较高优先级，降低后台被判为可回收而掉线的概率（API 26+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                setSupportZoom(false)

                // 页面本身走局域网 HTTP，只需放行被动混合内容（图片等）；
                // 不再无条件放行全部混合内容，避免被劫持的页面把 HTTP 脚本注入到会话中。
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT

                // 页面是 http(s) 远程内容，不需要 file:// 与 content:// 读取能力，
                // 收紧以缩小攻击面；若后续本地预览确需放开，应按需单独处理。
                allowFileAccess = false
                allowContentAccess = false

                // 保持长连接心跳所需的脚本执行能力
                loadsImagesAutomatically = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }

                // 自定义 UserAgent 标识为 Reasonix Mobile Client（版本取自构建配置）
                val defaultUa = userAgentString
                userAgentString = "$defaultUa ReasonixMobile/${BuildConfig.VERSION_NAME}"

                // 记录 WebView 内核版本：电脑端前端用 WebCrypto 的 X25519 做远程连接握手，
                // 而该算法只有 Chromium 133+ 支持；旧内核会抛
                // "Failed to execute 'generateKey' on 'SubtleCrypto': Algorithm: Unrecognized name"。
                // 记下版本即可判定是否属此因，无需真机抓包。
                logWebViewEngine(defaultUa)

                // 页面里的 target=_blank / window.open 需要多窗口支持，否则点击后
                // 毫无反应；实际打开目标由 onCreateWindow 接管为当前窗口内跳转。
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
            }

            // 保持会话与缓存，减少每次重连的握手开销
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(target, true)
            }

            // 页面上的下载链接原先点了毫无反应，这里交给系统下载管理器。
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                enqueueDownload(url, contentDisposition, mimeType)
            }

            // 注入原生 JS 桥接：破坏性操作（断开/重扫码）会校验调用方是不是已配对的页面；
            // 账号密码相关方法只对 reasonix.io 登录域放行（见 ReasonixJsBridge）
            addJavascriptInterface(
                ReasonixJsBridge(
                    context = this@MainActivity,
                    currentUrlProvider = { currentPageUrl },
                    pairedOriginProvider = {
                        preferenceManager.lastConnectedOrigin
                            ?: preferenceManager.lastConnectedUrl
                    },
                    loginCredentialProvider = { buildLoginCredentialJson() },
                    onLoginCredentialStaged = { email, password ->
                        // 只暂存，等确认登录成功（页面跳回已配对地址）后才加密保存
                        pendingLoginCredential = email to password
                    },
                    onDisconnectListener = { runOnUiThread { disconnectCurrentDevice() } },
                    onNewScanListener = { runOnUiThread { openScanActivity() } }
                ),
                "ReasonixMobileNative"
            )

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.progress = newProgress
                    } else {
                        binding.progressBar.visibility = View.GONE
                    }
                }

                /**
                 * 捕获页面脚本的控制台输出，重点是未捕获的 JS 错误。
                 *
                 * 背景：电脑端前端没有全局错误边界（仅有一处错误边界包住 Markdown 懒加载块，
                 * 其余组件暴露在外），渲染期抛错会让整块界面消失；而 WebView 默认不会把页面
                 * console 交给原生，导致手机上出问题时既看不到报错也拿不到堆栈 —— 排查
                 * 「Cannot read properties of undefined (reading 'filter')」时只能去翻电脑端
                 * 的混淆产物反查，成本极高。这里把错误与警告并入文件日志，随「导出诊断日志」
                 * 一并取出。
                 *
                 * 只记录 ERROR / WARNING，不记录 log/info：后者量极大且可能带上会话内容，
                 * 没有必要为了诊断把它们写进磁盘。
                 */
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage ?: return false
                    val label = when (msg.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "错误"
                        ConsoleMessage.MessageLevel.WARNING -> "警告"
                        // log/info 量极大且可能带上会话内容，一律不记
                        else -> return super.onConsoleMessage(consoleMessage)
                    }

                    val source = msg.sourceId() ?: ""
                    val line = msg.lineNumber()
                    val text = msg.message() ?: ""
                    val key = "$label|$source:$line|$text"

                    val existing = consoleDedupe[key]
                    if (existing != null) {
                        // 窗口内重复：只累加不写盘 —— 这正是刷屏的来源
                        existing.suppressed++
                    } else {
                        val entry = ConsoleDedupeEntry(0)
                        consoleDedupe[key] = entry
                        val line1 = "页面 JS $label $source:$line $text"
                        if (label == "错误") FileLogger.e(TAG, line1) else FileLogger.w(TAG, line1)

                        consoleDedupeHandler.postDelayed({
                            consoleDedupe.remove(key)
                            if (entry.suppressed > 0) {
                                FileLogger.i(
                                    TAG,
                                    "页面 JS $label 另有 ${entry.suppressed} 条相同内容已折叠：$source"
                                )
                            }
                        }, CONSOLE_DEDUPE_WINDOW_MS)
                    }
                    return super.onConsoleMessage(consoleMessage)
                }

                /**
                 * 网页里的 `<input type="file">` 原本没有实现，点了没有任何反应。
                 * 这里转成系统选择器：普通场景走 ACTION_GET_CONTENT，页面声明了
                 * `capture` 且设备有拍照应用时额外提供相机入口，拍照结果经 FileProvider
                 * 以 content:// 交回页面。
                 */
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // 上一次选择尚未结束时又发起新请求，先作废旧回调，避免页面卡死
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    cameraOutputUri = null
                    FileLogger.i(
                        TAG,
                        "网页请求选择文件：types=${fileChooserParams?.acceptTypes?.joinToString()} " +
                                "capture=${fileChooserParams?.isCaptureEnabled}"
                    )

                    val intent = buildFileChooserIntent(fileChooserParams)
                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        this@MainActivity.filePathCallback = null
                        FileLogger.w(TAG, "设备上没有可用于选择文件的应用")
                        Toast.makeText(
                            this@MainActivity,
                            R.string.file_chooser_unavailable,
                            Toast.LENGTH_SHORT
                        ).show()
                        false
                    }
                }

                /**
                 * 网页请求视频全屏时接管到原生视图，否则 `<video>` 的全屏按钮无效。
                 */
                override fun onShowCustomView(
                    view: View?,
                    callback: WebChromeClient.CustomViewCallback?
                ) {
                    super.onShowCustomView(view, callback)
                    if (view == null) return
                    if (fullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    fullscreenView = view
                    fullscreenCallback = callback
                    binding.contentRoot.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                override fun onHideCustomView() {
                    super.onHideCustomView()
                    val view = fullscreenView ?: return
                    binding.contentRoot.removeView(view)
                    fullscreenView = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                }

                /**
                 * 网页脚本弹出的 alert/confirm/prompt 默认由系统渲染，在本项目的暗黑主题
                 * 下样式突兀且无法定制，这里统一改用原生对话框。
                 */
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    if (result == null) return false
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.app_name)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.dialog_ok) { _, _ -> result.confirm() }
                        .setOnDismissListener { result.cancel() }
                        .show()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    if (result == null) return false
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.app_name)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.dialog_ok) { _, _ -> result.confirm() }
                        .setNegativeButton(R.string.cancel) { _, _ -> result.cancel() }
                        .setOnDismissListener { result.cancel() }
                        .show()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    if (result == null) return false
                    val input = android.widget.EditText(this@MainActivity).apply {
                        setText(defaultValue ?: "")
                        setSingleLine()
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.app_name)
                        .setMessage(message)
                        .setView(input)
                        .setCancelable(false)
                        .setPositiveButton(R.string.dialog_ok) { _, _ ->
                            result.confirm(input.text?.toString() ?: "")
                        }
                        .setNegativeButton(R.string.cancel) { _, _ -> result.cancel() }
                        .setOnDismissListener { result.cancel() }
                        .show()
                    return true
                }

                /**
                 * 网页请求麦克风/摄像头等权限时转成系统的运行时权限申请，
                 * 否则请求会被静默拒绝，页面上的录音、拍照功能表现为"没反应"。
                 */
                override fun onPermissionRequest(request: PermissionRequest?) {
                    val req = request ?: return
                    val needed = req.resources
                        .mapNotNull { webPermissionToAndroid(it) }
                        .filter {
                            ContextCompat.checkSelfPermission(
                                this@MainActivity, it
                            ) != PackageManager.PERMISSION_GRANTED
                        }

                    if (needed.isEmpty()) {
                        req.grant(req.resources)
                        return
                    }

                    pendingPermissionRequest = req
                    mediaPermissionLauncher.launch(needed.toTypedArray())
                }

                /**
                 * 页面里的 `target=_blank` / `window.open` 会被 WebView 请求开新窗口。
                 * 若不做处理，点击后毫无反应（配合 setSupportMultipleWindows 才生效）。
                 * 这里用一个临时 WebView 接住目标地址，再按同源与否决定在主窗口加载或
                 * 交给系统应用，从而保持"单窗口"的使用体验。
                 */
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

                    val probe = WebView(this@MainActivity)
                    var handled = false
                    probe.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val targetUrl = request?.url?.toString()
                            // 探针只为拿到地址，拿到后立即销毁，不真正渲染
                            handled = true
                            v?.destroy()
                            if (!targetUrl.isNullOrEmpty()) {
                                openNewWindowTarget(targetUrl)
                            }
                            return true
                        }

                        /**
                         * 目标地址未触发导航（被拦截或加载失败）时也要销毁探针，
                         * 否则每次点击这类链接都会残留一个隐藏的 WebView 进程。
                         */
                        override fun onPageFinished(v: WebView?, url: String?) {
                            if (!handled) {
                                handled = true
                                v?.destroy()
                            }
                        }
                    }
                    transport.webView = probe
                    resultMsg.sendToTarget()
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                /**
                 * 页面内的导航分流：http(s) 与 about: 保持原有行为交给 WebView；
                 * tel: / mailto: / intent: 等自定义 scheme 交给系统应用处理 —— 原先这些
                 * 链接会被 WebView 直接拒绝，表现为点击完全没反应。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    return when (uri.scheme?.lowercase()) {
                        "http", "https", "about" -> false
                        else -> {
                            openExternally(uri.toString())
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // 单页应用内切换不会触发这里，只有整页加载才会 —— 正是排查「上传后
                    // 页面为何重新加载」所需要的证据
                    FileLogger.i(TAG, "页面开始加载 ${url?.let { PairingInfo.sanitizedUrl(it) }}")
                    binding.progressBar.visibility = View.VISIBLE
                    binding.layoutError.visibility = View.GONE

                    // 供 JS 桥在后台线程读取（桥不能直接碰 WebView，见 currentPageUrl 注释）
                    if (url != null) currentPageUrl = url

                    // 登录成功的那一刻：页面刚从登录页离开。两件事都必须在这里做，且
                    // 不能只挂 onPageFinished —— 登录成功后页面会连续跳转（实测 /remote/
                    // 只活了约 1.2 秒就被 studio.reasonix.io 接管），被接管的那一页
                    // onPageFinished 可能根本不触发。
                    if (url != null && !url.startsWith("about:blank")) {
                        val onLoginPage = isLoginPageUrl(url)
                        if (wasOnLoginPage && !onLoginPage) {
                            // 1) 落盘新会话：与账号密码功能无关，始终执行。
                            //    若等到 onStop 才落盘，登录成功后立刻强杀 App 就会丢掉会话。
                            persistCookies()
                            // 2) 保存暂存凭据：未开启自动填充时不会有暂存内容，内部自行判断
                            saveStagedCredentialIfAny()
                        }
                        wasOnLoginPage = onLoginPage
                    } else {
                        // 离开到空白页（断开连接/清除记录）时复位，避免下次导航被误判为
                        // "刚离开登录页"而多做一次落盘
                        wasOnLoginPage = false
                    }
                }

                /**
                 * 单页应用内的导航（pushState / replaceState）不触发 [onPageStarted]，
                 * 但地址已经变了；不同步的话桥看到的还是上一个整页加载的 URL，
                 * 同源校验与登录域判断都会失准。
                 */
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url != null) currentPageUrl = url
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    rendererRecovered = false

                    if (url != null && !url.startsWith("about:blank")) {
                        showEmptyState(false)
                        // 这段处在会话切换的热路径上，因此刻意保持轻量：
                        // Cookie 落盘改由 onStop / 配对完成时触发，这里不再同步 flush()；
                        // 偏好写入放到下一帧，避免与页面首屏渲染抢主线程。
                        //
                        // 地址栏里残留的配对凭据不在这里清理：页面的 index.html 内联脚本
                        // （window.__rxAuth）自己就会读取 fragment 中的 pair/token、兑换成
                        // Cookie，并随即用 history.replaceState 抹掉 fragment。原生再注入
                        // 一段清理脚本只会与它重复，还可能因为改写时机不同而互相干扰，因此
                        // 交给页面处理；App 侧只保证「落入本地存储的地址不含凭据」。
                        rememberCurrentUrl(PairingInfo.sanitizedUrl(url))
                        // 证明桥可用：不依赖自动填充开关，任何页面加载完都自检一次
                        injectBridgeSelfCheck()
                        handleLoginPageFinished(url)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        FileLogger.w(
                            TAG,
                            "主框架加载失败：${error?.errorCode} ${error?.description}"
                        )
                        showErrorState("无法连接到电脑端 Studio，请确保手机与电脑在同一局域网中，且电脑端 Studio 手机访问已开启。")
                    }
                }

                /**
                 * SSL 证书错误处理。
                 *
                 * 原实现对所有证书错误一律 `proceed()`，等于完全放弃证书校验：一旦手机
                 * 通过 Tailscale 或公网地址连到 Studio，中间人即可无痕接管会话。现在只对
                 * 「已配对的私有网段主机」保留例外（局域网自签名证书的常见场景），其余
                 * 一律拒绝并提示，避免把局域网便利性扩散成全局安全缺口。
                 */
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    val errorHost = error?.url?.let { PairingInfo.hostOf(it) }
                    val pairedHost = preferenceManager.lastConnectedOrigin?.let {
                        PairingInfo.hostOf(it)
                    }
                    val trusted = errorHost != null &&
                            errorHost == pairedHost &&
                            PairingInfo.isPrivateHost(errorHost)

                    if (trusted) {
                        handler?.proceed()
                    } else {
                        FileLogger.w(
                            TAG,
                            "SSL 证书错误被拒绝：host=$errorHost 已配对 host=$pairedHost"
                        )
                        handler?.cancel()
                        showErrorState(getString(R.string.error_ssl_untrusted))
                    }
                }

                /**
                 * 渲染进程被系统回收时不崩溃，重建 WebView 并自动重连上次地址，
                 * 这是手机端长时间后台后“掉线”的常见根因。
                 */
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    if (rendererRecovered) {
                        // 已经恢复过一次仍然崩溃，交回系统处理，避免死循环
                        FileLogger.e(TAG, "渲染进程再次被回收，交回系统处理")
                        return false
                    }
                    rendererRecovered = true
                    val targetUrl = preferenceManager.lastConnectedUrl
                    val uploadPending = filePathCallback != null
                    FileLogger.w(
                        TAG,
                        "WebView 渲染进程被回收（didCrash=${detail?.didCrash()}），" +
                                "上传挂起=$uploadPending"
                    )

                    // 上传挂起期间不立即重建：此刻页面正处于 <input type="file"> 的等待状态，
                    // 且用户很可能还在系统选择器/相册里挑文件。立即销毁重建会让页面的上传
                    // 上下文彻底丢失，用户选完文件也回不去（这正是「上传时连接断开」的表现）。
                    // 改为记录待重建，等选择结果返回后再统一恢复。
                    if (uploadPending) {
                        pendingRebuildUrl = targetUrl
                        FileLogger.w(TAG, "上传挂起中：延后重建 WebView，等待选择结果")
                        return true
                    }

                    scheduleRebuildWebView(targetUrl)
                    return true
                }
            }
        }
    }

    /**
     * 解除挂起的文件选择回调。
     *
     * WebView 的 `onShowFileChooser` 把回调挂起，等用户选完文件才交还；若中途 WebView
     * 被销毁（渲染进程被系统回收后 `rebuildWebView()` 重建）或 Activity 结束，该回调
     * 就指向一个已销毁的 WebView。此时必须回一次 null，告诉页面「选择被取消」，
     * 否则页面那个 `<input type="file">` 会永久卡在等待选择的状态。
     *
     * 上传会拉起系统文件选择器/相册，是本应用唯一会让 Activity 长时间处于后台的操作，
     * 也是渲染进程最容易被回收的时刻，因此这条清理路径必须覆盖重建场景。
     */
    private fun releasePendingFileChooser() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        cameraOutputUri = null
        cameraOutputFile = null
    }

    /**
     * 旧的 WebView 已被系统判定渲染进程崩溃，必须销毁；随后在 content_root 中
     * 重新创建一个 WebView 并重新初始化，用于渲染进程退出后的自恢复。
     */
    private fun rebuildWebView() {
        // 旧的 WebView 即将销毁，先解除挂起的文件选择回调，避免页面永远停在"等待选择"
        releasePendingFileChooser()

        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Throwable) {
            // 忽略销毁异常，继续重建
        }

        val fresh = WebView(this)
        fresh.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 插入到 content_root 的最前面，保持在进度条/状态层之下
        binding.contentRoot.addView(fresh, 0)
        webView = fresh
        configureWebView(fresh)
    }

    /**
     * 把页面上的下载请求交给系统下载管理器。
     *
     * API 29 起下载到公共 Downloads 目录不再需要存储权限；API 24~28 需要
     * WRITE_EXTERNAL_STORAGE，本应用未申请该权限（也不打算为一个低频功能引入），
     * 因此在这些设备上提示改用电脑端下载，而不是静默失败。
     */
    private fun enqueueDownload(url: String, contentDisposition: String?, mimeType: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.download_requires_newer_android, Toast.LENGTH_LONG).show()
            return
        }

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val manager = getSystemService(DOWNLOAD_SERVICE) as? DownloadManager
            if (manager == null) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                return
            }
            manager.enqueue(request)
            FileLogger.i(TAG, "已交给系统下载：$fileName")
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            FileLogger.w(TAG, "下载失败：请求参数非法")
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            FileLogger.w(TAG, "下载失败：权限不足")
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理 `target=_blank` / `window.open` 的目标地址：与电脑端 Studio 同源的链接仍在
     * 主窗口内加载（客户端应始终停留在控制界面），其余交给系统浏览器等外部应用。
     */
    private fun openNewWindowTarget(target: String) {
        val targetOrigin = PairingInfo.originOf(target)
        val pairedOrigin = preferenceManager.lastConnectedOrigin
            ?: preferenceManager.lastConnectedUrl?.let { PairingInfo.originOf(it) }

        if (targetOrigin != null && targetOrigin == pairedOrigin) {
            loadStudioUrl(target)
        } else {
            openExternally(target)
        }
    }

    /** 把自定义 scheme（tel: / mailto: / 外部 http 链接等）交给系统应用处理。 */
    private fun openExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 记录当前页面地址（已净化，不含配对凭据）。
     *
     * 这是会话切换的热路径，写入本身要序列化并落盘，因此推迟到下一帧执行：
     * 既避开与页面首屏渲染抢主线程，也不会因为同一批导航连续触发而重复写盘。
     */
    private fun rememberCurrentUrl(sanitizedUrl: String) {
        binding.root.post {
            preferenceManager.lastConnectedUrl = sanitizedUrl
            // 此时页面标题已可用，用它更新历史设备的显示名
            preferenceManager.lastConnectedOrigin?.let { origin ->
                preferenceManager.rememberDevice(origin, deviceDisplayName())
            }
        }
    }

    /**
     * 把内存中的 Cookie 同步到磁盘。
     *
     * 原实现放在 `onPageFinished` 里，每次页面加载都同步落盘；而 Cookie 的持久化
     * 只需在「可能退出应用」的时机保证一次即可（配对完成、退到后台）。
     */
    private fun persistCookies() {
        CookieManager.getInstance().flush()
    }

    /**
     * 供 JS 桥读取的账号密码（JSON）。无凭据时返回空串。
     *
     * 注意：返回值只在页面主动调用 [ReasonixJsBridge.getLoginCredential] 时产生，
     * 而该方法已限制只有 `reasonix.io` 登录域可调用。
     */
    private fun buildLoginCredentialJson(): String {
        val credential = CredentialStore.load(this) ?: return ""
        return try {
            JSONObject()
                .put("email", credential.email)
                .put("password", credential.password)
                .toString()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 处理页面加载完成后的登录相关逻辑。
     *
     * 落在 `reasonix.io` 登录页时注入自动填充脚本（只填充，登录按钮仍由用户点击）。
     * **仅在用户开启了自动填充时才注入** —— 关闭时 App 完全不接触账号密码，登录页
     * 与普通页面无异。
     */
    private fun handleLoginPageFinished(url: String) {
        if (!isLoginPageUrl(url)) return
        if (!preferenceManager.autoFillCredentials) return
        injectLoginAutofill()
    }

    /** 是否正是账号密码登录页（区别于登录域下的其他页面，如 /remote/、/account/）。 */
    private fun isLoginPageUrl(url: String): Boolean {
        val host = PairingInfo.hostOf(url) ?: return false
        val isReasonixDomain = host == "reasonix.io" || host.endsWith(".reasonix.io")
        return isReasonixDomain && url.contains("/login")
    }

    /**
     * 把暂存的凭据交给 [CredentialStore] 加密保存。
     *
     * 触发条件是"离开登录页"，因为登录失败会停留在登录页并展示错误信息，所以离开即
     * 表示服务端接受了这次凭据 —— 这样保证记住的一定是能登录成功的那一份。
     */
    private fun saveStagedCredentialIfAny() {
        // 未开启自动填充时不该有任何暂存；这里再挡一道，确保关闭后绝不落盘凭据
        if (!preferenceManager.autoFillCredentials) return
        val staged = pendingLoginCredential ?: return
        pendingLoginCredential = null
        val (email, password) = staged
        if (CredentialStore.save(this, email, password)) {
            // 只记脱敏后的邮箱用于确认保存成功，密码绝不写日志
            FileLogger.i(TAG, "已记住登录账号 ${email.take(3)}***")
        } else {
            FileLogger.w(TAG, "保存登录账号失败（Keystore 不可用或磁盘异常）")
        }
    }

    /**
     * 往登录页注入自动填充脚本。
     *
     * 为什么可以这样做：登录页的表单是原生 `addEventListener('submit')` + 直接读
     * `element.value`（不是框架受控组件），因此设置 `.value` 即生效。脚本会：
     * - 填入邮箱与密码，并派发 input 事件（兼容以后改用受控组件的改版）；
     * - 监听表单提交，把用户实际提交的凭据回传原生暂存 —— 只有提交成功后页面跳回
     *   电脑端地址时，原生才会真正保存，避免把输错的密码记下来；
     * - 全程不点击登录按钮、不提交表单，登录动作始终由用户自己完成。
     */
    private fun injectLoginAutofill() {
        // 必须始终注入，不能因为"当前没有已保存凭据"就跳过：
        // 脚本承担两件事 —— 有凭据时填充，以及在用户提交登录表单时把凭据回传原生。
        // 首次使用时本来就没有凭据（无需填充），但提交监听必须装上，否则第一次登录
        // 成功后凭据永远存不下来，功能等于失效。
        webView.evaluateJavascript(LOGIN_AUTOFILL_SCRIPT, null)
    }

    /**
     * 往当前页面注入桥自检脚本。
     *
     * 为什么需要：桥的线程安全修复（见 [currentPageUrl] 注释）此前**没有任何真机日志能
     * 证明它生效** —— 唯一的桥侧诊断来自登录页注入脚本 `LoginDiag`，而它只在用户开启了
     * 自动填充、且确实进入登录页时才产生。结果是「桥是否可用」在日志里长期不可见，
     * 复查时无法区分「桥坏了」与「这次根本没走到那条路径」。
     *
     * 因此这里在每个非空白页面加载完成后都自检一次：不依赖开关、不改任何用户可见行为、
     * 也不必等登录态过期。成功由桥侧记 `BridgeCheck` 日志；失败经脚本里的 console.error
     * 走 [WebChromeClient.onConsoleMessage] 的 ERROR 通道落盘 —— 因此不必为此新增桥方法，
     * 判据始终是「导出日志里有没有 BridgeCheck 成功行」。
     */
    private fun injectBridgeSelfCheck() {
        webView.evaluateJavascript(BRIDGE_SELF_CHECK_SCRIPT, null)
    }

    /**
     * 记录 WebView 内核版本，用于判定 X25519 是否可用。
     *
     * 为什么需要：电脑端页面用 WebCrypto 的 X25519 做远程连接握手，而该算法仅
     * Chromium 133+ 支持；旧内核会失败并报
     * "Failed to execute 'generateKey' on 'SubtleCrypto': Algorithm: Unrecognized name"。
     * 记下内核版本即可直接判定根因，不必再复现或抓包。
     *
     * 判定优先用 [WebView.getCurrentWebViewPackage]（权威来源，API 26+），
     * UA 里的 Chrome 版本仅作兜底 —— 两者不一致时以包版本为准。
     */
    private fun logWebViewEngine(defaultUa: String) {
        val webViewPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                WebView.getCurrentWebViewPackage()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val uaChromeVersion = Regex("""Chrome/(\d+)""").find(defaultUa)
            ?.groupValues?.get(1)?.toIntOrNull()

        // 包版本形如 133.0.6943.121，取主版本号
        val packageMajorVersion = webViewPackage?.versionName
            ?.substringBefore('.')?.toIntOrNull()

        FileLogger.i(
            TAG,
            "WebView 内核：${webViewPackage?.packageName ?: "未知"}@" +
                    "${webViewPackage?.versionName ?: "未知"}；" +
                    "UA 中 Chrome/${uaChromeVersion ?: "未知"}"
        )

        // 以权威包版本为准；拿不到包版本时退回 UA 版本
        val effectiveVersion = packageMajorVersion ?: uaChromeVersion ?: return
        if (effectiveVersion < MIN_CHROMIUM_FOR_X25519) {
            FileLogger.w(
                TAG,
                "WebView 内核 $effectiveVersion 低于 Chromium $MIN_CHROMIUM_FOR_X25519：" +
                        "页面远程连接依赖的 X25519 密钥交换不可用，" +
                        "会报 Algorithm: Unrecognized name。这是内核能力限制，" +
                        "App 无法绕过，需升级 Android System WebView。"
            )
            notifyOutdatedWebViewOnce(effectiveVersion)
        }
    }

    /**
     * 内核过旧时提示一次「升级 WebView」。
     *
     * 这类失败表现为连接莫名中断或报一句英文算法错误，用户无从判断原因；不给提示
     * 就只能反复尝试。用静态标记保证只在首次进入时提示，避免每次页面重建都打扰。
     */
    private fun notifyOutdatedWebViewOnce(version: Int) {
        if (outdatedWebViewNotified) return
        outdatedWebViewNotified = true
        Toast.makeText(
            this,
            getString(R.string.webview_outdated_hint, version, MIN_CHROMIUM_FOR_X25519),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setupUIEvents() {
        // 空状态扫码按钮
        binding.btnEmptyScan.setOnClickListener {
            openScanActivity()
        }

        // 悬浮菜单的点击由 FloatingFabController 接管（避免与触摸拖拽冲突）

        // 错误重试按钮
        binding.btnRetry.setOnClickListener {
            val url = preferenceManager.lastConnectedUrl
            if (!url.isNullOrEmpty()) {
                loadStudioUrl(url)
            } else {
                openScanActivity()
            }
        }

        // 错误重新扫码按钮
        binding.btnErrorScan.setOnClickListener {
            openScanActivity()
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackPressTime < 2000) {
                        finish()
                    } else {
                        lastBackPressTime = currentTime
                        Toast.makeText(this@MainActivity, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * 构造文件选择意图：主体是系统的"从文件中选择"，当页面按图片采集请求且设备上
     * 确实存在拍照应用时，再把相机作为一个初始选项挂上去（chooser 里的"相机"入口）。
     */
    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val accepts = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = primaryMimeType(accepts)
            if (accepts.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
            }
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
        }

        if (accepts.any { it.startsWith("image/") } && hasCameraApp()) {
            createCaptureIntent()?.let { captureIntent ->
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
            }
        }
        return chooser
    }

    /** 页面给出的 MIME 列表可能混有 ".png" 这类扩展名，取不出的统一退化为通配符。 */
    private fun primaryMimeType(accepts: List<String>): String {
        val first = accepts.firstOrNull() ?: return "*/*"
        return if (first.contains("/")) first else "*/*"
    }

    /** Android 11+ 需在 manifest 的 queries 中声明才可见，见 AndroidManifest.xml。 */
    private fun hasCameraApp(): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    /** 拍照输出写入应用私有缓存，经 FileProvider 以 content:// 交给相机。 */
    private fun createCaptureIntent(): Intent? {
        return try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File.createTempFile("capture_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraOutputFile = file
            cameraOutputUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (_: IOException) {
            // 缓存目录不可写等异常：放弃相机入口，仍有"从文件中选择"可用
            cameraOutputFile = null
            cameraOutputUri = null
            Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
            null
        }
    }

    /** 选择结果可能是单个 URI，也可能是多选时的 ClipData。 */
    private fun extractResultUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val clipData = data.clipData
        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    /** 用户取消拍照时清理可能残留的空文件，避免缓存目录堆积垃圾。 */
    private fun cleanupEmptyCaptureFile() {
        val file = cameraOutputFile
        if (file != null && file.exists() && file.length() == 0L) {
            file.delete()
        }
        cameraOutputFile = null
        cameraOutputUri = null
    }

    /** WebView 资源名映射到对应的 Android 运行时权限，未知资源返回 null。 */
    private fun webPermissionToAndroid(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    fun openScanActivity() {
        val intent = Intent(this, ScanActivity::class.java)
        scanQrLauncher.launch(intent)
    }

    private fun connectToUrl(url: String) {
        val pairing = PairingInfo.parse(url)
        if (pairing != null) {
            // 只持久化去掉配对凭据的地址：一次性凭据不应长期留在本地存储（并随备份外流）
            preferenceManager.lastConnectedUrl = PairingInfo.sanitizedUrl(url)
            preferenceManager.lastConnectedOrigin = pairing.origin
            // 记入历史设备，便于之后在多设备之间直接切换
            preferenceManager.rememberDevice(pairing.origin, deviceDisplayName())
            // 配对凭据可能刚被页面兑换成 Cookie，落盘一次以免退出应用后失效
            persistCookies()
        }
        loadStudioUrl(url)
    }

    /** 设备显示名优先取页面标题，页面还没加载出标题时用主机名兜底。 */
    private fun deviceDisplayName(): String? {
        val title = webView.title?.trim()
        if (!title.isNullOrEmpty()) return title
        return preferenceManager.lastConnectedOrigin?.let { PairingInfo.hostOf(it) }
    }

    private fun loadStudioUrl(url: String) {
        // 记录的是脱敏后的地址，避免把配对凭据写进日志
        FileLogger.i(TAG, "加载页面 ${PairingInfo.sanitizedUrl(url)}")
        showEmptyState(false)
        binding.layoutError.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    private fun showEmptyState(show: Boolean) {
        if (show) {
            binding.layoutEmpty.visibility = View.VISIBLE
            webView.visibility = View.GONE
            binding.layoutError.visibility = View.GONE
            binding.fabMenu.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            webView.visibility = View.VISIBLE
            binding.fabMenu.visibility = View.VISIBLE
        }
    }

    private fun showErrorState(message: String) {
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun showActionDialog() {
        val devices = preferenceManager.savedDevices
        val currentOrigin = preferenceManager.lastConnectedOrigin

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels += getString(R.string.menu_rescan)
        actions += { openScanActivity() }

        labels += getString(R.string.menu_refresh)
        actions += { webView.reload() }

        // 只有一台历史设备时"切换设备"没有意义，因此多于一台才提供入口
        if (devices.size > 1) {
            labels += getString(R.string.menu_switch_device)
            actions += { showDevicePickerDialog(devices, currentOrigin) }
        }

        labels += getString(R.string.menu_disconnect)
        actions += { disconnectCurrentDevice() }

        // 出问题时把日志经分享面板交给用户，无需 USB 调试即可取到手机侧证据
        labels += getString(R.string.menu_export_logs)
        actions += { exportLogs() }

        // 账号密码自动填充默认关闭：开启后才注入填充脚本、才可能保存凭据。
        // 菜单项文字随状态变化，用户一眼能看出当前是开还是关。
        if (preferenceManager.autoFillCredentials) {
            labels += getString(R.string.menu_autofill_disable)
            actions += { setAutoFillCredentials(false) }
            if (CredentialStore.hasCredential(this)) {
                labels += getString(R.string.menu_forget_account)
                actions += { confirmForgetAccount() }
            }
        } else {
            labels += getString(R.string.menu_autofill_enable)
            actions += { setAutoFillCredentials(true) }
        }

        labels += getString(R.string.menu_logout_account)
        actions += { confirmLogoutAccount() }

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_device_options)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 已连接过的电脑端列表，点击即切回该设备（凭据已由之前的扫码兑换为 Cookie，
     * 因此切换无需再次扫码）。
     */
    private fun showDevicePickerDialog(
        devices: List<SavedDevice>,
        currentOrigin: String?
    ) {
        val labels = devices.map { device ->
            val marker = if (device.origin == currentOrigin) {
                getString(R.string.device_current_marker)
            } else {
                ""
            }
            "${device.name}\n${device.origin}$marker"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_switch_device)
            .setItems(labels) { _, which ->
                val target = devices[which]
                if (target.origin != currentOrigin) {
                    connectToUrl(target.origin)
                }
            }
            // 数据损坏或不再需要这些电脑时，提供彻底重置的出口
            .setNeutralButton(R.string.device_clear_all) { _, _ ->
                confirmClearAllDevices()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 清空全部本地记录（历史设备、当前连接与悬浮按钮位置），需要二次确认。 */
    private fun confirmClearAllDevices() {
        AlertDialog.Builder(this)
            .setTitle(R.string.device_clear_all)
            .setMessage(R.string.device_clear_all_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                preferenceManager.clearSession()
                // 凭据存在独立的 prefs 文件里，clearSession 清不到，这里一并清掉，
                // 否则用户以为"清除了全部记录"却仍留着账号密码
                CredentialStore.clear(this)
                pendingLoginCredential = null
                webView.clearCache(true)
                webView.clearHistory()
                webView.loadUrl("about:blank")
                showEmptyState(true)
                Toast.makeText(this, R.string.device_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 忘记已保存的账号密码（二次确认）。
     *
     * 这是用户对"App 代为保管账号密码"的退出通道：清除后不再自动填充，下次登录需
     * 手动输入；设备配对与历史设备列表不受影响，因此不需要重新扫码。
     */
    private fun confirmForgetAccount() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_forget_account)
            .setMessage(R.string.forget_account_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                CredentialStore.clear(this)
                pendingLoginCredential = null
                FileLogger.i(TAG, "已清除保存的登录账号")
                Toast.makeText(this, R.string.account_forgotten, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 开启/关闭账号密码自动填充。
     *
     * 关闭时顺带清除本机已保存的凭据 —— 否则用户明明关掉了功能，密码却还留在设备上，
     * 与「App 不再保管账号密码」的预期不符。设备配对与历史设备不受影响。
     */
    private fun setAutoFillCredentials(enabled: Boolean) {
        preferenceManager.autoFillCredentials = enabled
        if (enabled) {
            FileLogger.i(TAG, "已开启账号密码自动填充")
            Toast.makeText(this, R.string.autofill_enabled, Toast.LENGTH_SHORT).show()
        } else {
            CredentialStore.clear(this)
            pendingLoginCredential = null
            FileLogger.i(TAG, "已关闭账号密码自动填充，并清除本机保存的凭据")
            Toast.makeText(this, R.string.autofill_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 退出 Reasonix 账号（二次确认）。
     *
     * 与「断开当前连接」的区别：断开只解除设备接管、**保留**登录态，重连通常免登录；
     * 退出账号则清除本机保存的 Reasonix 登录态，下次连接需要重新登录。
     */
    private fun confirmLogoutAccount() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_logout_account)
            .setMessage(R.string.logout_account_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                clearReasonixAuthCookies()
                FileLogger.i(TAG, "已退出 Reasonix 账号（清除登录态 Cookie）")
                Toast.makeText(this, R.string.logout_account_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 清除承载 Reasonix 账号登录态的 Cookie（仅限 reasonix.io 各子域）。
     *
     * 为什么按域逐个清、而不是用 `removeAllCookies`：后者会把**已配对电脑端的凭据
     * Cookie 一起清掉**，用户会莫名其妙地需要重新扫码配对 —— 那是「断开配对」而不是
     * 「退出账号」。这里只针对登录态所在的域。
     *
     * CookieManager 没有「按域删除」的公开 API，因此先读出现有 Cookie 名，再逐个
     * 置为已过期；最后 flush() 确保立即落盘。
     */
    private fun clearReasonixAuthCookies() {
        val cookieManager = CookieManager.getInstance()
        REASONIX_AUTH_ORIGINS.forEach { origin ->
            val cookies = cookieManager.getCookie(origin) ?: return@forEach
            cookies.split(";").forEach { pair ->
                val name = pair.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cookieManager.setCookie(origin, "$name=; Max-Age=0; Path=/")
                }
            }
        }
        cookieManager.flush()
    }

    private fun disconnectCurrentDevice() {
        // 只断当前连接，保留历史设备列表，便于之后一键切回
        preferenceManager.clearCurrentConnection()
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl("about:blank")
        showEmptyState(true)
        Toast.makeText(this, R.string.disconnected, Toast.LENGTH_SHORT).show()
    }

    /**
     * 导出诊断日志：把应用私有目录里的日志经分享面板交给用户。
     *
     * 用途：手机端出问题时（例如上传后页面报「连接已断开」），无需 USB 调试即可取出
     * 手机侧日志 —— 此前全仓库无日志、且 USB 未连接时，只能靠电脑端日志反推。
     */
    private fun exportLogs() {
        // 写盘是异步的，先等队列落盘，否则最该看的那几行可能还没写进文件
        FileLogger.flush()
        val logFile = FileLogger.currentFile()
        if (logFile == null || logFile.length() == 0L) {
            Toast.makeText(this, R.string.export_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.menu_export_logs))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.menu_export_logs)))
            FileLogger.i(TAG, "已导出诊断日志")
        } catch (_: IllegalArgumentException) {
            // FileProvider 路径未配置等
            Toast.makeText(this, R.string.export_logs_failed, Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.export_logs_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时恢复 WebView 定时器，让长连接心跳与页面轮询立即恢复
        webView.resumeTimers()
        webView.onResume()
        FileLogger.i(TAG, "回到前台（onResume）")
    }

    override fun onPause() {
        super.onPause()
        // 刻意不调用 webView.onPause()：避免暂停 JS 定时器而切断与电脑端的心跳，
        // 这是手机端切到后台后频繁掉线的常见原因。
    }

    override fun onStop() {
        super.onStop()
        // 退到后台是「可能被杀掉」的时机，在这里保证 Cookie 已落盘一次即可，
        // 从而不必在每次页面加载时同步 flush()。
        // 用 post 让 onStop 立即返回：flush 是同步写盘，卡在 onStop 里会拖长
        // 「已进入后台」这个时间点，而系统正是在这之后开始做内存回收决策。
        binding.root.post { persistCookies() }
        FileLogger.i(TAG, "进入后台（onStop），已安排 Cookie 落盘")
    }

    /**
     * 系统内存压力回调。
     *
     * 这是判断「渲染进程为何被回收」最直接的证据来源：上传会把 Activity 长时间留在
     * 后台，此刻若出现 RUNNING_LOW / RUNNING_CRITICAL 等升高等级，即可确认回收是内存
     * 压力所致，而非本应用的逻辑缺陷 —— 排查「上传时连接断开」正需要这条线索。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FileLogger.w(TAG, "系统内存压力：$level（${trimLevelName(level)}）")
    }

    /** 把 ComponentCallbacks2 的内存压力等级翻译成可读名字，便于看日志。 */
    private fun trimLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "UNKNOWN"
    }

    override fun onDestroy() {
        // 解除挂起的文件选择回调，避免 Activity 销毁后页面一直等待
        releasePendingFileChooser()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        /** 日志标签，便于在导出日志里按模块筛选。 */
        const val TAG = "Main"

        /**
         * WebCrypto X25519 所需的最低 Chromium 主版本。
         *
         * 电脑端页面用它做远程连接握手，低于此版本的 WebView 会抛
         * "Algorithm: Unrecognized name"。仅用于诊断提示，App 无法替代页面实现该算法。
         */
        const val MIN_CHROMIUM_FOR_X25519 = 133

        /**
         * 承载 Reasonix 账号登录态的域。
         *
         * 「退出 Reasonix 账号」只清这些域的 Cookie，绝不碰已配对电脑端（局域网 Studio）
         * 的凭据，避免用户莫名其妙需要重新扫码。
         */
        val REASONIX_AUTH_ORIGINS = listOf(
            "https://reasonix.io",
            "https://id.reasonix.io",
            "https://studio.reasonix.io"
        )

        /**
         * 页面控制台消息的去重时间窗。
         *
         * 同一句页面错误在窗口内只记一条，超出窗口再记 —— 页面故障时同一句错误会在
         * 极短时间内刷几十条（实测 16ms 内 30+ 条），不去重会把 512KB 的日志瞬间灌满。
         * 3 秒足以把一次「连刷」归并为一条，又不会把前后两次独立故障混为一谈。
         */
        const val CONSOLE_DEDUPE_WINDOW_MS = 3_000L

        /**
         * 登录页自动填充脚本。
         *
         * 只在 `reasonix.io` 登录页注入。为什么不直接读原生凭据写进页面：那样密码会
         * 出现在这段脚本字符串里，而脚本本身会留在 WebView 的执行上下文中；改为让脚本
         * 通过 JS 桥 `ReasonixMobileNative.getLoginCredential()` 现取，桥那边已限定
         * 只有 reasonix.io 域能调用。
         *
         * 行为边界：只填值、只派发 input 事件，**不点击登录按钮、不提交表单** ——
         * 登录动作始终由用户完成（用户明确要求「只自动填充，登录由我点」）。
         */
        val LOGIN_AUTOFILL_SCRIPT = """
            (function () {
              var bridge = window.ReasonixMobileNative;
              var diag = function (m) {
                try { if (bridge && bridge.logLoginDiagnostic) bridge.logLoginDiagnostic(m); } catch (e) {}
              };

              // 整个脚本只在登录页运行；桥不存在时后续都无意义
              if (!bridge || typeof bridge.stageLoginCredential !== 'function') {
                return;
              }

              // ---------------------------------------------------------------
              // 第一步：填入已保存的凭据（与第二步完全隔离，各自 try/catch）。
              // 为什么必须隔离：第一步若抛异常，绝不能连累第二步把提交监听装上 ——
              // 曾经两步共用一个 try，结果「读凭据失败」直接导致「提交永远捕获不到」，
              // 表现为首次登录后凭据一直存不下来，且没有任何报错线索。
              // ---------------------------------------------------------------
              try {
                var raw = bridge.getLoginCredential();
                if (raw) {
                  var cred = null;
                  try { cred = JSON.parse(raw); } catch (e) { cred = null; }
                  if (cred && cred.email && cred.password) {
                    var fill = function (el, value) {
                      if (!el) return;
                      el.focus();
                      el.value = value;
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                      el.blur();
                    };
                    fill(document.getElementById('email'), cred.email);
                    fill(document.getElementById('password'), cred.password);
                    diag('已填入已保存的账号：' + cred.email.substring(0, 3) + '***');
                  } else {
                    diag('已有凭据记录但解析失败，跳过填充');
                  }
                } else {
                  diag('没有已保存的凭据，本次只装提交监听（首次登录属正常情况）');
                }
              } catch (e1) {
                diag('填充步骤异常（不影响提交捕获）：' + e1);
              }

              // ---------------------------------------------------------------
              // 第二步：无条件捕获登录表单的提交。
              //
              // 为什么监听 document 的捕获阶段，而不是 form 的 submit：
              // 页面脚本是 type="module"（延迟执行），且表单可能在脚本注入之后才被
              // 渲染或替换；绑在具体 form 元素上，只要注入时机早于表单存在就会漏掉。
              // document 级捕获监听不依赖表单何时出现 —— submit 事件一定会经过
              // document，且捕获阶段先于页面自己的监听执行，因此最稳。
              // ---------------------------------------------------------------
              try {
                if (window.__rxLoginCaptureInstalled) {
                  diag('提交监听已安装，跳过重复安装');
                  return;
                }
                window.__rxLoginCaptureInstalled = true;

                document.addEventListener('submit', function (ev) {
                  try {
                    var form = ev.target;
                    // 只认登录表单：注册/找回/重置等表单也走同一套事件
                    if (!form || form.id !== 'login-form') return;
                    var e = document.getElementById('email');
                    var p = document.getElementById('password');
                    if (e && p && e.value && p.value) {
                      bridge.stageLoginCredential(e.value, p.value);
                      diag('已捕获登录提交并回传凭据');
                    } else {
                      diag('捕获到提交，但邮箱或密码为空，未回传');
                    }
                  } catch (e2) {
                    diag('捕获提交时异常：' + e2);
                  }
                }, true);

                diag('提交监听已安装（document 捕获阶段）');
              } catch (e3) {
                diag('安装提交监听失败：' + e3);
              }
            })();
        """.trimIndent()

        /**
         * 桥自检脚本。
         *
         * 与登录页注入脚本（[LOGIN_AUTOFILL_SCRIPT]）互不影响：后者只在登录页、且开了自动
         * 填充时才注入，本脚本则每次整页加载完成都跑一遍。
         *
         * 关键点是**同时覆盖 `catch` 与返回值两条路径**：桥方法抛 `IllegalStateException`
         * 时，不同 WebView 版本的表现不一致 —— 有的让 JS 侧拿到 `undefined`，有的直接在
         * JS 侧抛异常。两条路径都打 console.error，失败才一定有线索可查。
         *
         * 脚本只回答「桥能不能被调用」这一个问题，成功与否都由日志判定，不用它的返回值
         * 做任何别的事。
         */
        val BRIDGE_SELF_CHECK_SCRIPT = """
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
        """.trimIndent()
    }
}
