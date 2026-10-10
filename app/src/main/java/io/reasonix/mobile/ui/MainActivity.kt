package io.reasonix.mobile.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import io.reasonix.mobile.R
import io.reasonix.mobile.bridge.ReasonixJsBridge
import io.reasonix.mobile.databinding.ActivityMainBinding
import io.reasonix.mobile.model.PairingInfo
import io.reasonix.mobile.storage.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var webView: WebView
    private var lastBackPressTime = 0L

    /** 渲染进程被系统回收（低内存等）后只自动恢复一次，避免反复重建形成死循环。 */
    private var rendererRecovered = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        webView = binding.webView

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

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(target: WebView) {
        target.apply {
            // 强制硬件加速渲染，提升滚动与页面交互流畅度
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
                displayZoomControls = false
                builtInZoomControls = false
                setSupportZoom(false)

                // 允许 HTTP 和 HTTPS 混合模式与局域网资源加载
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                allowContentAccess = true

                // 保持长连接心跳所需的脚本执行能力
                loadsImagesAutomatically = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }

                // 自定义 UserAgent 标识为 Reasonix Mobile Client
                val defaultUa = userAgentString
                userAgentString = "$defaultUa ReasonixMobile/1.0"
            }

            // 保持会话与缓存，减少每次重连的握手开销
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(target, true)
            }

            // 注入原生 JS 桥接
            addJavascriptInterface(
                ReasonixJsBridge(
                    context = this@MainActivity,
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
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.progressBar.visibility = View.VISIBLE
                    binding.layoutError.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    rendererRecovered = false

                    // 同步 Cookie 确保认证状态持久化
                    CookieManager.getInstance().flush()

                    if (url != null && !url.startsWith("about:blank")) {
                        showEmptyState(false)
                        preferenceManager.lastConnectedUrl = url
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

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // 自签名局域网证书或内网证书允许通过
                    handler?.proceed()
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

    private fun setupUIEvents() {
        // 空状态扫码按钮
        binding.btnEmptyScan.setOnClickListener {
            openScanActivity()
        }

        // 浮动快捷操作栏
        binding.fabMenu.setOnClickListener {
            showActionDialog()
        }

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

    fun openScanActivity() {
        val intent = Intent(this, ScanActivity::class.java)
        scanQrLauncher.launch(intent)
    }

    private fun connectToUrl(url: String) {
        val pairing = PairingInfo.parse(url)
        if (pairing != null) {
            preferenceManager.lastConnectedUrl = url
            preferenceManager.lastConnectedOrigin = pairing.origin
        }
        loadStudioUrl(url)
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
        val items = arrayOf(
            getString(R.string.menu_rescan),
            getString(R.string.menu_refresh),
            getString(R.string.menu_disconnect)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_device_options)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openScanActivity()
                    1 -> webView.reload()
                    2 -> disconnectCurrentDevice()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun disconnectCurrentDevice() {
        preferenceManager.clearSession()
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

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
