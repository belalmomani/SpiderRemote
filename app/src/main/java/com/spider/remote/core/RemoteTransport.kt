package com.spider.remote.core

/** طبقة النقل: كل بروتوكول مثبت يضيف تنفيذاً واحداً هنا دون تغيير بقية التطبيق. */
interface RemoteTransport {
    suspend fun send(key: RemoteKey): Result<Unit>
}

/** يُستخدم لأي موديل لم يُثبت بروتوكوله بعد؛ يفشل صراحةً ولا يرسل شيئاً. */
object UnverifiedTransport : RemoteTransport {
    override suspend fun send(key: RemoteKey): Result<Unit> =
        Result.failure(IllegalStateException("البروتوكول غير مثبت لهذا الموديل"))
}
