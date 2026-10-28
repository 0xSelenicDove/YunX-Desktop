package com.yunx.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/** Resolve credentials at request time, never in persisted download headers or URLs. */
class GitHubDownloadAuth(private val tokenProvider: () -> String? = GitHubTokenStore::getToken) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.scheme != "https" || url.host != "api.github.com" || url.port != 443 ||
            !url.encodedPath.startsWith("/repos/") || request.method !in setOf("GET", "HEAD")) {
            return chain.proceed(request)
        }
        val accept = if (url.pathSegments.getOrNull(3) == "contents")
            "application/vnd.github.raw+json" else "application/octet-stream"
        val builder = request.newBuilder().header("Accept", accept)
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        // OkHttp removes Authorization on cross-origin redirects to GitHub's download CDN.
        return chain.proceed(builder.build())
    }
}
