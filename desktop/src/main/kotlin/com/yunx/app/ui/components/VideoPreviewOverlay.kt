package com.yunx.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.preview.HlsPreviewSession
import com.yunx.app.data.preview.LoopbackMediaProxy
import com.yunx.app.ui.BackHandler
import com.yunx.app.util.Log
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import java.util.Locale

/**
 * 视频预览播放器（M6）。
 *
 * 播放内核是 JavaFX `MediaView`，通过 [SwingPanel] 把 `JFXPanel` 嵌进 Compose：
 * 实测 MediaView 必须挂在「可见窗口的 Scene」里，否则 jfxmedia 的视频 preroll 会随机死锁
 * （`docs/PORT-PLAN-1.3.0.md` §9.2），这里 JFXPanel + 真实窗口天然满足。
 *
 * 播放源由 [prepareSource] 决定：
 * - 单文件 MP4 → [LoopbackMediaProxy] 的 `127.0.0.1` 代理（注入 Referer/UA/Cookie + Range 透传）；
 * - HLS（m3u8）→ [HlsPreviewSession] 边下边重封装成本地增长文件，播放器读 `file:` URI
 *   （jfxmedia 不能播 m3u8，也不吃 HTTP 上的 fragmented MP4）。
 */
@Composable
fun VideoPreviewOverlay(media: VideoMedia?, onDismiss: () -> Unit) {
    val link = media
    // 关闭时保留最后一次内容跑淡出，避免硬切
    var lastMedia by remember { mutableStateOf<VideoMedia?>(null) }
    if (link != null) lastMedia = link
    if (lastMedia == null) return
    val shown = lastMedia!!
    val visible = link != null

    var source by remember(shown) { mutableStateOf<PreviewSource?>(null) }
    var error by remember(shown) { mutableStateOf<String?>(null) }
    var fullscreen by remember(shown) { mutableStateOf(false) }

    // 关闭预览时如果还停在全屏，先把窗口还原（否则留下一个没有内容的空白全屏窗口）
    LaunchedEffect(visible) {
        if (!visible) fullscreen = false
    }

    // 全屏开关跟随窗口状态：进入时记住原布局（最大化/普通），退出时还原
    DisposableEffect(fullscreen) {
        PreviewFullscreen.set(fullscreen)
        onDispose { if (fullscreen) PreviewFullscreen.set(false) }
    }

    BackHandler(enabled = visible) {
        if (fullscreen) fullscreen = false else onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120))
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // 播放源与内容同生命周期：淡出动画结束、内容被移除时才释放
            // （HLS 会停止续写并删除临时文件；MP4 只注销代理登记）
            LaunchedEffect(shown) {
                // NonCancellable：用户在「准备中」关掉预览时，让准备动作跑完并拿到句柄再释放，
                // 否则 HLS 会话（续写线程 + 临时文件）会悬空
                val result = withContext(Dispatchers.IO + NonCancellable) {
                    runCatching { prepareSource(shown) }
                }
                if (!isActive) {
                    result.getOrNull()?.close()
                    return@LaunchedEffect
                }
                result.onSuccess { prepared ->
                    if (prepared == null) error = "该视频暂时无法在线预览" else source = prepared
                }.onFailure {
                    Log.e(TAG, "预览源准备失败: ${it.message}")
                    error = "视频预览失败：${it.message ?: "未知错误"}"
                }
            }
            DisposableEffect(shown) {
                onDispose {
                    // 先释放再清状态：同一个媒体再次打开时不能复用已关闭的会话
                    source?.close()
                    source = null
                    error = null
                }
            }

            val prepared = source
            when {
                error != null -> Text(
                    text = error!!,
                    color = Color(0xFFE6E6E6),
                    style = MaterialTheme.typography.bodyMedium
                )

                prepared == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("正在准备预览…", color = Color(0xFFE6E6E6))
                }

                else -> VideoPlayer(
                    uri = prepared.uri,
                    title = shown.fileName.ifBlank { shown.quality },
                    fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onClose = onDismiss
                )
            }

            // 关闭按钮固定在右上角（JFXPanel 是重量级组件，控件不能压在视频上，
            // 所以放在视频区域之外的表层；全屏时同样可见）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
            }
        }
    }
}

// ---- 播放器本体 ----

@Composable
private fun VideoPlayer(
    uri: String,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    val panel = remember { JFXPanel() }
    val controller = remember(uri) { FxVideoController(panel) }

    DisposableEffect(uri) {
        controller.open(uri)
        onDispose { controller.dispose() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val error = controller.error
            if (error != null) {
                Text(error, color = Color(0xFFE6E6E6))
            } else {
                SwingPanel(
                    background = Color.Black,
                    factory = { panel },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        PlayerControls(
            controller = controller,
            title = title,
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
            onClose = onClose
        )
    }
}

@Composable
private fun PlayerControls(
    controller: FxVideoController,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }

    val durationMs = controller.durationMs
    val seekable = durationMs > 0
    val positionMs = if (dragging) (dragValue * durationMs).toLong() else controller.positionMs
    val progress = if (seekable) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.togglePlay() }) {
                Icon(
                    imageVector = if (controller.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (controller.playing) "暂停" else "播放",
                    tint = Color.White
                )
            }
            Text(
                text = formatTime(positionMs),
                color = Color(0xFFDDDDDD),
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = progress,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    if (seekable) controller.seekTo((dragValue * durationMs).toLong())
                    dragging = false
                },
                enabled = seekable,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(
                text = if (seekable) formatTime(durationMs) else "--:--",
                color = Color(0xFFDDDDDD),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { controller.toggleMute() }) {
                Icon(
                    imageVector = if (controller.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = if (controller.muted) "取消静音" else "静音",
                    tint = Color.White
                )
            }
            Box {
                IconButton(onClick = { speedMenu = true }) {
                    Icon(Icons.Rounded.Speed, contentDescription = "播放速度", tint = Color.White)
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    SPEEDS.forEach { speed ->
                        DropdownMenuItem(
                            text = { Text("${speed}x") },
                            onClick = {
                                controller.setRate(speed)
                                speedMenu = false
                            }
                        )
                    }
                }
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏",
                    tint = Color.White
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
            }
        }
        if (title.isNotBlank()) {
            Text(
                text = title,
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

// ---- JavaFX 控制 ----

/**
 * 把 JavaFX 播放器的属性桥接成 Compose 状态：监听器都在 FX 线程触发，
 * 直接写 snapshot state（Compose 快照写入跨线程安全），避免轮询。
 */
private class FxVideoController(private val panel: JFXPanel) {
    var playing by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var muted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private var player: MediaPlayer? = null
    private var mediaView: MediaView? = null
    private var sceneWidth = 0.0
    private var sceneHeight = 0.0
    private var disposed = false

    fun open(uri: String) {
        JavaFxRuntime.ensureInitialized()
        Platform.runLater {
            if (disposed) return@runLater
            runCatching {
                val loaded = Media(uri)
                val fxPlayer = MediaPlayer(loaded)
                player = fxPlayer

                val view = MediaView(fxPlayer)
                view.isPreserveRatio = true
                mediaView = view
                // Scene 尺寸不能直接 setWidth（包私有），只能按需重建 Scene 来跟随面板尺寸
                panel.background = java.awt.Color.BLACK
                panel.addComponentListener(object : ComponentAdapter() {
                    override fun componentResized(event: ComponentEvent) {
                        Platform.runLater { applyScene() }
                    }
                })

                loaded.errorProperty().addListener { _, _, err ->
                    if (err != null) error = "媒体加载失败：${err.message}"
                }
                fxPlayer.setOnError { error = "播放失败：${fxPlayer.error?.message}" }
                fxPlayer.setOnReady {
                    durationMs = toMillis(loaded.duration)
                    fxPlayer.play()
                }
                fxPlayer.currentTimeProperty().addListener { _, _, time ->
                    positionMs = toMillis(time)
                }
                fxPlayer.statusProperty().addListener { _, _, status ->
                    playing = status == MediaPlayer.Status.PLAYING
                }
                fxPlayer.muteProperty().addListener { _, _, value -> muted = value }

                durationMs = toMillis(loaded.duration)
                applyScene()
                fxPlayer.play()
            }.onFailure {
                Log.e(TAG, "JavaFX 播放器创建失败: ${it.message}")
                error = "播放器初始化失败：${it.message}"
            }
        }
    }

    /** 在 FX 线程按面板当前尺寸重建 Scene（尺寸没变就跳过） */
    private fun applyScene() {
        val view = mediaView ?: return
        val width = panel.width.toDouble()
        val height = panel.height.toDouble()
        if (width <= 0.0 || height <= 0.0) return
        if (width == sceneWidth && height == sceneHeight) return
        sceneWidth = width
        sceneHeight = height
        val root = StackPane(view).apply {
            background = Background(BackgroundFill(javafx.scene.paint.Color.BLACK, null, null))
        }
        val scene = Scene(root, width, height)
        view.fitWidthProperty().bind(scene.widthProperty())
        view.fitHeightProperty().bind(scene.heightProperty())
        panel.scene = scene
    }

    fun togglePlay() = post { p ->
        when (p.status) {
            MediaPlayer.Status.PLAYING -> p.pause()
            MediaPlayer.Status.PAUSED, MediaPlayer.Status.READY, MediaPlayer.Status.STOPPED -> p.play()
            else -> p.play()
        }
    }

    fun seekTo(ms: Long) = post { p -> p.seek(javafx.util.Duration.millis(ms.toDouble())) }

    fun setRate(value: Double) = post { p -> p.rate = value }

    fun toggleMute() = post { p -> p.isMute = !p.isMute }

    fun dispose() {
        disposed = true
        val fxPlayer = player
        player = null
        mediaView = null
        Platform.runLater {
            runCatching { fxPlayer?.stop() }
            runCatching { fxPlayer?.dispose() }
            runCatching { panel.scene = null }
        }
    }

    private fun post(block: (MediaPlayer) -> Unit) {
        Platform.runLater {
            val p = player ?: return@runLater
            runCatching { block(p) }
        }
    }
}

/** JavaFX toolkit 懒初始化：创建首个 JFXPanel 即完成启动（Compose 的 UI 线程就是 EDT） */
private object JavaFxRuntime {
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        // 已经在 EDT 上（Compose 的组合线程）→ 直接创建；否则转投 EDT。
        // 两种分支都不能反过来：在 EDT 里 invokeAndWait 会自锁。
        if (SwingUtilities.isEventDispatchThread()) {
            JFXPanel()
        } else {
            SwingUtilities.invokeAndWait { JFXPanel() }
        }
        Platform.setImplicitExit(false)
        initialized = true
    }
}

/** 全屏开关：Main 把主窗口的 [WindowState] 注入进来，播放器切窗口布局 */
internal object PreviewFullscreen {
    var windowState: WindowState? = null

    /** 进入全屏前的窗口布局（最大化 / 普通），退出时原样还原 */
    private var previous: WindowPlacement? = null

    fun set(on: Boolean) {
        val state = windowState ?: return
        if (on) {
            if (state.placement == WindowPlacement.Fullscreen) return
            previous = state.placement
            state.placement = WindowPlacement.Fullscreen
        } else {
            val restore = previous ?: return
            previous = null
            state.placement = restore
        }
    }
}

/** 播放源：直链代理 URL 或 HLS 会话（读本地增长文件） */
private sealed class PreviewSource {
    abstract val uri: String

    fun close() {
        if (this is Hls) session.cancel()
    }

    class Direct(override val uri: String) : PreviewSource()

    class Hls(val session: HlsPreviewSession) : PreviewSource() {
        override val uri: String get() = session.uri
    }
}

/** 阻塞解析播放源（调用方在 IO 线程执行）；null 表示不可预览 */
private fun prepareSource(media: VideoMedia): PreviewSource? {
    if (media.isHls) {
        val session = HlsPreviewSession.prepare(media.url, media.headers) ?: return null
        return PreviewSource.Hls(session)
    }
    val url = LoopbackMediaProxy.mediaUrl(media.url, media.headers) ?: return null
    return PreviewSource.Direct(url)
}

private const val TAG = "YunX-VideoPreview"

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

private fun toMillis(duration: javafx.util.Duration?): Long {
    if (duration == null) return 0L
    val ms = duration.toMillis()
    return if (ms.isFinite() && ms > 0) ms.toLong() else 0L
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
