package com.spider.remote.dlna

import com.spider.remote.core.RemoteKey
import com.spider.remote.core.RemoteTransport

/** ينفذ فقط ما يعلنه الجهاز فعلاً في RenderingControl: الصوت والكتم. أي زر آخر يفشل صراحةً. */
class DlnaTransport(private val r: Renderer) : RemoteTransport {
    private val inst = "InstanceID" to "0"
    private val master = "Channel" to "Master"

    private fun target(): Pair<String, String>? {
        val u = r.renderingUrl
        val t = r.renderingType
        return if (u != null && t != null) u to t else null
    }

    suspend fun getVolume(): Result<Int> {
        val (u, t) = target() ?: return Result.failure(IllegalStateException("لا توجد خدمة RenderingControl"))
        return Dlna.soap(u, t, "GetVolume", listOf(inst, master)).mapCatching {
            Regex("<CurrentVolume>\\s*(\\d+)\\s*</CurrentVolume>").find(it)?.groupValues?.get(1)?.toInt()
                ?: throw IllegalStateException("رد غير متوقع: ${it.take(150)}")
        }
    }

    suspend fun getMute(): Result<Boolean> {
        val (u, t) = target() ?: return Result.failure(IllegalStateException("لا توجد خدمة RenderingControl"))
        return Dlna.soap(u, t, "GetMute", listOf(inst, master)).mapCatching {
            val v = Regex("<CurrentMute>\\s*(\\w+)\\s*</CurrentMute>").find(it)?.groupValues?.get(1)
                ?: throw IllegalStateException("رد غير متوقع: ${it.take(150)}")
            v == "1" || v.equals("true", ignoreCase = true)
        }
    }

    private suspend fun step(delta: Int): Result<Unit> {
        val (u, t) = target() ?: return Result.failure(IllegalStateException("لا توجد خدمة RenderingControl"))
        val cur = getVolume().getOrElse { return Result.failure(it) }
        val n = (cur + delta).coerceIn(0, 100)
        return Dlna.soap(u, t, "SetVolume", listOf(inst, master, "DesiredVolume" to "$n")).map { }
    }

    private var lastVolume = 0

    /** يجرّب SetMute أولاً؛ إن رفضه الجهاز يلجأ لخفض الصوت إلى 0 ثم استرجاع آخر قيمة. */
    private suspend fun toggleMute(): Result<Unit> {
        val (u, t) = target() ?: return Result.failure(IllegalStateException("لا توجد خدمة RenderingControl"))
        val muted = getMute()
        if (muted.isSuccess) {
            val r = Dlna.soap(u, t, "SetMute", listOf(inst, master, "DesiredMute" to if (muted.getOrThrow()) "0" else "1")).map { }
            if (r.isSuccess) return r
        }
        val vol = getVolume().getOrElse { return Result.failure(it) }
        val newVol = if (vol > 0) { lastVolume = vol; 0 } else lastVolume.coerceAtLeast(1)
        return Dlna.soap(u, t, "SetVolume", listOf(inst, master, "DesiredVolume" to "$newVol")).map { }
    }

    override suspend fun send(key: RemoteKey): Result<Unit> = when (key) {
        RemoteKey.VOL_UP -> step(+1)
        RemoteKey.VOL_DOWN -> step(-1)
        RemoteKey.MUTE -> toggleMute()
        else -> Result.failure(IllegalStateException("الزر غير مدعوم: الجهاز لا يعرض هذا الأمر عبر الشبكة"))
    }
}
