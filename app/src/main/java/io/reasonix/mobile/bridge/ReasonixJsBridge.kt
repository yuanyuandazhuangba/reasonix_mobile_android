package io.reasonix.mobile.bridge

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.widget.Toast

class ReasonixJsBridge(
    private val context: Context,
    private val onDisconnectListener: (() -> Unit)? = null,
    private val onNewScanListener: (() -> Unit)? = null
) {

    @JavascriptInterface
    fun getClientType(): String {
        return "ReasonixAndroidApp"
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return "1.0.0"
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
        onNewScanListener?.invoke()
    }

    @JavascriptInterface
    fun requestDisconnect() {
        onDisconnectListener?.invoke()
    }
}
