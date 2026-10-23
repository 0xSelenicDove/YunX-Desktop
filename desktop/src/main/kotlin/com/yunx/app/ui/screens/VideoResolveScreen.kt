package com.yunx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.components.ImageViewerOverlay
import com.yunx.app.ui.components.VideoPreviewOverlay
import com.yunx.app.ui.viewmodel.VideoResolveUiState
import com.yunx.app.ui.viewmodel.VideoResolveViewModel

/**
 * 视频解析页宿主：加载 / 错误 / 结果三种状态 + 全屏看图。
 *
 * 结果页本身是纯展示组件（[VideoResultSection]），状态与多选都留在 ViewModel，
 * 这样切 Tab 再回来不会丢选择。
 *
 * @param onDownload 下载回调：本里程碑只交付解析与结果页，下载链路在 Part 5 接入
 */
@Composable
fun VideoResolveScreen(
    viewModel: VideoResolveViewModel,
    onBack: () -> Unit,
    onDownload: (List<VideoMedia>) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState
    // 预览同时带上该图片的请求头（视频图集 CDN 有防盗链）
    var preview by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    // 视频预览：非空时铺播放器覆盖层
    var videoPreview by remember { mutableStateOf<VideoMedia?>(null) }

    BackHandler { onBack() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (state) {
            VideoResolveUiState.Idle -> Unit

            VideoResolveUiState.Loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在解析…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is VideoResolveUiState.Error -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = { viewModel.retry() }) { Text("重试") }
                TextButton(onClick = onBack) { Text("返回") }
            }

            is VideoResolveUiState.Success -> VideoResultSection(
                result = state.result,
                selected = viewModel.selected,
                onToggleSelect = viewModel::toggleSelect,
                onSelectAll = viewModel::selectAll,
                onClearSelection = viewModel::clearSelection,
                onDownloadSelected = {
                    val picked = state.result.medias.filterIndexed { index, _ ->
                        index in viewModel.selected
                    }
                    if (picked.isNotEmpty()) onDownload(picked)
                },
                onDownloadMedia = { media -> onDownload(listOf(media)) },
                onPreviewImage = { url, headers -> preview = url to headers },
                onPreviewVideo = { media -> videoPreview = media },
                onBack = onBack
            )
        }

        // 全屏看图（点图集单元格触发）；Esc 关闭由 Overlay 自己注册的 BackHandler 处理
        ImageViewerOverlay(
            url = preview?.first,
            headers = preview?.second ?: emptyMap(),
            onDismiss = { preview = null }
        )

        // 视频预览播放器（JavaFX MediaView）；Esc 先退全屏再关闭，逻辑在 Overlay 内
        VideoPreviewOverlay(
            media = videoPreview,
            onDismiss = { videoPreview = null }
        )
    }
}
