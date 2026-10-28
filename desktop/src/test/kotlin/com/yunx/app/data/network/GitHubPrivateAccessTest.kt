package com.yunx.app.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GitHubPrivateAccessTest {
    private fun response(request: Request, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun `own home includes granted private repositories and caches are credential scoped`() = runBlocking {
        GitHubResponseCache.clear()
        var token: String? = "account-one"
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            response(request, when (request.url.encodedPath) {
                "/user" -> """{"login":"reader"}"""
                "/user/repos" -> """[{"name":"selected","full_name":"team/selected","private":true}]"""
                "/repos/team/selected" -> """{"name":"selected","full_name":"team/selected","private":true}"""
                else -> "[]"
            })
        }.build()
        val api = GitHubApi({ client }, { token })
        val repos = api.getUserRepos("reader", 2)!!
        assertTrue(repos.single().isPrivate)
        assertEquals("team/selected", repos.single().fullName)
        assertEquals("2", requests.last().url.queryParameter("page"))
        assertEquals("all", requests.last().url.queryParameter("visibility"))
        api.getRepo("team", "selected")
        api.getRepo("team", "selected")
        assertEquals(1, requests.count { it.url.encodedPath == "/repos/team/selected" })
        token = null
        api.getRepo("team", "selected")
        assertEquals(2, requests.count { it.url.encodedPath == "/repos/team/selected" })
        assertNull(requests.last().header("Authorization"))
        api.getUserRepos("other")
        assertEquals("/users/other/repos", requests.last().url.encodedPath)
    }

    @Test fun `download credentials are restricted to HTTPS GitHub API reads and resolved afresh`() {
        var token = "first-token"
        val sent = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(GitHubDownloadAuth { token })
            .addInterceptor { chain -> sent.add(chain.request()); response(chain.request(), "data") }.build()
        fun get(url: String) { client.newCall(Request.Builder().url(url).build()).execute().close() }
        get("https://api.github.com/repos/team/selected/contents/file.txt?ref=main")
        assertEquals("Bearer first-token", sent.last().header("Authorization"))
        assertEquals("application/vnd.github.raw+json", sent.last().header("Accept"))
        get("https://api.github.com/repos/team/selected/zipball/main")
        assertEquals("application/vnd.github+json", sent.last().header("Accept"))
        token = "second-token"
        get("https://api.github.com/repos/team/selected/releases/assets/1")
        assertEquals("Bearer second-token", sent.last().header("Authorization"))
        assertEquals("application/octet-stream", sent.last().header("Accept"))
        for (url in listOf("http://api.github.com/repos/team/selected/zipball/main",
            "https://api.github.com.evil.example/repos/team/selected/zipball/main",
            "https://mirror.example/https://api.github.com/repos/team/selected/zipball/main",
            "https://api.github.com:444/repos/team/selected/zipball/main",
            "https://raw.githubusercontent.com/team/selected/main/file.txt")) {
            get(url)
            assertNull(sent.last().header("Authorization"))
        }
    }
}
