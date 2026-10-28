package com.yunx.app.data.network

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GitHubDeviceAuthTest {
    @Test fun `device flow requests no scopes and handles pending and slow down`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_code":"test-device","user_code":"TEST-CODE","expires_in":900,"interval":1}"""))
            server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse().setBody("""{"error":"slow_down"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"test-token","token_type":"bearer"}"""))
            val auth = GitHubDeviceAuth(baseUrl = server.url("/"), clientId = "test-client")
            val code = auth.requestCode()
            assertEquals("TEST-CODE", code.userCode)
            assertEquals("client_id=test-client", server.takeRequest().body.readUtf8())
            val start = System.nanoTime()
            assertEquals("test-token", auth.awaitToken(GitHubDeviceAuth.DeviceCode(code.secret, code.userCode, 30, 0)))
            assertTrue("slow_down must increase the polling interval", System.nanoTime() - start >= 5_000_000_000)
            assertEquals(4, server.requestCount)
            val poll = server.takeRequest().body.readUtf8()
            assertTrue(poll.contains("device_code=test-device"))
            assertFalse(poll.contains("client_secret"))
        }
    }

    @Test fun `denial stops polling without exposing device secret`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"error":"access_denied"}"""))
            val auth = GitHubDeviceAuth(baseUrl = server.url("/"))
            try {
                auth.awaitToken(GitHubDeviceAuth.DeviceCode("private-device", "TEST", 30, 0))
                fail("Expected denial")
            } catch (e: IOException) {
                assertFalse(e.message.orEmpty().contains("private-device"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun `dismissal cancels polling and device expiry is bounded`() = runBlocking {
        MockWebServer().use { server ->
            val auth = GitHubDeviceAuth(baseUrl = server.url("/"))
            val job = launch { auth.awaitToken(GitHubDeviceAuth.DeviceCode("secret", "TEST", 30, 10)) }
            yield()
            job.cancelAndJoin()
            try {
                auth.awaitToken(GitHubDeviceAuth.DeviceCode("secret", "TEST", 1, 10))
                fail("Expected expiry")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `login does not follow redirects carrying device credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
            try {
                GitHubDeviceAuth(baseUrl = server.url("/")).requestCode()
                fail("Expected redirect rejection")
            } catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }
}
