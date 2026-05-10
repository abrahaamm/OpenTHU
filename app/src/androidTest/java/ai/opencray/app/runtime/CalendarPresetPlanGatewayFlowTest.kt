package ai.opencray.app.runtime

import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opencray.app.data.repository.FakeChatRepository
import ai.opencray.app.data.repository.FakeRuntimeRepository
import ai.opencray.app.data.model.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CalendarPresetPlanGatewayFlowTest {
  companion object {
    private const val TEST_GATEWAY_HOST = "10.0.2.2"
    private const val TEST_GATEWAY_PORT = 28791
  }

  @Test
  fun gatewayPresetPlan_createThenDeleteCalendarEvent_success() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageName = context.packageName
    grantCalendarPermissions(packageName)

    val runtime =
      OpenCrayRuntime(
        appContext = context,
        runtimeRepository = FakeRuntimeRepository(),
        chatRepository = FakeChatRepository(),
      )

    runtime.boot()
    runtime.connectToGateway(host = TEST_GATEWAY_HOST, port = TEST_GATEWAY_PORT, tlsEnabled = false)

    waitUntil(
      timeoutMs = TimeUnit.SECONDS.toMillis(15),
      failureMessage = {
        "gateway connect timeout\n${snapshotDebug(runtime.snapshot(), context, null)}"
      },
    ) {
      val status = runtime.snapshot().connectionStatus.lowercase()
      status.contains("connected") || status.contains("failed")
    }
    assertTrue(
      "Gateway should connect. Start preset server on host:$TEST_GATEWAY_PORT first.",
      runtime.snapshot().connectionStatus.lowercase().contains("connected"),
    )

    runtime.planGoal("preset calendar e2e")
    waitUntil(
      timeoutMs = TimeUnit.SECONDS.toMillis(15),
      failureMessage = {
        "plan reception timeout (expected create/delete actions)\n${snapshotDebug(runtime.snapshot(), context, null)}"
      },
    ) {
      val actions = runtime.snapshot().systemActions
      actions.any { it.id == "create_calendar_event" } &&
        actions.any { it.id == "delete_calendar_event" }
    }

    val planned = runtime.snapshot().systemActions
    val createPlanned = planned.firstOrNull { it.id == "create_calendar_event" }
    val deletePlanned = planned.firstOrNull { it.id == "delete_calendar_event" }
    assertNotNull("create_calendar_event should be present in preset plan", createPlanned)
    assertNotNull("delete_calendar_event should be present in preset plan", deletePlanned)

    val keyword =
      deletePlanned!!.params["title_keyword"].orEmpty().ifBlank {
        createPlanned!!.params["title"].orEmpty()
      }
    assertTrue("preset title keyword should not be empty", keyword.isNotBlank())
    cleanupEventsByTitleKeyword(context, keyword)

    runtime.runActions()

    waitUntil(
      timeoutMs = TimeUnit.SECONDS.toMillis(30),
      failureMessage = {
        "execution/callback timeout for preset calendar flow\n${snapshotDebug(runtime.snapshot(), context, keyword)}"
      },
    ) {
      val snapshot = runtime.snapshot()
      val createDone =
        snapshot.systemActions.any {
          it.id == "create_calendar_event" &&
            it.requestId == createPlanned!!.requestId &&
            it.status == "executed"
        }
      val deleteDone =
        snapshot.systemActions.any {
          it.id == "delete_calendar_event" &&
            it.requestId == deletePlanned.requestId &&
            it.status == "executed"
        }
      val createCallback = snapshot.recentEvents.any { it.contains("Result submitted for create_calendar_event") }
      val deleteCallback = snapshot.recentEvents.any { it.contains("Result submitted for delete_calendar_event") }
      createDone && deleteDone && createCallback && deleteCallback
    }

    val after = runtime.snapshot()
    val createExecuted =
      after.systemActions.firstOrNull {
        it.id == "create_calendar_event" &&
          it.requestId == createPlanned!!.requestId &&
          it.status == "executed"
      }
    val deleteExecuted =
      after.systemActions.firstOrNull {
        it.id == "delete_calendar_event" &&
          it.requestId == deletePlanned.requestId &&
          it.status == "executed"
      }
    assertNotNull("create action should execute", createExecuted)
    assertNotNull("delete action should execute", deleteExecuted)
    assertTrue(
      "create result should contain event_id",
      createExecuted!!.lastResult.orEmpty().contains("event_id="),
    )
    assertTrue(
      "delete result should contain Deleted count",
      deleteExecuted!!.lastResult.orEmpty().contains("Deleted"),
    )

    val remaining = countEventsByTitleKeyword(context, keyword)
    assertEquals("preset event should be deleted by delete_calendar_event", 0, remaining)
  }

  private fun grantCalendarPermissions(packageName: String) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    uiAutomation.executeShellCommand("pm grant $packageName android.permission.READ_CALENDAR").close()
    uiAutomation.executeShellCommand("pm grant $packageName android.permission.WRITE_CALENDAR").close()
  }

  private fun cleanupEventsByTitleKeyword(
    context: Context,
    keyword: String,
  ) {
    context.contentResolver.delete(
      CalendarContract.Events.CONTENT_URI,
      "${CalendarContract.Events.TITLE} LIKE ?",
      arrayOf("%$keyword%"),
    )
  }

  private fun countEventsByTitleKeyword(
    context: Context,
    keyword: String,
  ): Int {
    val projection = arrayOf(CalendarContract.Events._ID)
    val selection = "${CalendarContract.Events.TITLE} LIKE ? AND (${CalendarContract.Events.DELETED}=0 OR ${CalendarContract.Events.DELETED} IS NULL)"
    val args = arrayOf("%$keyword%")
    context.contentResolver.query(
      CalendarContract.Events.CONTENT_URI,
      projection,
      selection,
      args,
      null,
    )?.use { cursor ->
      return cursor.count
    }
    return 0
  }

  private fun waitUntil(
    timeoutMs: Long,
    intervalMs: Long = 250L,
    failureMessage: (() -> String)? = null,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(intervalMs)
    }
    val detail = failureMessage?.invoke().orEmpty()
    if (detail.isNotBlank()) {
      throw AssertionError("Condition not met within ${timeoutMs}ms\n$detail")
    }
    throw AssertionError("Condition not met within ${timeoutMs}ms")
  }

  private fun snapshotDebug(
    snapshot: RuntimeSnapshot,
    context: Context,
    keyword: String?,
  ): String {
    val actionText =
      snapshot.systemActions.joinToString(separator = "\n") { action ->
        "  - id=${action.id}, requestId=${action.requestId}, status=${action.status}, lastResult=${action.lastResult}, params=${action.params}"
      }
    val taskText =
      snapshot.tasks.joinToString(separator = "\n") { task ->
        "  - id=${task.id}, status=${task.status}, goal=${task.goal}, summary=${task.summary}"
      }
    val recentEventsText =
      snapshot.recentEvents.take(20).joinToString(separator = "\n") { "  - $it" }
    val auditText =
      snapshot.auditTrail.take(20).joinToString(separator = "\n") { audit ->
        "  - stage=${audit.stage}, actionId=${audit.actionId}, message=${audit.message}"
      }
    val keywordCount = keyword?.let { countEventsByTitleKeyword(context, it) } ?: -1

    return buildString {
      appendLine("connectionStatus=${snapshot.connectionStatus}")
      appendLine("host=${snapshot.host}:${snapshot.port}, tls=${snapshot.tlsEnabled}")
      appendLine("tasks:")
      appendLine(if (taskText.isBlank()) "  (none)" else taskText)
      appendLine("systemActions:")
      appendLine(if (actionText.isBlank()) "  (none)" else actionText)
      appendLine("recentEvents(top20):")
      appendLine(if (recentEventsText.isBlank()) "  (none)" else recentEventsText)
      appendLine("auditTrail(top20):")
      appendLine(if (auditText.isBlank()) "  (none)" else auditText)
      if (keyword != null) {
        appendLine("calendar title_keyword=$keyword, matched_events=$keywordCount")
      }
    }
  }
}
