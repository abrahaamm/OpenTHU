package ai.opencray.app.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opencray.app.data.repository.FakeChatRepository
import ai.opencray.app.data.repository.FakeRuntimeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenCrayRuntimeInteractionTest {
  @Test
  fun manualPreferenceCanBeAddedAndDeleted() {
    val runtime = runtime()

    assertTrue(runtime.addManualPreference("少推荐晚间活动"))
    val afterAdd = runtime.snapshot()
    assertTrue(
      afterAdd.memoryRecords.any {
        it.scope == "long" && it.key == "manual_preference" && it.value == "少推荐晚间活动"
      },
    )

    assertTrue(runtime.deleteLongPreference(0))
    val afterDelete = runtime.snapshot()
    assertFalse(afterDelete.memoryRecords.any { it.value == "少推荐晚间活动" })
  }

  @Test
  fun snoozeAndIgnorePersistActionFeedbackState() {
    val runtime = runtime()

    assertTrue(runtime.snoozeAction("create_calendar_event"))
    val snoozed = runtime.snapshot()
    assertEquals("snoozed", snoozed.systemActions.first { it.id == "create_calendar_event" }.status)
    assertTrue(snoozed.auditTrail.any { it.stage == "feedback" && it.actionId == "create_calendar_event" })
    assertTrue(snoozed.memoryRecords.any { it.scope == "mid" && it.key == "action_feedback_snooze" })

    assertTrue(runtime.ignoreAction("set_alarm_reminder"))
    val ignored = runtime.snapshot()
    assertEquals("ignored", ignored.systemActions.first { it.id == "set_alarm_reminder" }.status)
    assertTrue(ignored.memoryRecords.any { it.scope == "mid" && it.key == "action_feedback_ignore" })
  }

  @Test
  fun executeApprovalGatedActionMovesToPendingApproval() {
    val runtime = runtime()

    runtime.executeAction("create_calendar_event")

    val snapshot = runtime.snapshot()
    assertEquals("pending_approval", snapshot.systemActions.first { it.id == "create_calendar_event" }.status)
    assertTrue(
      snapshot.safetyRecords.any {
        it.actionId == "create_calendar_event" && it.status == "Awaiting approval"
      },
    )
  }

  private fun runtime(): OpenCrayRuntime =
    OpenCrayRuntime(
      appContext = ApplicationProvider.getApplicationContext<Context>(),
      runtimeRepository = FakeRuntimeRepository(),
      chatRepository = FakeChatRepository(),
    ).also { it.boot() }
}
