package com.spider.remote.dlna

import com.spider.remote.probe.SsdpProbe
import com.spider.remote.probe.TcpProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class Renderer(
    val name: String,
    val ip: String,
    val location: String,
    val renderingType: String?,
    val renderingUrl: String?
)

/** DLNA/UPnP قياسي: اكتشاف أجهزة العرض ثم إرسال طلبات SOAP موثقة في ملفات الوصف. */
object Dlna {
    private fun http(url: String, method: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 4000
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    private fun tag(s: String, t: String): String? =
        Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(s)?.groupValues?.get(1)?.trim()

    private fun origin(location: String): String {
        val u = URL(location)
        val port = if (u.port == -1) u.defaultPort else u.port
        return "${u.protocol}://${u.host}:$port"
    }

    private fun resolve(base: String, location: String, path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("/") -> origin(location) + path
        else -> (if (base.endsWith("/")) base else "$base/") + path
    }

    /** يقرأ ملف الوصف؛ يعيد null إن لم يكن الجهاز MediaRenderer أو خارج الشبكة المحلية. */
    suspend fun describe(location: String): Renderer? = withContext(Dispatchers.IO) {
        try {
            val ip = URL(location).host
            if (!TcpProbe.isPrivateIPv4(ip)) return@withContext null
            val (code, xml) = http(location, "GET", null, emptyMap())
            if (code !in 200..299) return@withContext null
            if (tag(xml, "deviceType")?.contains("MediaRenderer", ignoreCase = true) != true) return@withContext null
            val base = tag(xml, "URLBase") ?: (origin(location) + "/")
            var rType: String? = null
            var rUrl: String? = null
            Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                val block = m.groupValues[1]
                val type = tag(block, "serviceType")
                val ctrl = tag(block, "controlURL")
                if (type != null && ctrl != null && type.contains("RenderingControl")) {
                    rType = type
                    rUrl = resolve(base, location, ctrl)
                }
            }
            Renderer(tag(xml, "friendlyName") ?: ip, ip, location, rType, rUrl)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun discover(): List<Renderer> {
        val replies = SsdpProbe.search(listenMs = 3500)
        val locations = replies.mapNotNull { r ->
            r.text.lines().firstOrNull { it.trim().startsWith("LOCATION:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
        }.distinct()
        return locations.mapNotNull { describe(it) }.distinctBy { it.location }
    }

    suspend fun soap(url: String, type: String, action: String, args: List<Pair<String, String>>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val a = args.joinToString("") { "<${it.first}>${it.second}</${it.first}>" }
                val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$type">$a</u:$action></s:Body></s:Envelope>"""
                val (code, text) = http(
                    url, "POST", body,
                    mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"$type#$action\"")
                )
                if (code in 200..299) Result.success(text)
                else {
                    val ec = Regex("<errorCode>(.*?)</errorCode>").find(text)?.groupValues?.get(1)
                    val ed = Regex("<errorDescription>(.*?)</errorDescription>").find(text)?.groupValues?.get(1)
                    val detail = if (ec != null) "UPnP خطأ $ec ${ed ?: ""}"
                    else text.replace(Regex("\\s+"), " ").takeLast(300)
                    Result.failure(Exception("HTTP $code — $detail"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
