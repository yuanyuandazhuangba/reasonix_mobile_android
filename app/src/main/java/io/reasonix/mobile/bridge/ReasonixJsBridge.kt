package io.reasonix.mobile.bridge

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.widget.Toast
import io.reasonix.mobile.BuildConfig
import io.reasonix.mobile.model.PairingInfo
import io.reasonix.mobile.util.FileLogger

/**
 * 原生与网页之间的桥接，注册名为 `ReasonixMobileNative`。
 *
 * 安全约束：桥对页面脚本完全可见，任何被加载的页面都能调用其方法。因此
 * [requestDisconnect]（会清除配对凭据）与 [requestNewScan]（会拉起相机）这类破坏性
 * 操作，必须先确认调用方就是已配对的电脑端页面，否则静默忽略 —— 否则一段被注入的
 * 脚本就能悄悄把用户的配对会话清掉，或诱导线起相机界面。
 *
 * 账号密码相关方法（[getLoginCredential] / [stageLoginCredential]）另有更严格的限制：
 * 只对 `reasonix.io` 域生效，因为登录页位于该外部域，而凭据绝不能被其他页面取走。
 *
 * **线程安全（重要）**：`@JavascriptInterface` 暴露的方法由 WebView 在其**私有后台
 * 线程**上调用，而 WebView 的所有方法都必须在创建它的 UI 线程上调用 ——
 * `WebViewChromium.getUrl()` 会先 `checkThread()` 并**无条件抛**
 * `IllegalStateException`。因此本类**绝不能持有或访问 WebView 实例**，页面地址一律
 * 通过 [currentUrlProvider] 从 UI 线程维护好的缓存里读。曾经因为直接读 `webView.url`，
 * 桥上每个方法一进去就抛异常（且被 WebView 静默吞掉），导致功能全不生效却毫无日志线索。
 *
 * 无副作用的方法（[getClientType] / [getAppVersion] / [vibrate] / [showToast] /
 * [bridgeSelfCheck]）保持开放，以免影响页面既有的探测与提示逻辑；其中 [bridgeSelfCheck]
 * 刻意不校验来源，理由见其注释。
 */
class ReasonixJsBridge(
    private val context: Context,
    /** 由 UI 线程维护的当前页面地址缓存；**不要**在这类回调里访问 WebView。 */
    private val currentUrlProvider: () -> String?,
    private val pairedOriginProvider: () -> String?,
    private val loginCredentialProvider: (() -> String?)? = null,
    private val onLoginCredentialStaged: ((String, String) -> Unit)? = null,
    private val onDisconnectListener: (() -> Unit)? = null,
    private val onNewScanListener: (() -> Unit)? = null
) {

    private companion object {
        /**
         * 登录页注入脚本回传的诊断日志标签。
         *
         * 单独一个标签，便于在导出日志里直接筛出「注入是否成功、卡在哪一步」，
         * 不必在成百条页面报错里翻找。
         */
        const val LOGIN_DIAG_TAG = "LoginDiag"

        /**
         * 桥自检回传的日志标签。
         *
         * 与 [LOGIN_DIAG_TAG] 同样单独成标签：前者只在进入登录页、且开启了自动填充时才
         * 产生，而「桥能不能用」在别的页面同样需要判定，因此给自检一个独立标签，
         * 导出日志里可直接筛出结论。
         */
        const val SELF_CHECK_TAG = "BridgeCheck"
    }

    @JavascriptInterface
    fun getClientType(): String {
        return "ReasonixAndroidApp"
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        // 取自构建配置而非硬编码，使页面上显示的版本与 APK 实际版本一致
        return BuildConfig.VERSION_NAME
    }

    @JavascriptInterface
    fun vibrate(milliseconds: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        milliseconds.coerceAtLeast(10),
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(milliseconds.coerceAtLeast(10))
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    fun requestNewScan() {
        if (!isTrustedCaller()) return
        onNewScanListener?.invoke()
    }

    @JavascriptInterface
    fun requestDisconnect() {
        if (!isTrustedCaller()) return
        onDisconnectListener?.invoke()
    }

    /**
     * 取回已保存的账号密码，供 `reasonix.io` 登录页自动填充。
     *
     * 返回 JSON 字符串（`{"email":"..","password":".."}`），没有凭据时返回空串。
     * 只对登录域放行：该桥暴露给所有被加载的页面，若不校验域名，任何一个页面（包括
     * 被劫持的局域网页面）都能直接读走用户的账号密码。
     */
    @JavascriptInterface
    fun getLoginCredential(): String {
        if (!isLoginDomain()) return ""
        return loginCredentialProvider?.invoke() ?: ""
    }

    /**
     * 用户在登录页手动登录成功后，页面把凭据交回原生保存。
     *
     * 只在登录域接受提交，且必须在登录成功后由页面主动调用 —— App 不去猜测凭据，
     * 这样保存的一定是真正被服务端接受过的那一份。
     */
    @JavascriptInterface
    fun stageLoginCredential(email: String, password: String) {
        if (!isLoginDomain()) return
        if (email.isBlank() || password.isEmpty()) return
        onLoginCredentialStaged?.invoke(email, password)
    }

    /**
     * 登录页注入脚本回传执行状态，仅用于诊断。
     *
     * 为什么需要：注入脚本运行在页面上下文里，它是否注入成功、表单是否找到、提交是否
     * 捕获，原生一概看不到 —— 曾经因此把「凭据从未保存」误判为「保存逻辑写错」，只能
     * 靠反复发版试错。让脚本把每一步报告回来，下次导出日志即可直接定位卡在哪一步。
     *
     * 仅对登录域放行，避免其他页面往日志里灌内容。
     */
    @JavascriptInterface
    fun logLoginDiagnostic(message: String) {
        if (!isLoginDomain()) return
        FileLogger.i(LOGIN_DIAG_TAG, message.take(200))
    }

    /**
     * 桥自检：证明 `@JavascriptInterface` 方法能被正常调用。
     *
     * 为什么需要：桥方法运行在 WebView 的**私有后台线程**上，若在方法体里碰 WebView 会抛
     * `IllegalStateException` 并被 WebView 静默吞掉 —— 曾导致所有桥功能失效却毫无日志线索
     * （详见类注释）。本方法只回传自身可被调用的证据，不含任何敏感信息，让「桥是否可用」
     * 在任何页面都能被验证，而不必依赖登录页注入或自动填充开关。
     *
     * 故意**不做同源/域校验**：否则校验逻辑自身出问题时，自检也会跟着失灵，就无法区分
     * 「桥不可用」与「非同源」这两种完全不同的故障。
     */
    @JavascriptInterface
    fun bridgeSelfCheck(): String {
        FileLogger.i(SELF_CHECK_TAG, "桥自检成功（@JavascriptInterface 调用正常）")
        return "ok"
    }

    /**
     * 判断本次调用是否来自 Reasonix 登录域。
     *
     * 登录页位于外部域 `reasonix.io`（及其身份服务 `id.reasonix.io`），与已配对的
     * 局域网 Studio 不是同一个源，所以这里不能用 [isTrustedCaller]，而要专门比对域名。
     */
    private fun isLoginDomain(): Boolean {
        val currentUrl = currentUrlProvider() ?: return false
        val host = PairingInfo.hostOf(currentUrl) ?: return false
        return host == "reasonix.io" || host.endsWith(".reasonix.io")
    }

    /**
     * 判断本次调用是否来自已配对的电脑端页面：当前页面地址与记录中已配对的源
     * （`scheme://host:port`）完全一致时才放行。
     */
    private fun isTrustedCaller(): Boolean {
        val currentUrl = currentUrlProvider() ?: return false
        val pairedOrigin = pairedOriginProvider() ?: return false
        val currentOrigin = PairingInfo.originOf(currentUrl) ?: return false
        val expected = PairingInfo.originOf(pairedOrigin) ?: return false
        return currentOrigin == expected
    }
}
