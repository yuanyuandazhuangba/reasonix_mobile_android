package io.reasonix.mobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
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
import io.reasonix.mobile.storage.PreferenceManager
import io.reasonix.mobile.storage.SavedDevice
import java.io.File
import java.io.IOException

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
        // 取消时也必须回调 null，否则页面选择框会永远卡住
        callback.onReceiveValue(uris)
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
            loadStudioUrl(savedUrl)
        } else {
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

            // 注入原生 JS 桥接：破坏性操作（断开/重扫码）会校验调用方是不是已配对的页面
            addJavascriptInterface(
                ReasonixJsBridge(
                    context = this@MainActivity,
                    webViewProvider = { this@MainActivity.webView },
                    pairedOriginProvider = {
                        preferenceManager.lastConnectedOrigin
                            ?: preferenceManager.lastConnectedUrl
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

                    val intent = buildFileChooserIntent(fileChooserParams)
                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        this@MainActivity.filePathCallback = null
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
                    binding.progressBar.visibility = View.VISIBLE
                    binding.layoutError.visibility = View.GONE
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
                        rememberCurrentUrl(PairingInfo.sanitizedUrl(url))
                        stripCredentialsFromHistory(view ?: webView)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
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
                        return false
                    }
                    rendererRecovered = true
                    val targetUrl = preferenceManager.lastConnectedUrl

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
                    return true
                }
            }
        }
    }

    /**
     * 旧的 WebView 已被系统判定渲染进程崩溃，必须销毁；随后在 content_root 中
     * 重新创建一个 WebView 并重新初始化，用于渲染进程退出后的自恢复。
     */
    private fun rebuildWebView() {
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
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
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
     * 页面加载完成后剥离地址与历史记录中的配对凭据。
     *
     * 页面脚本会把 `#pair=xxx` 兑换为长效 Cookie，之后地址中继续保留凭据就只剩泄漏
     * 价值 —— WebView 历史、页面截图、崩溃日志都会带上它。这里用
     * `history.replaceState` 就地改写当前历史项（不触发页面重载），参数处理与
     * [PairingInfo.sanitizedUrl] 保持一致。
     *
     * 回退：该行为依赖「页面确实已自行兑换凭据」。若电脑端前端改回每次都从 URL 读取
     * 凭据，剥离会导致重连需要重新扫码，把 [ENABLE_CREDENTIAL_HISTORY_STRIPPING]
     * 置为 false 即可关闭，不影响其他逻辑。
     */
    private fun stripCredentialsFromHistory(target: WebView) {
        if (!ENABLE_CREDENTIAL_HISTORY_STRIPPING) return
        val current = target.url ?: return
        if (PairingInfo.sanitizedUrl(current) == current) return

        target.evaluateJavascript(CREDENTIAL_STRIPPING_SCRIPT, null)
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
                webView.clearCache(true)
                webView.clearHistory()
                webView.loadUrl("about:blank")
                showEmptyState(true)
                Toast.makeText(this, R.string.device_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
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

    override fun onResume() {
        super.onResume()
        // 回到前台时恢复 WebView 定时器，让长连接心跳与页面轮询立即恢复
        webView.resumeTimers()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // 刻意不调用 webView.onPause()：避免暂停 JS 定时器而切断与电脑端的心跳，
        // 这是手机端切到后台后频繁掉线的常见原因。
    }

    override fun onStop() {
        super.onStop()
        // 退到后台是「可能被杀掉」的时机，在这里保证 Cookie 已落盘一次即可，
        // 从而不必在每次页面加载时同步 flush()
        persistCookies()
    }

    override fun onDestroy() {
        // 解除挂起的文件选择回调，避免 Activity 销毁后页面一直等待
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        /**
         * 是否在页面加载完成后剥离地址与历史中的配对凭据。
         *
         * 依赖「电脑端页面脚本已自行把 `#pair=xxx` 兑换为长效 Cookie」。若电脑端改回
         * 每次都从 URL 读取凭据，剥离会导致重连需要重新扫码，把此值改为 false 即可
         * 关闭该行为，其余逻辑不受影响。
         */
        const val ENABLE_CREDENTIAL_HISTORY_STRIPPING = true

        /**
         * 就地移除当前历史项中的 `pair` / `token` 参数（query 与 hash 各处理一次）。
         * 用 `history.replaceState` 改写而非跳转，因此不会触发页面重新加载。
         */
        val CREDENTIAL_STRIPPING_SCRIPT = """
            (function () {
              try {
                var sensitive = ['pair', 'token'];
                var url = new URL(location.href);
                var changed = false;

                sensitive.forEach(function (key) {
                  if (url.searchParams.has(key)) {
                    url.searchParams.delete(key);
                    changed = true;
                  }
                });

                if (url.hash && url.hash.length > 1) {
                  var raw = url.hash.substring(1).split('&');
                  var kept = raw.filter(function (chunk) {
                    if (!chunk) return false;
                    var name = chunk.split('=')[0].toLowerCase();
                    return sensitive.indexOf(name) === -1;
                  });
                  if (kept.length !== raw.length) {
                    url.hash = kept.length ? '#' + kept.join('&') : '';
                    changed = true;
                  }
                }

                if (changed) history.replaceState(null, '', url.href);
              } catch (e) {
                /* 老版本 WebView 不支持 URL API 时静默跳过，不影响页面功能 */
              }
            })();
        """.trimIndent()
    }
}
