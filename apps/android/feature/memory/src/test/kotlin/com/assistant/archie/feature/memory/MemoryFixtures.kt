package com.assistant.archie.feature.memory

import com.assistant.core.model.MemoryNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Test data: the live memory tree (read-only GET from the Jetson, 138 files) and the link fixture. */
object MemoryFixtures {
    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("memory/$name")) { "missing test resource memory/$name" }.readText()

    /** `GET /api/memory/tree` as captured on 2026-10-04 (paths only). */
    val liveTree: List<MemoryNode> by lazy { parse(Json.parseToJsonElement(resource("live-tree.json")).jsonArray) }

    private fun parse(a: JsonArray): List<MemoryNode> = a.map { e ->
        val o = e as JsonObject
        MemoryNode(
            name = o["name"]!!.jsonPrimitive.content,
            path = o["path"]!!.jsonPrimitive.content,
            isDir = o["is_dir"]!!.jsonPrimitive.boolean,
            children = (o["children"] as? JsonArray)?.let { parse(it) },
        )
    }

    /** One real link of context/memory and what MEM-2 says it resolves to (gen_links.py). */
    data class LinkCase(val from: String, val href: String, val kind: String, val target: String, val fragment: String, val inTree: String)

    val links: List<LinkCase> by lazy {
        resource("memory-links.tsv").lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split('\t')
                LinkCase(f[0], f[1], f[2], f[3], f.getOrElse(4) { "" }, f.getOrElse(5) { "" })
            }
            .toList()
    }

    fun dir(name: String, path: String, vararg children: MemoryNode) = MemoryNode(name, path, true, children.toList())
    fun file(path: String) = MemoryNode(path.substringAfterLast('/'), path, false, null)

    /** The mockup's tree (phone (g1)) in the backend's order (folders first, alphabetical). */
    val mockTree = listOf(
        dir(
            "assistant", "assistant",
            dir(
                "architecture", "assistant/architecture",
                file("assistant/architecture/android_viewmodel.md"),
                file("assistant/architecture/orchestrator-vision.md"),
                file("assistant/architecture/refactor_methodology.md"),
                file("assistant/architecture/voice_subsystem.md"),
                file("assistant/architecture/wakeword_subsystem.md"),
            ),
            dir("devices", "assistant/devices", *Array(14) { file("assistant/devices/device_$it.md") }),
            dir("infrastructure", "assistant/infrastructure", *Array(11) { file("assistant/infrastructure/infra_$it.md") }),
            dir("plans", "assistant/plans", *Array(3) { file("assistant/plans/plan_$it.md") }),
            dir("utilities", "assistant/utilities", *Array(8) { file("assistant/utilities/util_$it.md") }),
            *Array(23) { file("assistant/note_$it.md") },
        ),
        dir("home", "home", *Array(9) { file("home/home_$it.md") }),
        dir("projects", "projects", *Array(22) { file("projects/project_$it.md") }),
        dir("rodrigo", "rodrigo", *Array(42) { file("rodrigo/note_$it.md") }),
        file("MEMORY.md"),
    )

    const val VOICE_DOC = """---
category: assistant/architecture
created: 2026-06-10
modified: 2026-10-02
tags: [voice, webrtc, refactor]
source: curated (refactor cycle)
references:
  - assistant/architecture/wakeword_subsystem.md
  - assistant/architecture/refactor_methodology.md
  - assistant/architecture/android_viewmodel.md
  - assistant/voice/voice_lifecycle_and_wake_after_stop.md
---
# Voice subsystem

Realtime voice runs browser ↔ OpenAI over WebRTC. The backend only signals, runs tools and saves transcripts.

## Lifecycle

1. `start_voice` mints an ephemeral token
2. `VoiceStateMachine` owns idle → listening → speaking
3. `end_voice` always runs cleanup, even when idle

```
IDLE ──start──▶ LISTENING ◀──▶ SPEAKING
  ▲                  │
  └──────end─────────┘
```

## Related

[wakeword_subsystem.md](wakeword_subsystem.md) · [refactor_methodology.md](./refactor_methodology.md)
"""
}
