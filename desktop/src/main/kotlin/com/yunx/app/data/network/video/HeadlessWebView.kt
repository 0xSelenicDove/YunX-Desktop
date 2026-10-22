package com.yunx.app.data.network.video

import com.yunx.app.util.DiagnosticLog

/**
 * 隐藏浏览器取数（桌面占位实现）。
 *
 * 上游用 Android 隐藏 WebView 打开作品页、注入脚本并回读结果（抖音的 `acrawler` 挑战
 * 只能由真实浏览器跑完）。桌面版将用 JCEF 的隐藏 `CefBrowser` + `CefMessageRouter` 实现
 * 同一语义（见 docs/PORT-PLAN-1.3.0.md §3.5），属于 M6 范围。
 *
 * 当前返回 null：抖音解析器会自动落回 HTTP/SSR 路线，等价于「网页兜底不可用」，
 * 不影响其它平台。
 */
object HeadlessWebView {

    suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long = 25_000L,
        pollIntervalMs: Long = 350L,
        extraDocumentStartJs: List<String> = emptyList()
    ): String? {
        DiagnosticLog.warn(
            DiagnosticLog.NETWORK,
            "headless_unavailable",
            "JCEF 隐藏浏览器尚未接入，跳过网页兜底：url=$url"
        )
        return null
    }
}
