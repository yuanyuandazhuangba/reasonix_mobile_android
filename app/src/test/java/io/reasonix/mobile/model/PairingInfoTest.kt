package io.reasonix.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PairingInfo] 解析逻辑的单元测试。
 *
 * 这些用例之所以能在 JVM 上直接跑，是因为 [PairingInfo] 已重构为纯 Kotlin 字符串
 * 解析、不再依赖 `android.net.Uri`（Uri 在本地单测中会抛 "not mocked"）。覆盖重点
 * 是配对地址的边界：fragment 与 query 两种传参风格、缺省端口、私有网段与 Tailscale
 * CGNAT 的判定边界，以及各类畸形输入。
 */
class PairingInfoTest {

    // ---------- 基本解析：fragment 形式 ----------

    @Test
    fun `fragment 中的 pair 参数被解析`() {
        val info = PairingInfo.parse("http://192.168.1.5:8080/#pair=abc123")

        assertEquals("abc123", info?.pairCode)
        assertNull(info?.token)
        assertEquals("http://192.168.1.5:8080", info?.origin)
        assertTrue(info?.isLocal == true)
    }

    @Test
    fun `fragment 中的 token 参数被解析`() {
        val info = PairingInfo.parse("https://studio.example.com/#token=t1")

        assertEquals("t1", info?.token)
        assertNull(info?.pairCode)
        // https 未写端口时按 443 归一化
        assertEquals("https://studio.example.com:443", info?.origin)
        assertFalse(info?.isLocal == true)
    }

    @Test
    fun `fragment 中多个参数可同时解析`() {
        val info = PairingInfo.parse("http://10.0.0.8:9000/#pair=p1&token=t1")

        assertEquals("p1", info?.pairCode)
        assertEquals("t1", info?.token)
        assertEquals("http://10.0.0.8:9000", info?.origin)
    }

    // ---------- 基本解析：query 形式 ----------

    @Test
    fun `query 中的 pair 与 device 参数被解析`() {
        val info = PairingInfo.parse("http://192.168.1.5:8080/?pair=p1&device=d1")

        assertEquals("p1", info?.pairCode)
        assertEquals("d1", info?.deviceId)
    }

    @Test
    fun `fragment 优先于 query 的同名参数`() {
        val info = PairingInfo.parse("http://192.168.1.5:8080/?pair=fromQuery#pair=fromFragment")

        assertEquals("fromFragment", info?.pairCode)
    }

    @Test
    fun `参数键名大小写不敏感`() {
        val info = PairingInfo.parse("http://192.168.1.5:8080/#PAIR=upper")

        assertEquals("upper", info?.pairCode)
    }

    // ---------- 端口与 scheme ----------

    @Test
    fun `http 未写端口时归一化为 80`() {
        assertEquals("http://192.168.1.5:80", PairingInfo.parse("http://192.168.1.5/")?.origin)
    }

    @Test
    fun `显式端口被保留`() {
        assertEquals("http://192.168.1.5:1234", PairingInfo.parse("http://192.168.1.5:1234")?.origin)
    }

    @Test
    fun `scheme 大小写不敏感且影响缺省端口`() {
        // 原 Uri 实现用大小写敏感的等值比较判断 https，会把 HTTPS 误判为 80 端口
        assertEquals("https://example.com:443", PairingInfo.parse("HTTPS://example.com/")?.origin)
        assertEquals("http://example.com:80", PairingInfo.parse("HTTP://example.com/")?.origin)
    }

    @Test
    fun `带路径的配对地址保留 origin 不含路径`() {
        val info = PairingInfo.parse("http://192.168.1.5:8080/studio/pair#pair=p1")

        assertEquals("http://192.168.1.5:8080", info?.origin)
        assertEquals("p1", info?.pairCode)
    }

    // ---------- 私有网段判定 ----------

    @Test
    fun `常见私有网段被判定为局域网`() {
        val locals = listOf(
            "http://127.0.0.1:8080/",
            "http://localhost:8080/",
            "http://10.0.0.1:8080/",
            "http://10.255.255.254:8080/",
            "http://192.168.0.1:8080/",
            "http://192.168.255.255:8080/",
            "http://172.16.0.1:8080/",
            "http://172.31.255.255:8080/",
            "http://macbook.local:8080/"
        )

        locals.forEach { url ->
            assertTrue("应判定为私网: $url", PairingInfo.parse(url)?.isLocal == true)
        }
    }

    @Test
    fun `172 网段边界之外不算私网`() {
        // 172.16.0.0/12 合法范围是 172.16 ~ 172.31
        assertFalse(PairingInfo.parse("http://172.15.1.1:8080/")?.isLocal == true)
        assertFalse(PairingInfo.parse("http://172.32.0.1:8080/")?.isLocal == true)
    }

    @Test
    fun `Tailscale CGNAT 网段边界正确`() {
        // 100.64.0.0/10 合法范围是 100.64 ~ 100.127
        assertTrue(PairingInfo.parse("http://100.64.0.1:8080/")?.isLocal == true)
        assertTrue(PairingInfo.parse("http://100.100.1.1:8080/")?.isLocal == true)
        assertTrue(PairingInfo.parse("http://100.127.255.255:8080/")?.isLocal == true)

        assertFalse(PairingInfo.parse("http://100.63.255.255:8080/")?.isLocal == true)
        assertFalse(PairingInfo.parse("http://100.128.0.1:8080/")?.isLocal == true)
    }

    @Test
    fun `公网地址不判定为局域网`() {
        assertFalse(PairingInfo.parse("https://studio.example.com/")?.isLocal == true)
        assertFalse(PairingInfo.parse("http://8.8.8.8:8080/")?.isLocal == true)
    }

    @Test
    fun `私有网段判定独立可复用`() {
        assertTrue(PairingInfo.isPrivateHost("192.168.1.1"))
        assertTrue(PairingInfo.isPrivateHost("MYBOOK.LOCAL"))
        assertFalse(PairingInfo.isPrivateHost("example.com"))
    }

    // ---------- 畸形输入 ----------

    @Test
    fun `非 http 协议返回 null`() {
        assertNull(PairingInfo.parse("ftp://192.168.1.5/pair"))
        assertNull(PairingInfo.parse("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(PairingInfo.parse("192.168.1.5:8080"))
    }

    @Test
    fun `空输入与纯空白返回 null`() {
        assertNull(PairingInfo.parse(""))
        assertNull(PairingInfo.parse("   "))
        assertNull(PairingInfo.parse("\n\t"))
    }

    @Test
    fun `缺少主机名返回 null`() {
        assertNull(PairingInfo.parse("http:///pair=abc"))
        assertNull(PairingInfo.parse("http://"))
        assertNull(PairingInfo.parse("https://#pair=abc"))
    }

    @Test
    fun `非法端口返回 null`() {
        assertNull(PairingInfo.parse("http://192.168.1.5:0/"))
        assertNull(PairingInfo.parse("http://192.168.1.5:99999/"))
        assertNull(PairingInfo.parse("http://192.168.1.5:abc/"))
    }

    @Test
    fun `首尾空白被容忍`() {
        val info = PairingInfo.parse("  http://192.168.1.5:8080/#pair=p1  ")

        assertEquals("p1", info?.pairCode)
        // rawUrl 收紧为去除首尾空白后的地址
        assertEquals("http://192.168.1.5:8080/#pair=p1", info?.rawUrl)
    }

    // ---------- 凭据净化 ----------

    @Test
    fun `净化后剔除 pair 与 token 参数`() {
        val sanitized = PairingInfo.sanitizedUrl("http://192.168.1.5:8080/?pair=p&device=d1#token=t")

        assertEquals("http://192.168.1.5:8080/?device=d1", sanitized)
    }

    @Test
    fun `净化保留路径与其他参数`() {
        val sanitized = PairingInfo.sanitizedUrl("http://192.168.1.5:8080/studio/?pair=p&tab=chat")

        assertEquals("http://192.168.1.5:8080/studio/?tab=chat", sanitized)
    }

    @Test
    fun `无凭据的地址净化后保持不变`() {
        val url = "http://192.168.1.5:8080/studio/?tab=chat#section"

        assertEquals(url, PairingInfo.sanitizedUrl(url))
    }

    @Test
    fun `净化对无法解析的输入原样返回`() {
        assertEquals("not a url", PairingInfo.sanitizedUrl("not a url"))
    }

    @Test
    fun `净化后不再包含任何凭据`() {
        val sanitized = PairingInfo.sanitizedUrl("http://192.168.1.5:8080/#pair=secret")

        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("pair="))
        assertEquals("http://192.168.1.5:8080/", sanitized)
    }

    // ---------- 同源比较辅助 ----------

    @Test
    fun `hostOf 与 originOf 对畸形输入返回 null`() {
        assertNull(PairingInfo.hostOf("ftp://example.com"))
        assertNull(PairingInfo.originOf("example.com"))
    }

    @Test
    fun `hostOf 与 originOf 归一化大小写与缺省端口`() {
        assertEquals("example.com", PairingInfo.hostOf("http://Example.COM:8080/"))
        assertEquals("https://example.com:443", PairingInfo.originOf("HTTPS://Example.com/"))
    }

    @Test
    fun `同源比较忽略端口之外的路径差异`() {
        val a = PairingInfo.originOf("http://192.168.1.5:8080/#pair=p")
        val b = PairingInfo.originOf("http://192.168.1.5:8080/studio/chat")

        assertEquals(a, b)
    }
}
