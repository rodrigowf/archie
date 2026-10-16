package com.assistant.archie.tv.shell

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.tv.R
import com.assistant.design.DarkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The window background is the tokens' dark surface (no white flash before TvTheme paints). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WindowColorsMatchTokensTest {
    @Test fun darkSurface() {
        val color = ApplicationProvider.getApplicationContext<Application>().getColor(R.color.archie_tv_window_background)
        assertEquals(DarkColorScheme.surface.toArgb(), color)
    }
}
