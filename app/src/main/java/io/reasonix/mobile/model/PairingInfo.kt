package io.reasonix.mobile.model

/**
 * 扫码得到的配对信息。
 *
 * 实现说明：本类刻意不依赖 `android.net.Uri`，全部解析由纯 Kotlin 字符串处理完成。
 * 原先用 Uri 解析导致 `PairingInfo` 无法在 JVM 单元测试中验证（Uri 在本地单测里是
 * "not mocked" 直接抛异常），而配对地址的边界情况 —— scheme 大小写、缺省端口、
 * 畸形输入、Tailscale CGNAT 网段 —— 恰恰是最需要回归覆盖的部分。
 */
data class PairingInfo(
    val rawUrl: String,
    val isLocal: Boolean,
    val origin: String,
    val pairCode: String?,
    val token: String?,
    val deviceId: String?
) {
    companion object {
        private const val SCHEME_HTTP = "http"
        private const val SCHEME_HTTPS = "https"
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443

        /**
         * 配对凭据参数名。这两个参数只用于一次配对握手，不应长期留在本地存储或
         * WebView 历史里，因此 [sanitizedUrl] 会把它们剔除。
         */
        private val SENSITIVE_KEYS = setOf("pair", "token")

        /** URL 拆解结果，供解析与净化共用。 */
        private data class UrlParts(
            val scheme: String,
            val host: String,
            val port: Int,
            val path: String,
            val query: String,
            val fragment: String
        )

        /**
         * 判断主机是否属于局域网 / 私有网段，含 Tailscale CGNAT 段。
         * 该判定同时用于：
         * - 扫码结果标记（[isLocal]）；
         * - SSL 证书错误的放行决策 —— 只对私有网段的自签名证书例外放行。
         */
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase()
            return h == "127.0.0.1" ||
                    h == "localhost" ||
                    h.startsWith("192.168.") ||
                    h.startsWith("10.") ||
                    h.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*""")) ||
                    h.endsWith(".local") ||
                    h.matches(Regex("""^100\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\..*""")) // Tailscale CGNAT
        }

        /**
         * 取出 URL 的主机名（小写），解析失败返回 null。
         */
        fun hostOf(url: String): String? = splitUrl(url)?.host

        /**
         * 从完整 URL 中取出 `scheme://host:port` 形式的源，用于同源比较。
         * 解析失败返回 null。
         */
        fun originOf(url: String): String? {
            val parts = splitUrl(url) ?: return null
            return "${parts.scheme}://${parts.host}:${parts.port}"
        }

        /**
         * 去掉 URL 中的配对凭据（`pair` / `token`），保留地址、路径与其他参数。
         *
         * 用途：最近连接的地址会写入本地存储并在下次启动时重新加载，若原样保存带
         * `#pair=xxxx` 的地址，等于把一次性配对凭据明文留在设备上（并随备份外流）。
         * 解析失败时原样返回，避免因为净化反而破坏地址。
         */
        fun sanitizedUrl(url: String): String {
            val trimmed = url.trim()
            val parts = splitUrl(trimmed) ?: return trimmed
            val query = dropSensitiveParams(parts.query)
            val fragment = dropSensitiveParams(parts.fragment)

            val authority = if (parts.host.contains(':')) {
                "[${parts.host}]" // IPv6
            } else {
                parts.host
            }

            return buildString {
                append(parts.scheme).append("://").append(authority)
                if (parts.port != defaultPort(parts.scheme)) append(':').append(parts.port)
                append(parts.path)
                if (query.isNotEmpty()) append('?').append(query)
                if (fragment.isNotEmpty()) append('#').append(fragment)
            }
        }

        fun parse(qrContent: String): PairingInfo? {
            val parts = splitUrl(qrContent) ?: return null

            val fragmentParams = parseParams(parts.fragment)
            val queryParams = parseParams(parts.query)

            // fragment 优先，兼容部分格式把参数放在 query 中
            val pairCode = fragmentParams["pair"] ?: queryParams["pair"]
            val token = fragmentParams["token"] ?: queryParams["token"]
            val deviceId = queryParams["device"]

            return PairingInfo(
                rawUrl = qrContent.trim(),
                isLocal = isPrivateHost(parts.host),
                origin = "${parts.scheme}://${parts.host}:${parts.port}",
                pairCode = pairCode,
                token = token,
                deviceId = deviceId
            )
        }

        /**
         * 把 URL 拆成 scheme / host / port / path / query / fragment。
         * 仅接受 http 与 https（大小写不敏感），其余或畸形输入返回 null。
         */
        private fun splitUrl(raw: String): UrlParts? {
            val trimmed = raw.trim()
            val lower = trimmed.lowercase()
            val scheme = when {
                lower.startsWith("$SCHEME_HTTP://") -> SCHEME_HTTP
                lower.startsWith("$SCHEME_HTTPS://") -> SCHEME_HTTPS
                else -> return null
            }

            val afterScheme = trimmed.substring(scheme.length + 3)

            val hashIndex = afterScheme.indexOf('#')
            val beforeFragment =
                if (hashIndex >= 0) afterScheme.substring(0, hashIndex) else afterScheme
            val fragment = if (hashIndex >= 0) afterScheme.substring(hashIndex + 1) else ""

            val queryIndex = beforeFragment.indexOf('?')
            val authorityAndPath =
                if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
            val query = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else ""

            val slashIndex = authorityAndPath.indexOf('/')
            val authority =
                if (slashIndex >= 0) authorityAndPath.substring(0, slashIndex) else authorityAndPath
            val path = if (slashIndex >= 0) authorityAndPath.substring(slashIndex) else ""
            if (authority.isEmpty()) return null

            val (host, port) = splitAuthority(authority, scheme) ?: return null
            if (host.isEmpty()) return null

            return UrlParts(scheme, host.lowercase(), port, path, query, fragment)
        }

        /** 从 authority 中拆出 host 与 port，处理 IPv6 的 `[::1]:8080` 写法。 */
        private fun splitAuthority(authority: String, scheme: String): Pair<String, Int>? {
            if (authority.isEmpty()) return null

            if (authority.startsWith("[")) {
                // IPv6 字面量：[::1] 或 [::1]:8080
                val close = authority.indexOf(']')
                if (close < 0) return null
                val host = authority.substring(1, close)
                val rest = authority.substring(close + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort(scheme)
                    rest.startsWith(":") -> parsePort(rest.substring(1))
                    else -> null
                }
                return if (port == null || host.isEmpty()) null else host to port
            }

            val colon = authority.lastIndexOf(':')
            val host = if (colon >= 0) authority.substring(0, colon) else authority
            val port = if (colon >= 0) parsePort(authority.substring(colon + 1)) else defaultPort(scheme)
            return if (port == null) null else host to port
        }

        private fun parsePort(text: String): Int? {
            val port = text.toIntOrNull() ?: return null
            return if (port in 1..65535) port else null
        }

        /**
         * 取出 `a=1&b=2` 形式的参数，键统一小写；同名参数保留最先出现的那个。
         */
        private fun parseParams(segment: String): Map<String, String> {
            if (segment.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (chunk in segment.split('&')) {
                if (chunk.isEmpty()) continue
                val eq = chunk.indexOf('=')
                if (eq <= 0) continue
                val key = chunk.substring(0, eq).lowercase()
                val value = chunk.substring(eq + 1)
                result.putIfAbsent(key, value)
            }
            return result
        }

        /** 删除 [SENSITIVE_KEYS] 中的参数，其余保持原顺序。 */
        private fun dropSensitiveParams(segment: String): String {
            if (segment.isEmpty()) return ""
            return segment.split('&')
                .filter { chunk ->
                    if (chunk.isEmpty()) return@filter false
                    val eq = chunk.indexOf('=')
                    val key = if (eq > 0) chunk.substring(0, eq) else chunk
                    key.lowercase() !in SENSITIVE_KEYS
                }
                .joinToString("&")
        }

        private fun defaultPort(scheme: String): Int =
            if (scheme == SCHEME_HTTPS) DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
    }
}
