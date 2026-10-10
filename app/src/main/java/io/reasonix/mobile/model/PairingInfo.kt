package io.reasonix.mobile.model

import android.net.Uri

data class PairingInfo(
    val rawUrl: String,
    val isLocal: Boolean,
    val origin: String,
    val pairCode: String?,
    val token: String?,
    val deviceId: String?
) {
    companion object {
        fun parse(qrContent: String): PairingInfo? {
            val trimmed = qrContent.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }

            try {
                val uri = Uri.parse(trimmed)
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
                val origin = "${uri.scheme}://$host:$port"

                // 解析 fragment 中的 hash 参数，如 #pair=xxxx 或 #token=xxxx
                val fragment = uri.fragment ?: ""
                var pairCode: String? = null
                var token: String? = null

                if (fragment.isNotEmpty()) {
                    val pairs = fragment.split("&")
                    for (p in pairs) {
                        val kv = p.split("=", limit = 2)
                        if (kv.size == 2) {
                            when (kv[0].lowercase()) {
                                "pair" -> pairCode = kv[1]
                                "token" -> token = kv[1]
                            }
                        }
                    }
                }

                // 兼容有些格式把参数放在 query 中
                if (pairCode == null) pairCode = uri.getQueryParameter("pair")
                if (token == null) token = uri.getQueryParameter("token")
                val deviceId = uri.getQueryParameter("device")

                val isLocal = host == "127.0.0.1" ||
                        host == "localhost" ||
                        host.startsWith("192.168.") ||
                        host.startsWith("10.") ||
                        host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*""")) ||
                        host.endsWith(".local") ||
                        host.matches(Regex("""^100\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\..*""")) // Tailscale CGNAT

                return PairingInfo(
                    rawUrl = trimmed,
                    isLocal = isLocal,
                    origin = origin,
                    pairCode = pairCode,
                    token = token,
                    deviceId = deviceId
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}
