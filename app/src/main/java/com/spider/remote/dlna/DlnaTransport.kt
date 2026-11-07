package com.spider.remote.dlna

import com.spider.remote.core.RemoteKey
import com.spider.remote.core.RemoteTransport

/**
 * الجهاز يقبل SetVolume فقط (GetMute/SetMute يرجعان 401، وGetVolume يرجع 0 دائماً حسب التجربة).
 * لذلك يتتبع التطبيق مستوى الصوت داخلياً ويرسل قيماً مطلقة.
 */
class DlnaTransport(private val r: Renderer) : RemoteTransport {
    private val inst = "InstanceID" to "0"
    private val master = "Channel" to "Master"

    var level = 10
        private set
    private var lastLevel = 10
    private var muted = false

    private fun target(): Pair<String, String>? {
        val u = r.renderingUrl
        val t = r.renderingType
        return if (u != null && t != null) u to t else null
    }

    suspend fun setLevel(n: Int): Result<Unit> {
        val (u, t) = target() ?: return Result.failure(IllegalStateException("لا توجد خدمة RenderingControl"))
        val v = n.coerceIn(0, 100)
        return Dlna.soap(u, t, "SetVolume", listOf(inst, master, "DesiredVolume" to "$v")).map {
            level = v
            if (v > 0) { lastLevel = v; muted = false }
            Unit
        }
    }

    private fun av(): Pair<String, String>? {
        val u = r.avUrl
        val t = r.avType
        return if (u != null && t != null) u to t else null
    }

    /** Play / Pause / Stop على الوسائط المبثوثة إلى الجهاز فقط (ليست قنوات الرسيفر). */
    suspend fun avAction(action: String): Result<Unit> {
        val (u, t) = av() ?: return Result.failure(IllegalStateException("لا توجد خدمة AVTransport"))
        val args = if (action == "Play") listOf(inst, "Speed" to "1") else listOf(inst)
        return Dlna.soap(u, t, action, args).map { Unit }
    }

    suspend fun transportState(): Result<String> {
        val (u, t) = av() ?: return Result.failure(IllegalStateException("لا توجد خدمة AVTransport"))
        return Dlna.soap(u, t, "GetTransportInfo", listOf(inst)).mapCatching {
            Regex("<CurrentTransportState>\\s*(\\w+)\\s*</CurrentTransportState>").find(it)?.groupValues?.get(1)
                ?: throw IllegalStateException("رد غير متوقع: ${it.take(150)}")
        }
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** يبث رابطاً إلى الجهاز: Stop ثم SetAVTransportURI ثم Play. تجريبي. */
    suspend fun playUri(url: String, mime: String, title: String): Result<Unit> {
        val (u, t) = av() ?: return Result.failure(IllegalStateException("لا توجد خدمة AVTransport"))
        val cls = when {
            mime.startsWith("audio") -> "object.item.audioItem.musicTrack"
            mime.startsWith("image") -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>$cls</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
        Dlna.soap(u, t, "Stop", listOf(inst))
        val set = Dlna.soap(u, t, "SetAVTransportURI", listOf(inst, "CurrentURI" to esc(url), "CurrentURIMetaData" to esc(didl)))
        if (set.isFailure) return set.map { }
        return Dlna.soap(u, t, "Play", listOf(inst, "Speed" to "1")).map { }
    }

    override suspend fun send(key: RemoteKey): Result<Unit> = when (key) {
        RemoteKey.VOL_UP -> setLevel(level + 1)
        RemoteKey.VOL_DOWN -> setLevel(level - 1)
        RemoteKey.MUTE ->
            if (!muted) {
                val keep = level.coerceAtLeast(1)
                setLevel(0).map { lastLevel = keep; muted = true; Unit }
            } else {
                setLevel(lastLevel)
            }
        else -> Result.failure(IllegalStateException("الزر غير مدعوم: الجهاز لا يعرض هذا الأمر عبر الشبكة"))
    }
}
