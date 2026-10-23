package com.yunx.app.data.preview

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yunx.app.data.download.HlsRequestPolicy
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.Log
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 本地回环媒体代理（应用内视频预览的基础设施）。
 *
 * 为什么需要它：JavaFX `Media` 不支持自定义请求头，而各平台 CDN 都校验
 * `Referer` / `User-Agent` / `Cookie`，直链喂给播放器必 403。所有预览流量统一
 * 走 `http://127.0.0.1:随机端口/{会话token}/...`，由本代理注入请求头并透传。
 *
 * 职责（docs/PORT-PLAN-1.3.0.md §3.2）：
 * 1. `/media/{id}`：单文件 MP4 代理 —— 注入请求头、**透传 Range**（拖动进度条必需）；
 * 2. 安全边界：只绑 `127.0.0.1`；每个 URL 带**进程级会话 token**（每次启动重新生成）；
 *    源地址与请求头不落 URL（注册表 [sources] 以随机 id 引用，避免敏感头泄露进日志）；
 *    上游仅限 HTTPS（复用 [HlsRequestPolicy] 的同源判断，重定向跳域时剥敏感头）；
 * 3. 启动失败（端口被占等）直接视为「预览不可用」，由调用方降级，不换端口裸奔。
 *
 * HLS 不走这里：jfxmedia 对 HTTP 上的 fragmented MP4 支持有缺陷（实测，见 §9.2），
 * 改由 [HlsPreviewSession] 落成本地增长文件后直接播放。
 *
 * 生命周期：进程级单例，首次预览时 [ensureStarted] 懒启动；JVM 退出经 shutdown hook 关闭。
 */
object LoopbackMediaProxy {

    private const val TAG = "YunX-Preview"
    private const val MAX_REDIRECTS = 5
    private const val COPY_BUFFER_SIZE = 64 * 1024

    /** 单条已注册的预览源：直链 + 该媒体自带的请求头 */
    private data class Source(val url: String, val headers: Map<String, String>)

    @Volatile
    private var server: HttpServer? = null

    /** 会话随机 token：本机其他进程扫到端口也无法猜出有效 URL */
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val sources = ConcurrentHashMap<String, Source>()

    /** 代理是否已就绪（启动失败即 false，整个会话不再重试，避免反复卡 UI） */
    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    private var startAttempted = false

    private var boundPort: Int = -1

    /**
     * 懒启动代理；成功返回 true。重复调用是幂等的。
     * 失败（bind 异常等）记日志并返回 false —— 调用方应把预览降级为不可用。
     */
    @Synchronized
    fun ensureStarted(): Boolean {
        if (available) return true
        if (startAttempted) return false
        startAttempted = true
        return runCatching {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
            http.createContext("/") { exchange -> route(exchange) }
            // 默认执行器会把所有请求串行化在 dispatcher 线程上（Range 请求与 HLS 流会互相饿死），
            // 换成守护线程的缓存池：每条流独立线程，进程退出自动回收
            http.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "preview-proxy").apply { isDaemon = true }
            }
            http.start()
            server = http
            boundPort = http.address.port
            available = true
            Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "preview-proxy-stop"))
            Log.i(TAG, "回环代理已启动: 127.0.0.1:$boundPort")
            true
        }.getOrElse {
            Log.e(TAG, "回环代理启动失败，预览不可用: ${it.message}")
            false
        }
    }

    fun stop() {
        runCatching { server?.stop(0) }
        server = null
        available = false
    }

    /**
     * 注册一个 MP4 单文件预览源，返回代理 URL；代理未就绪返回 null（调用方降级）。
     * [headers] 原样持有在内存注册表中，不出现在 URL 里。
     */
    fun mediaUrl(url: String, headers: Map<String, String>): String? {
        if (!ensureStarted()) return null
        if (HlsRequestPolicy.initialUrl(url) == null) {
            Log.w(TAG, "拒绝非 HTTPS 的预览源: ${url.take(64)}")
            return null
        }
        val id = UUID.randomUUID().toString()
        sources[id] = Source(url, headers)
        return "http://127.0.0.1:$boundPort/$token/media/$id"
    }

    // ---- 请求路由与安全边界 ----

    private fun route(exchange: HttpExchange) {
        try {
            val segments = exchange.requestURI.rawPath.split('/').filter { it.isNotEmpty() }
            // 第一段必须是会话 token —— 本机端口扫描拿到的 404，不构成开放中转
            if (segments.size != 3 || segments[0] != token) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val source = sources[segments[2]]
            if (source == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            when (segments[1]) {
                "media" -> handleMedia(exchange, source)
                else -> exchange.sendResponseHeaders(404, -1)
            }
        } catch (t: Throwable) {
            // 客户端中断（拖动/关闭预览）在这里表现为 IOException，属正常路径，不打错误日志
            if (t !is IOException) Log.e(TAG, "代理请求处理异常: ${t.message}")
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            runCatching { exchange.close() }
        }
    }

    // ---- /media：单文件代理（Range 透传 + 请求头注入）----

    private fun handleMedia(exchange: HttpExchange, source: Source) {
        val startUrl = HlsRequestPolicy.initialUrl(source.url) ?: run {
            exchange.sendResponseHeaders(502, -1)
            return
        }
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val headOnly = exchange.requestMethod.equals("HEAD", ignoreCase = true)
        if (!headOnly && !exchange.requestMethod.equals("GET", ignoreCase = true)) {
            exchange.sendResponseHeaders(405, -1)
            return
        }

        val response = fetchWithRedirects(startUrl, source.headers, rangeHeader, headOnly) ?: run {
            exchange.sendResponseHeaders(502, -1)
            return
        }
        response.use { resp ->
            if (resp.code !in 200..299) {
                Log.w(TAG, "上游拒绝预览请求: HTTP ${resp.code}")
                exchange.sendResponseHeaders(502, -1)
                return
            }
            val outHeaders = exchange.responseHeaders
            resp.header("Content-Type")?.let { outHeaders.set("Content-Type", it) }
            resp.header("Content-Range")?.let { outHeaders.set("Content-Range", it) }
            outHeaders.set("Accept-Ranges", "bytes")
            outHeaders.set("Cache-Control", "no-store")

            val length = resp.body?.contentLength() ?: -1L
            if (headOnly) {
                if (length >= 0) outHeaders.set("Content-Length", length.toString())
                exchange.sendResponseHeaders(resp.code, -1)
                return
            }
            // 长度已知 → 定长响应；未知 → chunked（0 表示流式）
            exchange.sendResponseHeaders(resp.code, if (length >= 0) length else 0)
            val body = resp.body ?: return
            copyCancellable(body.byteStream(), exchange.responseBody)
        }
    }

    // ---- 上游请求：手动跟随重定向，跨域剥敏感头（与 HlsRequestPolicy 一致）----

    private fun fetchWithRedirects(
        startUrl: HttpUrl,
        headers: Map<String, String>,
        rangeHeader: String?,
        headOnly: Boolean
    ): Response? {
        var current = startUrl
        val client = HttpClients.apiClient().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val builder = Request.Builder().url(current)
            HlsRequestPolicy.headersFor(current, startUrl, headers)
                .forEach { (name, value) -> builder.header(name, value) }
            if (rangeHeader != null) builder.header("Range", rangeHeader)
            val request = (if (headOnly) builder.head() else builder.get()).build()
            val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return null
            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                val next = location?.let { HlsRequestPolicy.resolve(current, it) } ?: return null
                if (redirectCount >= MAX_REDIRECTS) return null
                current = next
            } else {
                return response
            }
        }
        return null
    }

    /** 边下边转；客户端断开（预览关闭/拖动重连）时写抛 IOException，安静结束即可 */
    private fun copyCancellable(input: java.io.InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        input.use { inp ->
            while (true) {
                val count = runCatching { inp.read(buffer) }.getOrDefault(-1)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
            }
        }
        runCatching { output.flush() }
    }
}
