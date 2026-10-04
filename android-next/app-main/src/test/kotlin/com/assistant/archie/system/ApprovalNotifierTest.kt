package com.assistant.archie.system

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.assistant.archie.graph.MainAppGraph
import com.assistant.archie.shell.MemoryDataStore
import com.assistant.archie.shell.TestArchieApp
import com.assistant.archie.shell.eventually
import com.assistant.core.conversation.AgentApproval
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.PermissionBlock
import com.assistant.core.conversation.PermissionState
import com.assistant.core.data.ApprovalAnswer
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.settings.SettingsStore
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** OI-2 + OI-6: the "not looking" decision, what is pending, notification post/cancel, actions → REST. */
class ApprovalAttentionTest {
    private fun agent(localId: String, vararg perms: PermissionBlock) = ConversationState.initial(
        SessionRef(localId, null, SessionKind.AGENT, null),
    ).copy(entries = persistentListOf(AssistantEntry("e1", persistentListOf(*perms))))

    private fun perm(rid: String, state: PermissionState = PermissionState.PENDING) =
        PermissionBlock("b-$rid", rid, "Bash", JsonObject(mapOf("command" to JsonPrimitive("ls"))), state)

    @Test fun lookingOnlyInForegroundWithWorkspaceOnTopAndThatViewSelected() {
        assertTrue(ApprovalAttention.isLooking(foreground = true, workspaceOnTop = true, activeLocalId = "A", agentLocalId = "A"))
        assertFalse("backgrounded / screen off", ApprovalAttention.isLooking(false, true, "A", "A"))
        assertFalse("settings or history in front", ApprovalAttention.isLooking(true, false, "A", "A"))
        assertFalse("another view selected", ApprovalAttention.isLooking(true, true, "B", "A"))
        assertFalse("Archie selected", ApprovalAttention.isLooking(true, true, null, "A"))
    }

    @Test fun pendingFromArchieMirrorAndAgentViews_deduplicated() {
        val archie = ConversationState.initial(SessionRef("O", "J", SessionKind.ORCHESTRATOR, null)).copy(
            agentApprovals = persistentListOf(
                AgentApproval("A", "r1", "Bash", JsonObject(emptyMap())),
                AgentApproval("B", "r9", "ExitPlanMode", JsonObject(emptyMap())),
            ),
        )
        val p = ApprovalAttention.pendingOf(listOf(archie, agent("A", perm("r1"), perm("r2"), perm("r0", PermissionState.ALLOWED))))
        assertEquals(listOf("A:r1", "B:r9", "A:r2"), p.map { it.tag })
    }

    @Test fun textForPlanAndTools() {
        val plan = ApprovalAttention.textOf("ExitPlanMode", JsonObject(mapOf("plan" to JsonPrimitive("1. Check Kodi\n2. Queue films"))))
        assertEquals("Plan ready for approval" to "1. Check Kodi\n2. Queue films", plan)
        val bash = ApprovalAttention.textOf("Bash", JsonObject(mapOf("command" to JsonPrimitive("rm -rf build"), "description" to JsonPrimitive("Clean build"))))
        assertEquals("Wants to use Bash" to "Clean build", bash)
        assertEquals("Wants to use Mystery" to null, ApprovalAttention.textOf("Mystery", JsonObject(emptyMap())))
        val long = ApprovalAttention.textOf("ExitPlanMode", JsonObject(mapOf("plan" to JsonPrimitive("x".repeat(2000))))).second!!
        assertTrue(long.length <= 601 && long.endsWith("…"))
    }
}

class ApprovalNotifierTest {
    private class ListSink : ApprovalSink {
        val shown = LinkedHashMap<String, ApprovalNotice>()
        val log = mutableListOf<String>()
        override fun post(notice: ApprovalNotice) { shown[notice.tag] = notice; log += "post ${notice.tag}${notice.error?.let { " ($it)" } ?: ""}" }
        override fun cancel(tag: String) { if (shown.remove(tag) != null) log += "cancel $tag" }
    }

    private val sink = ListSink()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var answer: ApprovalAnswer = ApprovalAnswer.Sent
    private val answered = mutableListOf<Triple<String, String, Boolean>>()
    private val notifier = ApprovalNotifier(sink, scope, { "Session $it" }) { l, r, a -> answered += Triple(l, r, a); answer }

    @After fun tearDown() = scope.cancel()

    private fun pa(localId: String, rid: String, tool: String = "Bash") = PendingApproval(localId, rid, tool, JsonObject(emptyMap()))

    @Test fun postsWhenNotLooking_cancelsOnResolve() = runBlocking {
        notifier.reconcile(listOf(pa("A", "r1")), lookingAt = null)
        assertEquals("Session A", sink.shown["A:r1"]!!.title)
        assertEquals("Wants to use Bash", sink.shown["A:r1"]!!.text)
        notifier.reconcile(listOf(pa("A", "r1")), lookingAt = null)          // same state: no second post
        notifier.reconcile(emptyList(), lookingAt = null)                     // permission_resolved by anyone
        assertEquals(listOf("post A:r1", "cancel A:r1"), sink.log)
    }

    @Test fun noNotificationWhileLookingAtThatAgent_andNoneAfterLeaving() = runBlocking {
        notifier.reconcile(listOf(pa("A", "r1")), lookingAt = "A")
        assertTrue(sink.log.isEmpty())
        notifier.reconcile(listOf(pa("A", "r1")), lookingAt = null)           // the user saw it in the view
        assertTrue(sink.log.isEmpty())
        notifier.reconcile(listOf(pa("A", "r1"), pa("B", "r2")), lookingAt = "A")
        assertEquals(listOf("post B:r2"), sink.log)
    }

    @Test fun openingTheViewRemovesItsNotification() = runBlocking {
        notifier.reconcile(listOf(pa("A", "r1"), pa("B", "r2")), lookingAt = null)
        notifier.reconcile(listOf(pa("A", "r1"), pa("B", "r2")), lookingAt = "A")
        assertEquals(listOf("post A:r1", "post B:r2", "cancel A:r1"), sink.log)
        assertNotNull(sink.shown["B:r2"])
    }

    @Test fun flowsDriveReconcile() {
        val pending = kotlinx.coroutines.flow.MutableStateFlow(listOf(pa("A", "r1", "ExitPlanMode")))
        val looking = kotlinx.coroutines.flow.MutableStateFlow<String?>("A")
        notifier.attach(pending, looking)
        eventually { true }
        assertTrue(sink.log.isEmpty())
        looking.value = null
        pending.value = listOf(pa("A", "r1", "ExitPlanMode"), pa("C", "r3", "ExitPlanMode"))
        eventually(message = { "${sink.log}" }) { sink.shown["C:r3"]?.text == "Plan ready for approval" }
        pending.value = emptyList()
        eventually(message = { "${sink.log}" }) { sink.shown.isEmpty() }
    }

    @Test fun actionAnswers_successCancels_failureKeepsItWithTheError() = runBlocking {
        notifier.reconcile(listOf(pa("A", "r1")), lookingAt = null)
        val n = sink.shown["A:r1"]!!
        answer = ApprovalAnswer.Failed("That agent session is no longer running")
        notifier.answer(n, allow = true)
        assertEquals("That agent session is no longer running", sink.shown["A:r1"]!!.error)
        answer = ApprovalAnswer.AlreadyAnswered
        notifier.answer(n, allow = false)
        assertNull(sink.shown["A:r1"])
        assertEquals(listOf(Triple("A", "r1", true), Triple("A", "r1", false)), answered)
    }
}

/** The real notification (channel, heads-up, lock screen, actions) and the receiver → REST round trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestArchieApp::class)
class ApprovalNotificationRobolectricTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @After fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun graphAt(url: String) {
        TestArchieApp.factory = { a ->
            MainAppGraph(
                a,
                settings = SettingsStore(
                    MemoryDataStore(mutablePreferencesOf().apply {
                        this[stringPreferencesKey("server_url")] = url
                        this[booleanPreferencesKey("auto_connect")] = false
                    }),
                    scope,
                ),
                scanner = { emptyList() },
                scope = scope,
            )
        }
    }

    private val notice = ApprovalNotice("AG 1", "r1", "TV setup plan", "Plan ready for approval", "1. Check Kodi")

    @Test fun postsHeadsUpOnTheApprovalsChannel_withActions_andCancels() {
        val sink = SystemApprovalSink(app)
        sink.post(notice)
        val ch = nm.getNotificationChannel(SystemApprovalSink.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, ch.lockscreenVisibility)
        val sbn = shadowOf(nm).activeNotifications.single()
        assertEquals("AG 1:r1", sbn.tag)
        val n = sbn.notification
        assertEquals(android.app.Notification.CATEGORY_REMINDER, n.category)
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals("TV setup plan", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Plan ready for approval", n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Deny", "Approve"), n.actions.map { it.title.toString() })
        assertTrue("approve needs an unlocked device", n.actions[1].isAuthenticationRequired)
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals("AG 1", open.getStringExtra(SystemApprovalSink.EXTRA_OPEN_AGENT))
        sink.cancel("AG 1:r1")
        assertTrue(shadowOf(nm).activeNotifications.isEmpty())
    }

    @Test fun approveActionPostsToTheEndpoint_andRemovesTheNotification() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))
        server.start()
        graphAt("ws://${server.hostName}:${server.port}")
        SystemApprovalSink(app).post(notice)
        val approve = shadowOf(nm).activeNotifications.single().notification.actions[1].actionIntent
        deliver(shadowOf(approve).savedIntent)

        val req = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/api/sessions/AG%201/permission", req.path)
        assertEquals("""{"request_id":"r1","decision":"allow"}""", req.body.readUtf8())
        eventually { shadowOf(nm).activeNotifications.isEmpty().also { shadowOf(android.os.Looper.getMainLooper()).idle() } }
    }

    @Test fun denyActionFailure_updatesTheNotificationWithTheError_neverCrashes() {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"detail":"No live pool session with local_id='AG 1'"}"""))
        server.start()
        graphAt("ws://${server.hostName}:${server.port}")
        SystemApprovalSink(app).post(notice)
        val deny = shadowOf(nm).activeNotifications.single().notification.actions[0].actionIntent
        deliver(shadowOf(deny).savedIntent)

        val req = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("""{"request_id":"r1","decision":"deny"}""", req.body.readUtf8())
        eventually(message = { "${shadowOf(nm).activeNotifications.map { it.notification.extras }}" }) {
            shadowOf(nm).activeNotifications.singleOrNull()?.notification?.extras
                ?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() == "Couldn't send: That agent session is no longer running"
        }
    }

    @Test fun foreignIntentsAreIgnored() {
        assertNull(SystemApprovalSink.noticeOf(Intent("other")))
        assertNull(SystemApprovalSink.noticeOf(Intent(SystemApprovalSink.ACTION_APPROVE)))   // no ids
        ApprovalActionReceiver().onReceive(app, Intent("other"))                             // no crash, no call
    }

    private fun deliver(intent: Intent) {
        app.sendBroadcast(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
