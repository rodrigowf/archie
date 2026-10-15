package com.assistant.archie.feature.toolcards

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure size math of tool images (spec 12 TC-5). */
class ToolImageSizingTest {
    @Test fun sampleSizeIsTheLargestPowerOfTwoThatKeepsTheDrawnSize() {
        assertEquals(1, ToolImageSizing.sampleSize(800, 600, 900, 640))       // smaller than the box: full size
        assertEquals(1, ToolImageSizing.sampleSize(1600, 1200, 900, 640))     // ratio 1.875: halving would undershoot
        assertEquals(2, ToolImageSizing.sampleSize(1800, 1200, 900, 640))     // ratio 2
        assertEquals(4, ToolImageSizing.sampleSize(4000, 3000, 900, 640))     // ratio 4.69
        assertEquals(8, ToolImageSizing.sampleSize(1000, 6000, 900, 640))     // tall: bounded by height (9.4)
        assertEquals(1, ToolImageSizing.sampleSize(4000, 3000, 0, 640))       // box unknown
        assertEquals(1, ToolImageSizing.sampleSize(0, 0, 900, 640))
    }

    @Test fun fitKeepsTheAspectRatioAndNeverUpscales() {
        assertEquals(400 to 200, ToolImageSizing.fit(400, 200, 900, 640))     // small: natural size
        assertEquals(900 to 450, ToolImageSizing.fit(1800, 900, 900, 640))    // wide: card width
        assertEquals(320 to 640, ToolImageSizing.fit(1000, 2000, 900, 640))   // tall: bounded height
        assertEquals(0 to 0, ToolImageSizing.fit(0, 10, 900, 640))
    }
}
