package com.assistant.core.wakeword.parity

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Vosk model extraction (ports `VoskModelLoaderParityTest`; same dir + stamp as the old app, spec 14 §1.2). */
class VoskModelStoreParityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val store = wakeCore.modelStore

    @Test
    fun usesTheOldDirectoryStampAndAssetRoot() {
        assertEquals("vosk-model", store.extractDirName)
        assertEquals("vosk-model-small-en-us-0.15", store.stamp)
        assertEquals("vosk-model-small-en-us-0.15", store.assetRoot)
        assertEquals(".stamp", store.stampFileName)
    }

    @Test fun extractWhenTargetMissing() = assertTrue(store.shouldExtract(File(tmp.root, "none"), "v1"))

    @Test
    fun extractWhenStampMissing() {
        val d = tmp.newFolder("vosk-model")
        assertTrue(store.shouldExtract(d, "v1"))
    }

    @Test
    fun extractWhenStampMismatches() {
        val d = tmp.newFolder("vosk-model")
        File(d, ".stamp").writeText("v0")
        assertTrue(store.shouldExtract(d, "v1"))
    }

    @Test
    fun noExtractWhenStampMatches() {
        val d = tmp.newFolder("vosk-model")
        File(d, ".stamp").writeText("v1")
        assertFalse(store.shouldExtract(d, "v1"))
    }

    @Test
    fun extractTreeWritesFilesAndStamp() {
        val target = File(tmp.root, "vosk-model")
        store.extractTree(
            mapOf<String, () -> InputStream>(
                "README" to { "the readme".byteInputStream() },
                "am/final.mdl" to { byteArrayOf(1, 2, 3).inputStream() },
                "graph/phones/word_boundary.int" to { "1 2 3 4".byteInputStream() },
            ),
            target, "v1",
        )
        assertEquals("the readme", File(target, "README").readText())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(target, "am/final.mdl").readBytes())
        assertEquals("1 2 3 4", File(target, "graph/phones/word_boundary.int").readText())
        assertEquals("v1", File(target, ".stamp").readText())
    }

    @Test
    fun extractTreeOverwritesAndHandlesEmptyStreams() {
        val target = File(tmp.root, "vosk-model")
        store.extractTree(mapOf<String, () -> InputStream>("README" to { "first".byteInputStream() }), target, "v1")
        store.extractTree(
            mapOf<String, () -> InputStream>(
                "README" to { "second".byteInputStream() },
                "ivector/online_cmvn.conf" to { ByteArrayInputStream(ByteArray(0)) },
            ),
            target, "v1",
        )
        assertEquals("second", File(target, "README").readText())
        assertEquals(0L, File(target, "ivector/online_cmvn.conf").length())
    }

    @Test
    fun assetFileListFlattensTheTree() {
        val root = "vosk-model-small-en-us-0.15"
        val tree = mapOf(
            root to listOf("README", "am", "graph"),
            "$root/am" to listOf("final.mdl"),
            "$root/graph" to listOf("phones"),
            "$root/graph/phones" to listOf("word_boundary.int"),
        )
        val files = store.assetFileList { tree[it] ?: emptyList() }
        assertEquals(listOf("README", "am/final.mdl", "graph/phones/word_boundary.int"), files.sorted())
    }
}
