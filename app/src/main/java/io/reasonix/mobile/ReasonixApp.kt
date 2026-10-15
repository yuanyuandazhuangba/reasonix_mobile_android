package io.reasonix.mobile

import android.app.Application
import io.reasonix.mobile.util.FileLogger

/**
 * 应用入口。在此初始化文件日志 —— 手机端一旦出问题，没有日志就只能靠电脑端反推，
 * 且 USB 未连接时连 logcat 都拿不到（排查「上传时连接断开」时即卡在此处）。
 */
class ReasonixApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.i(TAG, "App 启动，版本 ${BuildConfig.VERSION_NAME}")
    }

    private companion object {
        const val TAG = "App"
    }
}
