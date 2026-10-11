package io.reasonix.mobile.bridge

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import io.reasonix.mobile.BuildConfig
import io.reasonix.mobile.model.PairingInfo

/**
 * 原生与网页之间的桥接，注册名为 `ReasonixMobileNative`。
 *
 * 安全约束：桥对页面脚本完全可见，任何被加载的页面都能调用其方法。因此
 * [requestDisconnect]（会清除配对凭据）与 [requestNewScan]（会拉起相机）这类破坏性
 * 操作，必须先确认调用方就是已配对的电脑端页面，否则静默忽略 —— 否则一段被注入的
 * 脚本就能悄悄把用户的配对会话清掉，或诱导线起相机界面。
 *
 * 无副作用的方法（[getClientType] / [getAppVersion] / [vibrate] / [showToast]）保持开放，
 * 以免影响页面既有的探测与提示逻辑。
 */
class ReasonixJsBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
    private val pairedOriginProvider: () -> String?,
    private val onDisconnectListener: (() -> Unit)? = null,
    private val onNewScanListener: (() -> Unit)? = null
) {

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
     * 判断本次调用是否来自已配对的电脑端页面：当前页面地址与记录中已配对的源
     * （`scheme://host:port`）完全一致时才放行。
     */
    private fun isTrustedCaller(): Boolean {
        val currentUrl = webViewProvider()?.url ?: return false
        val pairedOrigin = pairedOriginProvider() ?: return false
        val currentOrigin = PairingInfo.originOf(currentUrl) ?: return false
        val expected = PairingInfo.originOf(pairedOrigin) ?: return false
        return currentOrigin == expected
    }
}
