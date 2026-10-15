package io.reasonix.mobile.util

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 极简文件日志。
 *
 * 为什么需要它：本应用此前没有任何日志输出（全仓库零 `Log.*` 调用），手机端一旦出问题
 * 只能在电脑端日志里反推，而 USB 未连接时连 logcat 都拿不到 —— 排查「上传时连接断开」
 * 时就卡在这一步。此日志写入应用私有目录，可经 FAB 菜单导出，不依赖 USB 调试。
 *
 * 设计取舍：
 * - 写盘在单线程后台执行，绝不阻塞主线程或 Android 生命周期回调；
 * - 单文件超过上限就滚动一次（保留一个备份），避免长期使用把磁盘写满；
 * - 任何异常都吞掉：日志本身绝不能成为崩溃源，最多是丢一条记录。
 *
 * 隐私：调用方必须传入已脱敏的文本。URL 一律经 `PairingInfo.sanitizedUrl` 处理后再记录，
 * 配对凭据（pair/token）不允许出现在日志里。
 */
object FileLogger {

    private const val LOG_DIR = "logs"
    private const val LOG_NAME = "reasonix.log"
    private const val BACKUP_NAME = "reasonix.log.1"

    /** 单文件上限，超过即滚动。够装下数天的诊断信息，又不会明显占用存储。 */
    private const val MAX_BYTES = 512 * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reasonix-file-logger").apply { isDaemon = true }
    }

    @Volatile
    private var logFile: File? = null

    /** 在 Application.onCreate 调用一次。重复调用无害。 */
    fun init(context: Context) {
        if (logFile != null) return
        val dir = File(context.applicationContext.filesDir, LOG_DIR)
        logFile = File(dir, LOG_NAME)
    }

    /** 供导出使用；未初始化时返回 null。 */
    fun currentFile(): File? = logFile?.takeIf { it.exists() }

    /**
     * 等待队列中已提交的日志全部落盘。
     *
     * 导出前必须调用：写盘是异步的，否则刚发生的关键事件（比如渲染进程被回收）
     * 可能还在队列里，导出的文件会缺掉最该看的那几行。
     */
    fun flush(timeoutMillis: Long = 1500) {
        try {
            executor.submit { }.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // 超时或执行器已关闭：已落盘的部分仍然可用，不因此失败
        }
    }

    fun i(tag: String, message: String) = write("INFO", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        write("WARN", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        write("ERROR", tag, message, error)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val file = logFile ?: return
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ').append(level)
            append(' ').append(tag)
            append(": ").append(message)
            if (error != null) {
                append(" | ").append(error.javaClass.simpleName)
                error.message?.let { append(": ").append(it) }
            }
            append('\n')
        }

        try {
            executor.execute {
                try {
                    file.parentFile?.mkdirs()
                    if (file.length() > MAX_BYTES) {
                        rollover(file)
                    }
                    file.appendText(line)
                } catch (_: IOException) {
                    // 磁盘满或权限异常：放弃这条，绝不向上抛
                }
            }
        } catch (_: RuntimeException) {
            // 执行器已关闭（进程退出中）等：忽略
        }
    }

    /** 滚动一次：旧日志改名备份，原文件清空重写。只保留一份备份。 */
    private fun rollover(file: File) {
        val backup = File(file.parentFile, BACKUP_NAME)
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    /** 进程退出前尽力把队列里的日志刷完，主要用于测试与调试。 */
    fun shutdown() {
        try {
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
