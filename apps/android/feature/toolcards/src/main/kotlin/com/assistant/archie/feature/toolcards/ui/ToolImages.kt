package com.assistant.archie.feature.toolcards.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.assistant.archie.feature.toolcards.LoadedToolImage
import com.assistant.archie.feature.toolcards.LocalToolImageLoader
import com.assistant.archie.feature.toolcards.ToolImageSizing
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.components.IconButtonStyle
import com.assistant.core.design.components.Spinner
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.protocol.ToolImage

/** Test tags: `tool-image:<i>` (loaded), `tool-image-loading:<i>`, `tool-image-unavailable:<i>`, the viewer. */
const val TOOL_IMAGE_TAG: String = "tool-image"
const val TOOL_IMAGE_VIEWER_TAG: String = "tool-image-viewer"

/** TC-5: the bounded height of a picture in a card. */
private val MAX_IMAGE_HEIGHT = 320.dp

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    class Loaded(val image: LoadedToolImage) : ImageLoad
}

/**
 * The pictures of a tool result (spec 12 TC-5), one under the other: fitted to the card's width with
 * their aspect ratio, at most [MAX_IMAGE_HEIGHT] tall, never upscaled. This composes only inside an
 * expanded card's output region, so nothing is fetched for a collapsed card. Tapping a picture opens it
 * full size ([ToolImageViewer]).
 */
@Composable
fun ToolImages(images: List<ToolImage>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<ToolImage?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEachIndexed { i, img -> ToolImageView(img, i, images.size) { open = img } }
    }
    open?.let { img -> ToolImageViewer(img) { open = null } }
}

@Composable
private fun ToolImageView(image: ToolImage, index: Int, count: Int, onOpen: () -> Unit) {
    val loader = LocalToolImageLoader.current
    val density = LocalDensity.current
    val label = if (count > 1) "Image ${index + 1} of $count" else "Image"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val boxW = constraints.maxWidth
        val boxH = with(density) { MAX_IMAGE_HEIGHT.roundToPx() }
        val state by produceState<ImageLoad>(
            initialValue = loader?.cached(image.url, boxW, boxH)?.let { ImageLoad.Loaded(it) }
                ?: if (loader == null) ImageLoad.Failed else ImageLoad.Loading,
            image.url, boxW, boxH, loader,
        ) {
            if (value is ImageLoad.Loaded || loader == null) return@produceState
            value = loader.load(image.url, boxW, boxH)?.let { ImageLoad.Loaded(it) } ?: ImageLoad.Failed
        }
        when (val s = state) {
            ImageLoad.Loading -> Box(
                Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp))
                    .background(ArchieTheme.colors.surfaceContainer)
                    .testTag("tool-image-loading:$index")
                    .semantics { contentDescription = "$label, loading" },
                contentAlignment = Alignment.Center,
            ) { Spinner(size = 16.dp) }
            ImageLoad.Failed -> Unavailable(Modifier.testTag("tool-image-unavailable:$index"))
            is ImageLoad.Loaded -> {
                val (w, h) = ToolImageSizing.fit(s.image.width, s.image.height, boxW, boxH)
                val bitmap = remember(s.image) { s.image.bitmap.asImageBitmap() }
                Image(
                    bitmap,
                    contentDescription = "$label, tap to open full size",
                    modifier = Modifier
                        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Image, onClickLabel = "Open full size", onClick = onOpen)
                        .testTag("$TOOL_IMAGE_TAG:$index"),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

/** TC-5: a small placeholder instead of a broken-image glyph. */
@Composable
private fun Unavailable(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(ArchieTheme.colors.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ArchieIcons.Image, contentDescription = null, Modifier.size(16.dp).alpha(0.7f))
        Text("Image unavailable", Modifier.alpha(0.85f), style = ArchieTheme.typography.bodySmall)
    }
}

/**
 * The full-size view of a tool image: a full-screen dialog, the picture fitted to the screen (decoded
 * for the screen size), pinch to zoom and drag while zoomed, double-tap to reset, tap or Back to close.
 */
@Composable
fun ToolImageViewer(image: ToolImage, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val loader = LocalToolImageLoader.current
        val cfg = LocalConfiguration.current
        val density = LocalDensity.current
        val boxW = with(density) { cfg.screenWidthDp.dp.roundToPx() }
        val boxH = with(density) { cfg.screenHeightDp.dp.roundToPx() }
        val state by produceState<ImageLoad>(
            initialValue = loader?.cached(image.url, boxW, boxH)?.let { ImageLoad.Loaded(it) }
                ?: if (loader == null) ImageLoad.Failed else ImageLoad.Loading,
            image.url, boxW, boxH, loader,
        ) {
            if (value is ImageLoad.Loaded || loader == null) return@produceState
            value = loader.load(image.url, boxW, boxH)?.let { ImageLoad.Loaded(it) } ?: ImageLoad.Failed
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black).testTag(TOOL_IMAGE_VIEWER_TAG)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { scale = 1f; offset = Offset.Zero },
                        onTap = { if (scale <= 1f) onDismiss() },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                ImageLoad.Loading -> Spinner(size = 24.dp, color = Color.White)
                ImageLoad.Failed -> Unavailable()
                is ImageLoad.Loaded -> {
                    val bitmap = remember(s.image) { s.image.bitmap.asImageBitmap() }
                    Image(
                        bitmap,
                        contentDescription = "Image, full size",
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            ArchieIconButton(
                ArchieIcons.Close,
                "Close",
                onDismiss,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                style = IconButtonStyle.Standard,
                tint = Color.White,
            )
        }
    }
}
