package com.spider.remote.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class SsdpReply(val from: String, val text: String)

/**
 * اكتشاف UPnP/SSDP القياسي: استعلام M-SEARCH عام ثم استماع للردود فقط.
 * لا يرسل أي أمر تحكم لأي جهاز.
 */
object SsdpProbe {
    suspend fun search(listenMs: Int = 3000): List<SsdpReply> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SsdpReply>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                val end = System.currentTimeMillis() + listenMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        out += SsdpReply(p.address.hostAddress ?: "?", String(p.data, 0, p.length))
                    } catch (e: SocketTimeoutException) { /* continue */ }
                }
            }
        } catch (e: Exception) { /* network unavailable */ }
        out
    }

    fun summarize(replies: List<SsdpReply>): String =
        replies.flatMap { it.text.lines() }
            .map { it.trim() }
            .filter { l -> listOf("SERVER:", "ST:", "LOCATION:", "USN:").any { l.startsWith(it, ignoreCase = true) } }
            .distinct().take(6).joinToString("\n")
}
