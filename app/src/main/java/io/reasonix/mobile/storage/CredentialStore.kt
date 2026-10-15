package io.reasonix.mobile.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 用户主动要求保存的 Reasonix 账号凭据，仅用于登录页自动填充。 */
data class LoginCredential(val email: String, val password: String)

/**
 * 账号密码的加密存储。
 *
 * 背景：手机端首次配对后会跳到 `reasonix.io/login/` 要求输入 Reasonix 账号密码；
 * 隔几天再用时设备配对仍在（免扫码），但这个账号会话会失效，需要重新输入。用户要求
 * 由 App 记住账号密码并在登录页自动填入（只填充、不代替点击登录）。
 *
 * 安全设计：
 * - 密文用 Android Keystore 里的 AES/GCM 密钥加密，密钥不出安全硬件；
 * - 明文只以「加密前的字节」在内存里短暂存在，磁盘上永远是密文；
 * - GCM 自带完整性校验，密文被篡改会解密失败而不是解出错误内容；
 * - 解密失败（例如恢复了出厂设置、或 Keystore 中的密钥被系统清除）时返回 null，
 *   由调用方引导用户重新登录，绝不抛异常影响页面。
 *
 * 隐私：调用方不得把 password 写进任何日志。
 */
object CredentialStore {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "reasonix_login_credential_v1"
    private const val PREFS_NAME = "reasonix_credential"
    private const val PREF_KEY = "payload"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val gson = Gson()

    private data class Payload(val email: String, val password: String)

    /** 保存（覆盖）账号密码。任一项为空则拒绝，避免把空值当成有效凭据。 */
    fun save(context: Context, email: String, password: String): Boolean {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || password.isEmpty()) return false

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val body = gson.toJson(Payload(trimmedEmail, password)).toByteArray(Charsets.UTF_8)
            val sealed = cipher.doFinal(body)
            // 格式：base64(iv) + ":" + base64(密文)。IV 随每次加密变化，无需保密。
            val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                    ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
            prefs(context).edit().putString(PREF_KEY, encoded).apply()
            true
        } catch (_: Exception) {
            // Keystore 不可用或磁盘异常：保存失败但绝不让调用方崩溃
            false
        }
    }

    fun load(context: Context): LoginCredential? {
        val encoded = prefs(context).getString(PREF_KEY, null) ?: return null
        return try {
            val separator = encoded.indexOf(':')
            if (separator <= 0) return null
            val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
            val sealed = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val payload = gson.fromJson(
                String(cipher.doFinal(sealed), Charsets.UTF_8),
                Payload::class.java
            )
            if (payload == null || payload.email.isBlank() || payload.password.isEmpty()) {
                null
            } else {
                LoginCredential(payload.email, payload.password)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hasCredential(context: Context): Boolean =
        !prefs(context).getString(PREF_KEY, null).isNullOrEmpty()

    /**
     * 仅清除已保存的账号密码，保留设备配对与历史设备列表。
     * 用户不希望 App 再代为保管账号时使用。
     */
    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_KEY).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 取得（必要时创建）Keystore 中的 AES 密钥。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 用户选择「仅加密、填充时不再验证」：不要求每次使用都做生物识别
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
