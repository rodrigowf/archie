package com.assistant.core.protocol

import com.assistant.core.model.ConfigPatch
import com.assistant.core.model.HarnessProvider
import com.assistant.core.model.LiveStatus
import com.assistant.core.model.SessionConfig
import com.assistant.core.model.VoiceConnectionType
import com.assistant.core.model.WorkingDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** REST bodies of inv01 §3, using the documented (and LIVE-verified) example payloads. */
class RestDtoTest {
    private inline fun <reified T> decode(text: String): T = RestJson.decodeFromString(text)

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun sessionsAndPool() {
        val list = decode<List<SessionInfoDto>>(
            """[{"session_id":"528dbf6f","started_at":"2026-08-28T07:57:31.607864+00:00","last_activity":"2026-08-28T21:18:36.502531+00:00","title":"Lamp presets","message_count":190,"is_orchestrator":true,"provider":"claude","local_id":null,"future_field":1}]""",
        )
        val s = list.single().toModel()
        assertEquals("528dbf6f", s.sdkId)
        assertEquals(HarnessProvider.CLAUDE, s.provider)
        assertTrue(s.isOrchestrator)
        assertNull(s.localId)
        // Harness ids are registry-driven: Codex / Model Studio / unknown ids keep their provider.
        for (id in listOf("codex", "modelstudio", "future-cli")) {
            val row = decode<SessionInfoDto>("""{"session_id":"x","title":"t","message_count":1,"is_orchestrator":false,"provider":"$id"}""").toModel()
            assertEquals(id, row.provider?.wire)
        }
        assertNull(decode<SessionInfoDto>("""{"session_id":"x","title":"t","message_count":1,"is_orchestrator":false,"provider":null}""").toModel().provider)

        val pool = decode<List<PoolSessionDto>>(
            """[{"local_id":"3540ff69","sdk_session_id":"528dbf6f","status":"idle","cost":0.0,"turns":0,"title":"lamp presets test","is_orchestrator":false},{"local_id":"x","sdk_session_id":null,"status":"weird","cost":1,"turns":2,"title":null,"is_orchestrator":false}]""",
        ).map { it.toModel() }
        assertEquals(LiveStatus.IDLE, pool[0].status)
        assertNull("unknown status maps to null, not idle (ST-1)", pool[1].status)
        assertNull(pool[1].sdkId)
    }

    @Test
    fun messagesPage() {
        val page = decode<PaginatedMessagesDto>(
            """{"messages":[
                {"role":"user","text":"Refactor","blocks":[{"type":"text","text":"Refactor","tool_use_id":null,"tool_name":null,"tool_input":null,"output":null,"is_error":false}],"timestamp":"2026-10-03T12:00:00+00:00"},
                {"role":"assistant","text":"","blocks":[{"type":"tool_use","text":null,"tool_use_id":"t1","tool_name":"Read","tool_input":{"file_path":"u.py"},"output":null,"is_error":false}],"timestamp":null},
                {"role":"user","text":"","blocks":[{"type":"tool_result","tool_use_id":"t1","output":"def f(): ...","is_error":true}]},
                {"role":"assistant","text":"x","blocks":[{"type":"thinking","text":"hm"}]}
               ],"total_count":120,"has_more":true,"start_index":70}""",
        )
        assertEquals(4, page.messages.size)
        assertEquals(70, page.startIndex)
        assertTrue(page.hasMore)
        val tu = page.messages[1].blocks.single()
        assertEquals("t1", tu.toolUseId)
        assertEquals(obj("""{"file_path":"u.py"}"""), tu.toolInput)
        assertEquals(JsonPrimitive("def f(): ..."), page.messages[2].blocks.single().output)
        assertTrue(page.messages[2].blocks.single().isError)
        assertEquals("thinking", page.messages[3].blocks.single().type)
    }

    @Test
    fun configAndPartialUpdate() {
        val cfg = decode<ConfigDto>(
            """{"working_directory":"192.168.0.28:/home/rodrigo/assistant","working_directory_history":[{"id":"/home/rodrigo/assistant","path":"/home/rodrigo/assistant","label":"Jetson (local)","ssh_host":null,"ssh_user":null,"ssh_key":null,"claude_config_dir":null}],"enabled_mcps":[],"chrome_extension":true,"provider":"claude","default_model":"gpt-audio-mini","summarizer_model":"","harness_model":{"claude":"","qwen":""},"default_voice_provider":"openai","default_voice_model":"gpt-realtime-2","default_voice_name":"cedar","default_voice_transcription_language":"","default_voice_endpoint":"aistudio","voice_recording_enabled":false,"voice_vad_threshold":0.28,"voice_vad_min_silence_ms":1800,"voice_mic_gain":1.0}""",
        ).toModel()
        assertEquals("cedar", cfg.voice.voice)
        assertEquals("", cfg.voice.transcriptionLanguage)
        assertTrue("[] = all enabled (CFG-4) is preserved as empty", cfg.enabledMcps.isEmpty())
        assertEquals(1800, cfg.voiceVadMinSilenceMs)

        // a partial PUT carries only the set fields (CFG-1)
        val body = RestJson.encodeToJsonElement(ConfigPatch(voiceMicGain = 1.5, enabledMcps = listOf("a")).toDto())
        assertEquals(obj("""{"enabled_mcps":["a"],"voice_mic_gain":1.5}"""), body)
        val wd = RestJson.encodeToJsonElement(
            ConfigPatch(workingDirectoryHistory = listOf(WorkingDirectory("h:/p", "/p", "L", "h", "u", null, null))).toDto(),
        ).jsonObject
        assertEquals(obj("""{"working_directory_history":[{"id":"h:/p","path":"/p","label":"L","ssh_host":"h","ssh_user":"u"}]}"""), wd)
    }

    @Test
    fun sessionConfigPutBody() {
        val get = decode<SessionConfigDto>("""{"working_directory":null,"enabled_mcps":null,"chrome_extension":null,"provider":"qwen","harness_model":null}""").toModel()
        assertEquals("qwen", get.provider)
        // only changed keys; "inherit" is an explicit JSON null
        val body = SessionConfig(provider = "claude").toPutBody(inherit = setOf("harness_model"))
        assertEquals(JsonPrimitive("claude"), body["provider"])
        assertEquals(JsonNull, body["harness_model"])
        assertEquals(2, body.size)
    }

    @Test
    fun visualizationsMemoryUploads() {
        val v = decode<List<VisualizationInfoDto>>(
            """[{"path":"tarot-canvas/index.html","url":"/tarot-canvas/index.html","title":"Tarô","created":"2026-10-03T13:45:45.461139+00:00","modified":"2026-10-03T13:45:45.461139+00:00","size":27851}]""",
        ).single().toModel()
        assertEquals(27851L, v.size)
        val tree = decode<List<MemoryNodeDto>>(
            """[{"name":"assistant","path":"assistant","is_dir":true,"children":[{"name":"android","path":"assistant/android","is_dir":true,"children":[{"name":"a.md","path":"assistant/android/a.md","is_dir":false,"children":null}]}]}]""",
        ).map { it.toModel() }
        val leaf = tree[0].children!![0].children!![0]
        assertFalse(leaf.isDir)
        assertNull(leaf.children)
        val up = decode<UploadResultDto>("""{"filename":"r.pdf","path":"/x/r.pdf","url":"/uploads/2026-r.pdf","size":12,"content_type":"application/pdf"}""").toModel()
        assertEquals("application/pdf", up.contentType)
        assertEquals(obj("""{"available":false,"reason":"no TV"}"""), RestJson.encodeToJsonElement(CastProbeDto(false, "no TV")))
    }

    @Test
    fun orchestratorModelsAndVoice() {
        val m = decode<OrchestratorModelsDto>(
            """{"models":[{"provider":"openai","model_id":"gpt-audio","display_name":"GPT Audio","supports_audio":true,"supports_vision":false,"supports_tools":true,"max_tokens":4096,"context_window":null}],"audio_capable_models":["gpt-audio"],"default_model":"claude-sonnet-4-5-20250929"}""",
        )
        assertTrue(m.models.single().supportsAudio)
        assertNull(m.models.single().contextWindow)
        val voice = decode<VoiceModelsDto>(
            """{"providers":{"qwen":[{"id":"qwen3.5-omni-plus-realtime","label":"Qwen","voice":"Aiden","voices":[{"id":"Aiden","label":"Aiden","description":""}],"transcription_languages":[{"id":"en","label":"English","description":""}],"default_transcription_language":"en","default":true}]},"default_provider":"openai","default_model":"gpt-realtime"}""",
        )
        assertEquals("Aiden", voice.providers["qwen"]!!.single().voices.single().id)
        val session = decode<VoiceSessionDto>(
            """{"connection_info":{"connection_type":"webrtc","endpoint":"https://api.openai.com/v1/realtime/calls?model=gpt-realtime","ephemeral_token":"ek_1","expires_at":1759500000,"audio_in_format":{"sample_rate":24000,"encoding":"pcm16"},"audio_out_format":{"sample_rate":24000,"encoding":"pcm16"},"model":"gpt-realtime","voice":"cedar"},"client_secret":{"value":"ek_1","expires_at":1759500000},"model":"gpt-realtime","voice":"cedar"}""",
        )
        val ci = session.connectionInfo!!.toModel()
        assertEquals(VoiceConnectionType.WEBRTC, ci.connectionType)
        assertEquals(24_000, ci.audioOut.sampleRate)
        assertEquals(ci, ci.toDto().toModel())
        // WS providers: missing formats fall back to 24 kHz PCM16 rather than failing
        val ws = decode<ConnectionInfoDto>("""{"connection_type":"websocket","audio_in_format":{"sample_rate":16000,"encoding":"pcm16"},"audio_relay":"backend"}""").toModel()
        assertEquals(16_000, ws.audioIn.sampleRate)
        assertEquals(24_000, ws.audioOut.sampleRate)
        assertEquals("backend", ws.audioRelay)
    }

    @Test
    fun catalogs() {
        assertEquals("qwen", decode<HarnessProvidersDto>("""{"providers":[{"id":"claude","label":"Claude Code","description":"x"},{"id":"qwen"}]}""").providers[1].id)
        assertEquals(128000L, decode<QwenModelsDto>("""{"models":[{"id":"q","display_name":"Q","provider":"openai","base_url":null,"context_window":128000,"supports_vision":true,"supports_video":false,"supports_thinking":true}]}""").models[0].contextWindow)
        assertEquals("d", decode<SkillsDto>("""{"skills":[{"name":"n","description":"d","dir":"/x"}]}""").skills[0].description)
        assertEquals("f", decode<AgentsDto>("""{"agents":[{"name":"n","description":"d","file":"f"}]}""").agents[0].file)
        val mcp = decode<McpServersDto>("""{"servers":{"chrome-devtools":{"type":"stdio","command":"npx","args":["x"],"env":{}}},"project_dir":"/home/rodrigo/assistant"}""")
        assertEquals(setOf("chrome-devtools"), mcp.servers.keys)
        assertEquals("aistudio", decode<GoogleVoiceModelsDto>("""{"models":[{"id":"g","label":"G","voice":"Puck","voices":[],"transcription_languages":[],"default_transcription_language":"","default":true,"description":"aistudio"}]}""").models[0].description)
    }

    @Test
    fun requestBodies() {
        assertEquals(obj("""{"drop_last_n":3}"""), RestJson.encodeToJsonElement(DropLastNRequest(3)))
        assertEquals(obj("""{"title":"New"}"""), RestJson.encodeToJsonElement(RenameRequest("New")))
        assertEquals(obj("""{"path":"a/index.html","title":"T"}"""), RestJson.encodeToJsonElement(VisualizationRenameRequest("a/index.html", "T")))
        assertEquals(obj("""{"credentials_json":"{}"}"""), RestJson.encodeToJsonElement(CredentialsRequest("{}")))
        assertEquals(obj("""{"text":"hi","local_id":"L"}"""), RestJson.encodeToJsonElement(InjectRequest("hi", localId = "L")))
        assertEquals(obj("""{"level":"warn","msg":"m"}"""), RestJson.encodeToJsonElement(DebugLogRequest("warn", "m")))
        assertEquals("S", decode<SessionIdResponse>("""{"session_id":"S"}""").sessionId)
        assertTrue(decode<AuthStatusDto>("""{"authenticated":false,"auth_url":"https://console.anthropic.com/x","headless":true}""").toModel().headless)
    }

    @Test
    fun errorDetails() {
        // CFG-2 / W-6.2: show the backend detail verbatim
        assertEquals("title is required", decode<ErrorDetailDto>("""{"detail":"title is required"}""").message())
        assertEquals("field required", decode<ErrorDetailDto>("""{"detail":[{"loc":["body","x"],"msg":"field required","type":"missing"}]}""").message())
        assertNull(decode<ErrorDetailDto>("""{}""").message())
    }

    @Test
    fun wrongTypedOptionalFieldsFallBackToDefaults() {
        // coerceInputValues: a null for a non-null default does not fail the whole page
        val p = decode<PaginatedMessagesDto>("""{"messages":[{"role":"user","text":null,"blocks":null}],"total_count":1,"has_more":null,"start_index":0}""")
        assertEquals("", p.messages[0].text)
        assertTrue(p.messages[0].blocks.isEmpty())
        assertFalse(p.hasMore)
        assertEquals(
            obj("""{"type":"text","is_error":false}"""),
            RestJson.encodeToJsonElement(ContentBlockDto(type = "text")),
        )
        assertEquals(JsonObject(emptyMap()), RestJson.encodeToJsonElement(SessionConfigDto()))
        assertEquals(1, RestJson.decodeFromJsonElement<List<SessionInfoDto>>(Json.parseToJsonElement("""[{"session_id":"s"}]""")).size)
    }
}
