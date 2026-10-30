package com.spider.remote.core

enum class ProtocolStatus { UNVERIFIED, VERIFIED }

/** ملف تعريف موديل: إضافة موديل جديد = إضافة كائن جديد هنا فقط. */
interface ReceiverProfile {
    val id: String
    val displayName: String
    val status: ProtocolStatus
    fun transport(host: String): RemoteTransport
}

object Forever5ExtraProfile : ReceiverProfile {
    override val id = "spider_forever_5_extra"
    override val displayName = "Spider Forever 5 Extra"
    override val status = ProtocolStatus.UNVERIFIED
    override fun transport(host: String): RemoteTransport = UnverifiedTransport
}

object ProfileRegistry {
    val all: List<ReceiverProfile> = listOf(Forever5ExtraProfile)
}
