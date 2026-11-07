package com.spider.remote.stream

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * خادم HTTP صغير يقدّم ملفاً واحداً اختاره المستخدم، ويقبل الاتصال من عنوان الرسيفر فقط.
 * يدعم طلبات Range لأن أجهزة DLNA تحتاجها للتقديم والتأخير.
 */
class FileServer(
    private val ctx: Context,
    private val uri: Uri,
    private val allowedIp: String,
    private val bindAddr: InetAddress
) {
    private var server: ServerSocket? = null
    val mime: String = ctx.contentResolver.getType(uri) ?: "video/mp4"
    private val size: Long = querySize()

    private fun querySize(): Long {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }

    fun start(): String {
        val s = ServerSocket(0, 5, bindAddr)
        server = s
        thread(isDaemon = true) {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                } catch (e: Exception) {
                    break
                }
            }
        }
        return "http://${bindAddr.hostAddress}:${s.localPort}/media"
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
    }

    private fun handle(c: Socket) {
        try {
            c.use {
                if (c.inetAddress.hostAddress != allowedIp || size <= 0) return
                c.soTimeout = 20000
                val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val reqLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val l = reader.readLine() ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val method = reqLine.split(" ").firstOrNull() ?: return
                var start = 0L
                var end = size - 1
                var partial = false
                headers["range"]?.let { h ->
                    Regex("bytes=(\\d*)-(\\d*)").find(h)?.let { m ->
                        val a = m.groupValues[1]
                        val b = m.groupValues[2]
                        if (a.isNotEmpty()) {
                            start = a.toLong()
                            if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                        } else if (b.isNotEmpty()) {
                            start = maxOf(0L, size - b.toLong())
                        }
                        partial = true
                    }
                }
                val out = BufferedOutputStream(c.getOutputStream())
                if (start > end) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val len = end - start + 1
                val head = (if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n") +
                    "Content-Type: $mime\r\nContent-Length: $len\r\nAccept-Ranges: bytes\r\n" +
                    (if (partial) "Content-Range: bytes $start-$end/$size\r\n" else "") +
                    "transferMode.dlna.org: Streaming\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                if (method != "HEAD") {
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis ->
                            fis.channel.position(start)
                            val buf = ByteArray(64 * 1024)
                            var remaining = len
                            while (remaining > 0) {
                                val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                    }
                }
                out.flush()
            }
        } catch (_: Exception) {
            // الجهاز أغلق الاتصال أو انتهت المهلة؛ لا شيء نفعله
        }
    }

    companion object {
        /** عنوان الهاتف على نفس الشبكة الفرعية للرسيفر. */
        fun localAddressFor(rendererIp: String): InetAddress? {
            val prefix = rendererIp.substringBeforeLast('.') + "."
            val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
            return all.firstOrNull { it.hostAddress?.startsWith(prefix) == true }
        }
    }
}
