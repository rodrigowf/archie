package com.assistant.archie.feature.toolcards

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.assistant.archie.feature.toolcards.ui.TOOL_IMAGE_VIEWER_TAG
import com.assistant.archie.feature.toolcards.ui.ToolCard
import com.assistant.core.conversation.ToolBlock
import com.assistant.core.conversation.ToolStatus
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.protocol.ToolImage
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Spec 12 TC-5 in the UI: pictures in the expanded output region, lazy, placeholder on failure, full-size view. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class ToolImagesUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetTiming() = ToolTiming.reset()

    private val url = "/api/tool-images/${"a".repeat(64)}.png"

    private fun read(path: String, output: String?, images: List<ToolImage> = emptyList()) = ToolBlock(
        id = "r1",
        toolUseId = "toolu_r",
        toolName = "Read",
        toolInput = buildJsonObject { put("file_path", path) },
        status = ToolStatus.DONE,
        output = output,
        images = images,
    )

    private fun png(w: Int = 40, h: Int = 20): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private class CountingFetch(private val bytes: ByteArray?) {
        val urls = mutableListOf<String>()
        suspend fun fetch(u: String): ByteArray? { urls += u; return bytes }
    }

    private fun setCard(block: ToolBlock, fetch: CountingFetch?, expanded: () -> Boolean) {
        val loader = fetch?.let { f -> ToolImageLoader(fetch = { f.fetch(it) }, io = Dispatchers.Unconfined) }
        compose.setContent {
            CompositionLocalProvider(LocalToolImageLoader provides loader) {
                ArchieTheme(reduceMotion = true) { ToolCard(block, expanded = expanded(), onToggle = {}) }
            }
        }
    }

    @Test fun textReadShowsTheFileText() {
        setCard(read("/tmp/notes.md", "     1\thello"), null) { true }
        compose.onNodeWithTag("tool-output:output").assertIsDisplayed()
        compose.onNodeWithText("hello", substring = true).assertIsDisplayed()
    }

    @Test fun imagesLoadOnlyOnceTheCardIsExpanded() {
        val f = CountingFetch(png())
        var open by mutableStateOf(false)
        setCard(read("/tmp/a.png", "", listOf(ToolImage(url, "image/png"))), f) { open }
        compose.waitForIdle()
        assertEquals("collapsed: nothing fetched", emptyList<String>(), f.urls)
        open = true
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.waitForIdle(); f.urls.isNotEmpty() }
        assertEquals(listOf(url), f.urls)
        compose.onNodeWithTag("tool-image:0").assertIsDisplayed()
        // An image result is output, not "No output".
        compose.onNodeWithText("No output").assertDoesNotExist()
        // Tap → full-size viewer; Close dismisses it.
        compose.onNodeWithTag("tool-image:0").performClick()
        compose.onNodeWithTag(TOOL_IMAGE_VIEWER_TAG).assertExists()
        compose.onNodeWithContentDescription("Image, full size").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag(TOOL_IMAGE_VIEWER_TAG).assertDoesNotExist()
    }

    @Test fun failedImageShowsAPlaceholderAndTheTextStillShows() {
        setCard(read("/tmp/a.png", "Note: cropped", listOf(ToolImage(url, "image/png"))), CountingFetch(null)) { true }
        compose.waitForIdle()
        compose.onNodeWithTag("tool-image-unavailable:0").assertIsDisplayed()
        compose.onNodeWithText("Image unavailable").assertIsDisplayed()
        compose.onNodeWithText("Note: cropped", substring = true).assertIsDisplayed()
    }

    @Test fun withoutALoaderImagesAreUnavailableNotBroken() {
        setCard(read("/tmp/a.png", "", listOf(ToolImage(url, "image/png"))), null) { true }
        compose.onNodeWithText("Image unavailable").assertIsDisplayed()
    }

    @Test fun decodeDownsamplesToTheBox() {
        val img = ToolImageLoader.decode(png(1600, 800), 400, 640)!!
        assertEquals(1600, img.width)
        assertEquals(800, img.height)
        assertEquals(400, img.bitmap.width)       // inSampleSize 4
        assertEquals(null, ToolImageLoader.decode(byteArrayOf(1, 2, 3), 400, 640))
    }
}
