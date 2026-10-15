package io.reasonix.mobile.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 已配对过的电脑端记录。多设备场景下用于在“设备与连接操作”里快速切回，
 * 不必每次都重新扫码。
 */
data class SavedDevice(
    val id: String,
    val name: String,
    val origin: String,
    val lastConnected: Long
)

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reasonix_prefs", Context.MODE_PRIVATE)

    private val gson = Gson()

    companion object {
        private const val KEY_LAST_URL = "last_connected_url"
        private const val KEY_LAST_ORIGIN = "last_connected_origin"
        private const val KEY_SAVED_DEVICES = "saved_devices"
        private const val KEY_FAB_SIDE = "fab_side"
        private const val KEY_FAB_VERTICAL_RATIO = "fab_vertical_ratio"
        private const val KEY_AUTOFILL_CREDENTIALS = "autofill_credentials"

        /** 最多记住的历史设备数，避免偏好文件无限增长。 */
        private const val MAX_SAVED_DEVICES = 10

        /** 悬浮菜单按钮吸附在屏幕左侧 */
        const val FAB_SIDE_LEFT = 0

        /** 悬浮菜单按钮吸附在屏幕右侧（默认） */
        const val FAB_SIDE_RIGHT = 1
    }

    var lastConnectedUrl: String?
        get() = prefs.getString(KEY_LAST_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_URL, value).apply()

    var lastConnectedOrigin: String?
        get() = prefs.getString(KEY_LAST_ORIGIN, null)
        set(value) = prefs.edit().putString(KEY_LAST_ORIGIN, value).apply()

    /**
     * 已连接过的设备列表，按最近连接时间倒序持久化为 JSON。
     * 数据损坏时退回空列表，不因为历史记录解析失败而影响连接。
     */
    var savedDevices: List<SavedDevice>
        get() {
            val json = prefs.getString(KEY_SAVED_DEVICES, null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<SavedDevice>>() {}.type
                gson.fromJson<List<SavedDevice>>(json, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            val trimmed = value
                .sortedByDescending { it.lastConnected }
                .take(MAX_SAVED_DEVICES)
            prefs.edit().putString(KEY_SAVED_DEVICES, gson.toJson(trimmed)).apply()
        }

    /**
     * 记录（或更新）一台设备为最近连接。以 origin 作为唯一标识，
     * 同一台电脑重复扫码只更新时间与名称，不会堆积重复项。
     */
    fun rememberDevice(origin: String, name: String?) {
        val displayName = name?.takeIf { it.isNotBlank() } ?: origin
        val updated = savedDevices
            .filterNot { it.origin == origin }
            .plus(
                SavedDevice(
                    id = origin,
                    name = displayName,
                    origin = origin,
                    lastConnected = System.currentTimeMillis()
                )
            )
        savedDevices = updated
    }

    /**
     * 是否启用「账号密码自动填充」。
     *
     * 默认**关闭**，由用户在悬浮菜单里按需开启。原因：Reasonix 的登录态由网站 Cookie
     * 自行保持，正常情况下根本不会走到登录页，这个功能只在「登录态过期、需要重新输入」
     * 时省事；既然触发频率很低，就没必要默认替用户保管账号密码。
     *
     * 关闭时 App 完全不接触账号密码：不注入填充脚本、不捕获表单提交、不留存凭据。
     */
    var autoFillCredentials: Boolean
        get() = prefs.getBoolean(KEY_AUTOFILL_CREDENTIALS, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOFILL_CREDENTIALS, value).apply()

    /**
     * 悬浮菜单按钮吸附的侧别，取值 [FAB_SIDE_LEFT] 或 [FAB_SIDE_RIGHT]。
     * 右下角原本与电脑端页面的提示词发送按钮重叠，允许用户拖开后记住位置。
     */
    var fabSide: Int
        get() = prefs.getInt(KEY_FAB_SIDE, FAB_SIDE_RIGHT)
        set(value) = prefs.edit().putInt(KEY_FAB_SIDE, value).apply()

    /**
     * 悬浮菜单按钮垂直位置，以可用高度（状态栏之下、导航栏之上）的比例存储，
     * 而非绝对像素，这样换机、旋转屏幕、系统栏高度变化后位置依然合理。
     */
    var fabVerticalRatio: Float
        get() = prefs.getFloat(KEY_FAB_VERTICAL_RATIO, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_FAB_VERTICAL_RATIO, value.coerceIn(0f, 1f)).apply()

    /**
     * 仅清除当前连接（地址与已配对源），保留历史设备列表。
     * "断开当前连接"用它，这样多设备用户断开后仍能从列表切回别的电脑。
     */
    fun clearCurrentConnection() {
        prefs.edit()
            .remove(KEY_LAST_URL)
            .remove(KEY_LAST_ORIGIN)
            .apply()
    }

    /**
     * 清空全部数据，含历史设备列表与悬浮按钮位置。
     * 仅在需要彻底重置时使用。
     */
    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
