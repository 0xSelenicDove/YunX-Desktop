package com.yunx.app.data.network.video

import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.network.CefRequest
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * 隐藏浏览器取数：桌面等价于上游 Android 的隐藏 WebView。
 *
 * 抖音作品页要由真实浏览器跑完 `acrawler` 反爬挑战 → 重载 → 挂载 → 自己发详情请求，
 * 纯 HTTP 拿不到，必须用 JCEF（完整 Chromium）渲染。这里创建一个**从不显示**的
 * 窗口态 `CefBrowser`（UI 组件不 attach 到任何窗口）加载页面并回读结果。
 *
 * 实现要点（JCEF 与 Android WebView 的能力差异适配）：
 * - **document-start 注入**：JCEF 没有等价 API，在 [CefLoadHandlerAdapter.onLoadStart]
 *   （仅 main frame）里补注 [extraDocumentStartJs]；抖音挑战-重载每次导航都触发
 *   onLoadStart，所以每次都要重新注入。
 * - **JS 回读**：`executeJavaScript` 没有返回值，用 `CefMessageRouter` 注入页面的
 *   `window.cefQuery` 把结果送回来：就绪探测包成 `ready:<值>`，取数结果包成
 *   `result:<内容>`，在 `onQuery` 里按前缀分发。取数结果若是对象（EXTRACT_JS 返回
 *   小对象），先 `JSON.stringify` 再回传 —— 对齐 Android `evaluateJavascript` 把对象
 *   序列化成 JSON 的语义。
 * - **userAgent 保留但故意不改请求头**：JCEF 是完整 Chromium，默认 UA 就是桌面
 *   Chrome 形态；改写 HTTP UA 会造成与 `navigator.userAgent` 不一致，反而触发反爬。
 * - **线程**：java-cef 的 browser 创建 / executeJavaScript / close 统一走 AWT EDT；
 *   完成 / 超时 / 协程取消都会（经 CAS 去重后）在 EDT 上 close browser、dispose
 *   client 与 MessageRouter，不泄漏原生资源。
 *
 * JCEF 不可用（[JcefHolder.isAvailable] 为 false）时返回 null：调用方自动落回
 * HTTP/SSR 路线，等价于「网页兜底不可用」，不影响其它平台。
 */
object HeadlessWebView {

    private const val TAG = "YunX-Headless"
    private const val PREFIX_READY = "ready:"
    private const val PREFIX_RESULT = "result:"

    suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long = 25_000L,
        pollIntervalMs: Long = 350L,
        extraDocumentStartJs: List<String> = emptyList()
    ): String? {
        if (!JcefHolder.isAvailable()) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK,
                "headless_unavailable",
                "JCEF 不可用，跳过网页兜底：url=$url"
            )
            return null
        }
        val app = JcefHolder.app() ?: run {
            DiagnosticLog.warn(DiagnosticLog.NETWORK, "headless_unavailable", "CefApp 为空：url=$url")
            return null
        }

        // 就绪探测 / 取数脚本统一包成 cefQuery 回传（executeJavaScript 拿不到返回值）。
        // 探测脚本异常时静默吞掉等下一轮轮询；取数脚本异常时也回传一条 result，
        // 避免一直挂到超时（EXTRACT_JS 内部自己也会 catch，这层只防包装层语法问题）。
        val readyProbeJs =
            "(function(){try{var r=(\n$readyCheckJs\n);window.cefQuery({request:'$PREFIX_READY'+r});}catch(e){}})()"
        val extractRunJs = "(function(){try{var r=(\n$extractJs\n);" +
            "var s=(typeof r==='string')?r:JSON.stringify(r);" +
            "window.cefQuery({request:'$PREFIX_RESULT'+s});" +
            "}catch(e){window.cefQuery({request:'$PREFIX_RESULT{\"error\":\"js-wrap\"}'});}})()"

        DiagnosticLog.event(DiagnosticLog.NETWORK, "headless_load_start", "url=$url timeoutMs=$timeoutMs")
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val finished = AtomicBoolean(false)
                    val readySeen = AtomicBoolean(false)
                    var client: CefClient? = null
                    var browser: CefBrowser? = null
                    var router: CefMessageRouter? = null
                    var poller: Thread? = null

                    // 完成（取值/失败/取消）收口：CAS 保证只执行一次，避免 onQuery 在
                    // 协程已完成后二次 resume；资源释放一律放到 EDT。
                    fun complete(value: String?) {
                        if (!finished.compareAndSet(false, true)) return
                        poller?.interrupt()
                        if (cont.isActive) cont.resume(value)
                        SwingUtilities.invokeLater {
                            runCatching { client?.let { c -> router?.let(c::removeMessageRouter) } }
                            runCatching { browser?.close(true) }
                            runCatching { router?.dispose() }
                            runCatching { client?.dispose() }
                        }
                    }
                    cont.invokeOnCancellation { complete(null) }

                    val queryHandler = object : CefMessageRouterHandlerAdapter() {
                        override fun onQuery(
                            queryBrowser: CefBrowser,
                            frame: CefFrame,
                            queryId: Long,
                            request: String,
                            persistent: Boolean,
                            callback: CefQueryCallback
                        ): Boolean {
                            when {
                                request.startsWith(PREFIX_READY) -> {
                                    callback.success("")
                                    if (request == "${PREFIX_READY}true" &&
                                        readySeen.compareAndSet(false, true) && !finished.get()
                                    ) {
                                        DiagnosticLog.event(
                                            DiagnosticLog.NETWORK, "headless_ready", "url=$url"
                                        )
                                        SwingUtilities.invokeLater {
                                            if (!finished.get()) {
                                                runCatching {
                                                    browser?.executeJavaScript(extractRunJs, url, 0)
                                                }
                                            }
                                        }
                                    }
                                    return true
                                }
                                request.startsWith(PREFIX_RESULT) -> {
                                    callback.success("")
                                    val payload = request.substring(PREFIX_RESULT.length)
                                    DiagnosticLog.event(
                                        DiagnosticLog.NETWORK,
                                        "headless_extract_ok",
                                        "url=$url bytes=${payload.length}"
                                    )
                                    complete(payload)
                                    return true
                                }
                            }
                            return false
                        }
                    }

                    val loadHandler = object : CefLoadHandlerAdapter() {
                        override fun onLoadStart(
                            loadBrowser: CefBrowser,
                            frame: CefFrame,
                            transitionType: CefRequest.TransitionType
                        ) {
                            // document-start 注入：挑战-重载每次导航都触发，逐次重注
                            if (frame.isMain && extraDocumentStartJs.isNotEmpty()) {
                                SwingUtilities.invokeLater {
                                    extraDocumentStartJs.forEach { js ->
                                        runCatching {
                                            loadBrowser.executeJavaScript(js, url, 0)
                                        }
                                    }
                                }
                            }
                        }

                        override fun onLoadError(
                            errorBrowser: CefBrowser,
                            frame: CefFrame,
                            errorCode: CefLoadHandler.ErrorCode,
                            errorText: String,
                            failedUrl: String
                        ) {
                            // ERR_ABORTED 是重定向/挑战重载掐掉的导航，不算失败
                            if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
                                DiagnosticLog.warn(
                                    DiagnosticLog.NETWORK,
                                    "headless_load_error",
                                    "code=$errorCode text=$errorText url=$failedUrl"
                                )
                                complete(null)
                            }
                        }
                    }

                    // 创建浏览器必须在 EDT（与 JcefLoginPane 同一约定）
                    val createTask = Runnable {
                        try {
                            val c = app.createClient()
                            client = c
                            val r = CefMessageRouter.create()
                            r.addHandler(queryHandler, true)
                            c.addMessageRouter(r)
                            router = r
                            c.addLoadHandler(loadHandler)
                            val b = c.createBrowser(url, false, false)
                            browser = b
                            // 画布永不挂到窗口 → 等不到 addNotify 的自动创建，
                            // 必须手动触发原生浏览器创建（隐藏加载的关键）
                            b.createImmediately()
                        } catch (t: Throwable) {
                            Log.e(TAG, "create hidden browser failed", t)
                            complete(null)
                        }
                    }
                    if (SwingUtilities.isEventDispatchThread()) createTask.run()
                    else SwingUtilities.invokeAndWait(createTask)

                    // 就绪轮询：周期执行 readyProbeJs，结果经 cefQuery 回到 onQuery
                    poller = Thread({
                        try {
                            while (!finished.get() && !readySeen.get()) {
                                Thread.sleep(pollIntervalMs)
                                if (finished.get() || readySeen.get()) break
                                val b = browser
                                if (b != null) {
                                    SwingUtilities.invokeLater {
                                        if (!finished.get() && !readySeen.get()) {
                                            runCatching { b.executeJavaScript(readyProbeJs, url, 0) }
                                        }
                                    }
                                }
                            }
                        } catch (_: InterruptedException) {
                            // 完成/取消时退出轮询
                        }
                    }, "YunX-Headless-Poll").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        } catch (t: TimeoutCancellationException) {
            DiagnosticLog.warn(
                DiagnosticLog.NETWORK,
                "headless_timeout",
                "url=$url timeoutMs=$timeoutMs"
            )
            null
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "headless extract failed: url=$url", t)
            null
        }
    }
}
