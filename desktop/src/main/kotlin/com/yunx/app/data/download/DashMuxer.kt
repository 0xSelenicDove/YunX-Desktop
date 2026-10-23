package com.yunx.app.data.download

import com.yunx.app.util.Log
import org.mp4parser.Container
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.io.FileOutputStream

/**
 * MP4 分轨合轨 / 重封装（桌面端纯 Java 实现）。
 *
 * 用途：哔哩哔哩的高清是 **DASH 音视频分离**的两路流（视频 `.m4s` + 音频 `.m4s`），
 * 各自下载完必须合轨才是一个能播放的文件；微博动图则是 QuickTime 封装（`.mov`），
 * 内嵌到动态照片前需要换成标准 mp4 容器。
 *
 * ★ 为什么用 mp4parser 而不是 MediaCodec：
 *   - `android.media.MediaExtractor` / `MediaMuxer` / `MediaCodec` 只存在于 Android 运行时，
 *     桌面 JVM 根本没有这些类；桌面想沿用「逐样本重封装」就只能引 ffmpeg/mp4box 的 JNI 产物，
 *     会让 jpackage 打包、跨平台分发复杂很多。
 *   - isoparser 是纯 Java 的 ISO BMFF 解析/写出库（无原生库），能直接读取 fragmented MP4
 *     的 moof/trun 并重建出标准 moov，做到「只搬样本、不重编码」。
 *   - 代价：mp4parser 重建的 moov 比 Android 原生 MediaMuxer 朴素（上游 Android 版正是因为
 *     isoparser 对 B 站 fragmented MP4 的 moov 重建不够规范才改用 MediaMuxer），个别严格的
 *     播放器可能对时间轴支持不佳 —— 桌面端没有原生封装器兜底，这是纯 Java 方案的取舍。
 */
object DashMuxer {

    private const val TAG = "YunX-Dash"

    /** ISO BMFF 的轨道类型：视频 / 声音（hdlr box 的 handlerType） */
    private const val HANDLER_VIDEO = "vide"
    private const val HANDLER_AUDIO = "soun"

    /**
     * 把分离的视频轨与音频轨（哔哩哔哩 DASH，fragmented MP4）合轨成一个普通 MP4。
     * 只重封装、不重编码。成功返回 true。
     *
     * 同步 IO 操作，调用方应在 IO 线程执行。
     *
     * @param onProgress 0f..1f 的粗略进度（可为空实现）
     */
    fun mux(video: File, audio: File, out: File, onProgress: (Float) -> Unit = {}): Boolean =
        runCatching {
            if (!video.isFile || video.length() == 0L || !audio.isFile || audio.length() == 0L) {
                Log.w(TAG, "合轨输入缺失或为空 video=${video.absolutePath} audio=${audio.absolutePath}")
                return@runCatching false
            }
            onProgress(0f)
            // 两个输入各自只取需要的轨道；任一侧读不出轨道都明确失败，不产出无声/无画面的半成品
            val videoMovie = MovieCreator.build(video.absolutePath)
            onProgress(0.2f)
            val audioMovie = MovieCreator.build(audio.absolutePath)
            onProgress(0.4f)

            val videoTrack = videoMovie.tracks.firstOrNull { it.handler == HANDLER_VIDEO }
            val audioTrack = audioMovie.tracks.firstOrNull { it.handler == HANDLER_AUDIO }
            if (videoTrack == null || audioTrack == null) {
                Log.w(
                    TAG,
                    "合轨失败：轨道缺失 video=${videoTrack != null} audio=${audioTrack != null}" +
                        "（文件可能不是预期的 DASH 分轨）"
                )
                return@runCatching false
            }

            val movie = Movie()
            // mvhd 的矩阵沿用视频侧，尽量保留竖屏/镜像等展示信息
            movie.matrix = videoMovie.matrix
            movie.addTrack(videoTrack)
            movie.addTrack(audioTrack)

            val container = DefaultMp4Builder().build(movie)
            onProgress(0.7f)
            val ok = writeContainer(container, out)
            if (ok) {
                onProgress(1f)
                Log.d(
                    TAG,
                    "合轨完成 video=${video.length()} audio=${audio.length()} out=${out.length()}"
                )
            }
            ok
        }.onFailure {
            Log.w(TAG, "合轨异常 video=${video.name} audio=${audio.name}: ${it.message}")
        }.getOrDefault(false)

    /** 把任意 MP4/MOV 重新封装成 MP4（微博动图 .mov 用），成功返回 true */
    fun remuxToMp4(input: File, out: File): Boolean =
        runCatching {
            if (!input.isFile || input.length() == 0L) {
                Log.w(TAG, "重封装输入缺失或为空: ${input.absolutePath}")
                return@runCatching false
            }
            val source = MovieCreator.build(input.absolutePath)
            if (source.tracks.isEmpty()) {
                Log.w(TAG, "重封装失败：${input.name} 没有可用轨道")
                return@runCatching false
            }
            val movie = Movie()
            movie.matrix = source.matrix
            source.tracks.forEach { movie.addTrack(it) }
            val ok = writeContainer(DefaultMp4Builder().build(movie), out)
            if (ok) Log.d(TAG, "重封装完成 in=${input.length()} out=${out.length()}")
            ok
        }.onFailure {
            Log.w(TAG, "重封装异常 in=${input.name}: ${it.message}")
        }.getOrDefault(false)

    /**
     * 把 [container] 写成 [out]。
     *
     * DefaultMp4Builder 的写出过程内部没有进度回调，所以进度只能按阶段上报；
     * 真正的样本拷贝发生在 [Container.writeContainer] 里（一帧一帧读、一帧一帧写）。
     */
    private fun writeContainer(container: Container, out: File): Boolean {
        val ok = runCatching {
            FileOutputStream(out).use { stream -> container.writeContainer(stream.channel) }
            out.isFile && out.length() > 0L
        }.onFailure {
            Log.w(TAG, "写出 ${out.name} 失败: ${it.message}")
        }.getOrDefault(false)
        // 失败产物不留半成品（异常时可能只写了 ftyp/moov 的一部分）
        if (!ok) runCatching { out.delete() }
        return ok
    }
}
