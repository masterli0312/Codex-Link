package com.codex.quota

import com.codex.quota.data.cloud.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CloudApiTest {
    @Test fun requestsUseOfficialOriginAndSelectedWorkspaceAndEncodeCursor() = runBlocking {
        var request: Request? = null
        val api = CloudApi(OkHttpClient.Builder().addInterceptor {
            request = it.request()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"items":[],"cursor":null}""".toResponseBody()).build()
        }.build())
        api.tasks("access-fixture", "workspace-fixture", "next&x=1")
        assertEquals("chatgpt.com", request!!.url.host)
        assertEquals("/backend-api/wham/tasks/list", request!!.url.encodedPath)
        assertEquals("next&x=1", request!!.url.queryParameter("cursor"))
        assertEquals("20", request!!.url.queryParameter("limit"))
        assertEquals("Bearer access-fixture", request!!.header("Authorization"))
        assertEquals("workspace-fixture", request!!.header("ChatGPT-Account-Id"))
    }
    @Test fun ambiguousCreateNeverRetriesAndDoesNotExposeServerOrCredentialData() = runBlocking {
        val calls = AtomicInteger()
        val api = CloudApi(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw IOException("sensitive fixture content")
        }.build())
        val error = runCatching { api.create("token", "workspace", "env", "main", "Fixture") }.exceptionOrNull() as CloudException
        assertEquals(CloudErrorKind.UNCERTAIN, error.kind)
        assertEquals(1, calls.get())
        assertFalse(error.toString().contains("sensitive fixture"))
    }
    @Test fun jsonPermissionDenialAndCloudflareNodeBlockAreDifferent() = runBlocking {
        fun api(html: Boolean) = CloudApi(OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .header("cf-ray", "fixture").header("Content-Type", if(html) "text/html" else "application/json")
                .body("fixture".toResponseBody()).build()
        }.build())
        assertEquals(CloudErrorKind.FORBIDDEN, (runCatching { api(false).environments("token", "workspace") }.exceptionOrNull() as CloudException).kind)
        assertEquals(CloudErrorKind.NODE_BLOCKED, (runCatching { api(true).environments("token", "workspace") }.exceptionOrNull() as CloudException).kind)
    }
}
