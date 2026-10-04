package com.codex.quota.notifications.task

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

/** Change the transport origin without changing pairing identity, keys, or cached conversations. */
object RelayRouting {
    fun origin(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null) return null
        return url.toString()
    }

    fun rewrite(value: String, sourceOrigin: String, targetOrigin: String, pairedTopics: Set<String>): String {
        val source = origin(sourceOrigin)?.toHttpUrlOrNull() ?: return value
        val target = origin(targetOrigin)?.toHttpUrlOrNull() ?: return value
        val url = value.toHttpUrlOrNull() ?: return value
        if (!url.isHttps || url.host != source.host || url.port != source.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            url.encodedPath.startsWith("/file/") ||
            pairedTopics.none { url.encodedPath == it || url.encodedPath.startsWith("$it/") }) return value
        return url.newBuilder().host(target.host).port(target.port).build().toString()
    }

    fun endpoint(context: Context?, value: String): String {
        if (context == null) return value
        val settings = TaskNotificationStore(context).read()
        val source = origin(settings.relaySourceOrigin)?.toHttpUrlOrNull() ?: return value
        val url = value.toHttpUrlOrNull() ?: return value
        if (url.host != source.host || url.port != source.port) return value
        val base = settings.endpoint.toHttpUrlOrNull() ?: return value
        val topics = mutableSetOf(base.encodedPath)
        // Public services host many independent computers; migrate only this pairing's topics.
        ComputerConnectionStore(context).all(settings).filter { it.endpoint == settings.endpoint &&
            it.hostId.isNotBlank() && it.key.isNotBlank() }.forEach { computer ->
            for (direction in listOf("commands", "events")) runCatching {
                RemoteProtocol.topic(computer.endpoint, computer.key, computer.hostId, direction)
                    .toHttpUrlOrNull()?.encodedPath?.let(topics::add)
            }
        }
        return rewrite(value, settings.relaySourceOrigin, settings.relayOrigin, topics)
    }

    fun interceptor(context: Context?) = Interceptor { chain ->
        val request = chain.request()
        val actual = endpoint(context, request.url.toString())
        chain.proceed(if (actual == request.url.toString()) request else request.newBuilder().url(actual).build())
    }

    fun attachmentUrl(context: Context?, endpoint: String, value: String): String? =
        TaskNotificationProtocol.attachmentUrl(RelayRouting.endpoint(context, endpoint), value)
            ?: TaskNotificationProtocol.attachmentUrl(endpoint, value)
}
