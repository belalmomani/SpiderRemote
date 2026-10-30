package com.spider.remote.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class PortResult(val port: Int, val open: Boolean)

/**
 * فحص اتصال فقط (TCP connect) دون إرسال أي بيانات للجهاز.
 * مقيّد بعناوين الشبكة المحلية الخاصة حتى لا يُستخدم خارج الشبكة المنزلية.
 */
object TcpProbe {
    private val privateRange = Regex(
        "^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})$"
    )

    fun isPrivateIPv4(host: String) = privateRange.matches(host.trim())

    fun parsePorts(text: String): List<Int> =
        text.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }.distinct().take(20)

    suspend fun check(host: String, port: Int, timeoutMs: Int = 1500): PortResult =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                PortResult(port, true)
            } catch (e: Exception) {
                PortResult(port, false)
            }
        }
}
