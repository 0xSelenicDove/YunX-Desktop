package com.yunx.app.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** GitHub's public-client device flow. No client secret, cookies, or repository scopes. */
class GitHubDeviceAuth(
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build(),
    private val baseUrl: HttpUrl = "https://github.com/".toHttpUrl(),
    private val clientId: String = CLIENT_ID
) {
    class DeviceCode(
        internal val secret: String,
        val userCode: String,
        val expiresInSeconds: Long,
        internal val intervalSeconds: Long
    )

    suspend fun requestCode(): DeviceCode {
        val json = post("login/device/code", mapOf("client_id" to clientId))
        if (json.has("error")) throw IOException("GitHub 无法开始登录，请稍后重试")
        // Use the fixed GitHub URL in the UI, never a server-supplied redirect.
        return DeviceCode(json.getString("device_code"), json.getString("user_code"),
            json.getLong("expires_in").coerceIn(1, 3600), json.optLong("interval", 5).coerceAtLeast(1))
    }

    suspend fun awaitToken(code: DeviceCode): String = withTimeout(code.expiresInSeconds * 1000) {
        var interval = code.intervalSeconds
        while (true) {
            delay(interval * 1000)
            val json = post("login/oauth/access_token", mapOf(
                "client_id" to clientId,
                "device_code" to code.secret,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
            ))
            val token = json.optString("access_token")
            if (token.isNotBlank()) {
                check(json.optString("token_type").equals("bearer", ignoreCase = true))
                return@withTimeout token
            }
            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> throw IOException("已取消 GitHub 授权")
                "expired_token", "token_expired" -> throw IOException("登录验证码已过期，请重新登录")
                else -> throw IOException("GitHub 授权失败，请重新登录")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun post(path: String, fields: Map<String, String>): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
            val call = client.newCall(Request.Builder().url(baseUrl.resolve(path)!!)
                .header("Accept", "application/json").header("User-Agent", "YunX-Desktop")
                .post(form).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("无法连接 GitHub，请检查网络"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException("GitHub 登录请求失败（HTTP ${it.code}）")
                            JSONObject(it.body?.string() ?: throw IOException("GitHub 返回为空"))
                        }
                    }
                    if (continuation.isActive) result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(IOException("GitHub 登录响应无效，请重试")) }
                    )
                }
            })
        }

    companion object {
        // Public identifier for the fork-owned GitHub App; not a secret.
        const val CLIENT_ID = "Iv23lionn3rHqrghzMgm"
        const val INSTALL_URL = "https://github.com/apps/yunx-read-only-selenicdove/installations/new"
        const val VERIFICATION_URL = "https://github.com/login/device"
    }
}
