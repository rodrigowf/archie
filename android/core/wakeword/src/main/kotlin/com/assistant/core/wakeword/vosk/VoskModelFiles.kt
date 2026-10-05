package com.assistant.core.wakeword.vosk

import com.assistant.core.wakeword.WakeTuning
import com.assistant.core.wakeword.ports.VoskModelStore
import java.io.File
import java.io.InputStream

/**
 * Vosk model extraction, pure file logic (old `VoskModelLoader.shouldExtract` / `extractTree` /
 * `assetFileList`). Uses the old app's `filesDir/vosk-model/` and `.stamp` value so the lite
 * upgrade over the old app finds the 68 MB model already extracted (spec 14 §1.2).
 */
object VoskModelFiles : VoskModelStore {
    override val assetRoot: String = WakeTuning.VOSK_MODEL_ASSET_ROOT
    override val extractDirName: String = WakeTuning.VOSK_MODEL_DIR
    override val stamp: String = WakeTuning.VOSK_MODEL_STAMP
    override val stampFileName: String = WakeTuning.VOSK_STAMP_FILE

    /** True unless [target] is a directory whose stamp file holds exactly [expectedStamp]. */
    override fun shouldExtract(target: File, expectedStamp: String): Boolean {
        if (!target.isDirectory) return true
        val stampFile = File(target, stampFileName)
        if (!stampFile.isFile) return true
        return stampFile.readText() != expectedStamp
    }

    /** Copies every source to `target/<relPath>` (overwriting), then writes the stamp last. */
    override fun extractTree(sources: Map<String, () -> InputStream>, target: File, stamp: String) {
        target.mkdirs()
        sources.forEach { (rel, open) ->
            val out = File(target, rel)
            out.parentFile?.mkdirs()
            open().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        File(target, stampFileName).writeText(stamp)
    }

    /**
     * File leaves under [assetRoot], relative to it. [list] follows `AssetManager.list` semantics:
     * children for a directory, empty for a file (an empty directory is skipped).
     */
    override fun assetFileList(list: (String) -> List<String>): List<String> {
        val out = mutableListOf<String>()
        fun walk(assetPath: String, relPath: String) {
            val children = list(assetPath)
            if (children.isEmpty()) {
                if (relPath.isNotEmpty()) out += relPath
                return
            }
            children.forEach { child -> walk("$assetPath/$child", if (relPath.isEmpty()) child else "$relPath/$child") }
        }
        walk(assetRoot, "")
        return out
    }
}
