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
