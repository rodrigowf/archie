package com.assistant.core.wakeword.parity

import com.assistant.core.testing.ArchieRoot
import com.assistant.core.testing.OldConstants
import com.assistant.core.testing.PinsConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Build-level pins of inv04 §4.2 that live outside Kotlin code. */
class WakewordBuildPinsTest {

    /** The patched `libvosk.so` must match the AAR; bumping it is decision Q4 (spec 14 §9). */
    @Test
    fun voskAndroidIsPinnedTo0347InTheCatalog() {
        val catalog = ArchieRoot.file("gradle/libs.versions.toml").readText()
        val pinned = Regex("""(?m)^vosk\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1)
        assertEquals(OldConstants["vosk.android_version"].value.toString().trim('"'), pinned)
        assertEquals("0.3.47", pinned)
    }

    /** `Model(path)` needs raw files: the model directory is stored uncompressed (old `A/build.gradle.kts:46`). */
    @Test
    @Ignore("A-07")
    @PinsConstant("vosk.no_compress")
    fun modelDirectoryIsNotCompressed() {
        val build = ArchieRoot.file("core/wakeword/build.gradle.kts").readText()
        assertTrue("noCompress for the model dir in :core:wakeword", Regex("""noCompress\s*\+=\s*"vosk-model-small-en-us-0\.15"""").containsMatchIn(build))
    }
}
