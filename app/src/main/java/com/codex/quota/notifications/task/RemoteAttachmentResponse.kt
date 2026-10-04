package com.codex.quota.notifications.task

import java.io.IOException
import okio.BufferedSource

/** Bound upload metadata while reading, including responses without Content-Length. */
internal object RemoteAttachmentResponse {
    const val MAX_BYTES = 16_384

    fun read(source: BufferedSource): String {
        if (source.request(MAX_BYTES + 1L)) throw IOException("ATTACHMENT_UPLOAD")
        return source.readUtf8()
    }
}
