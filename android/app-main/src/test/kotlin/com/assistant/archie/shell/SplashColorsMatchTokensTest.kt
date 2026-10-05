package com.assistant.archie.shell

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.R
import com.assistant.design.DarkColorScheme
import com.assistant.design.LightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The window/splash colors are the tokens' surfaces (no purple/white launch flash, inv03 §8 bug 10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SplashColorsMatchTokensTest {
    private fun color(id: Int) = ApplicationProvider.getApplicationContext<Application>().getColor(id)

    @Test fun light() {
        assertEquals(LightColorScheme.surface.toArgb(), color(R.color.archie_window_background))
        assertEquals(DarkColorScheme.primaryContainer.toArgb(), color(R.color.archie_mark_tile))
        assertEquals(DarkColorScheme.onPrimaryContainer.toArgb(), color(R.color.archie_mark_arch))
        assertEquals(DarkColorScheme.tertiary.toArgb(), color(R.color.archie_mark_dot))
    }

    @Config(qualifiers = "night")
    @Test fun dark() {
        assertEquals(DarkColorScheme.surface.toArgb(), color(R.color.archie_window_background))
    }
}
