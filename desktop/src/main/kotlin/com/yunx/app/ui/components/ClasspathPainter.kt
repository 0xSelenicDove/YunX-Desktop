package com.yunx.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap

/** Existing bundled PNG assets, decoded once per resource name. */
@Composable
fun rememberClasspathPainter(path: String): BitmapPainter = remember(path) {
    val bytes = requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) {
        "Missing image resource: $path"
    }.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
