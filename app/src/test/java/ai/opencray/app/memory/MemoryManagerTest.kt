package ai.opencray.app.memory

import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.SystemAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryManagerTest {
  private val manager = MemoryManager()

  @Test
  fun addManualPreferenceStoresLongPreferenceAndDedupesByValue() {
    val first = manager.addManualPreference(emptyList(), "少推荐晚间活动")
    val second = manager.addManualPreference(first, "少推荐晚间活动")

    val preferences = second.filter { it.scope == "long" && it.key == "manual_preference" }
    assertEquals(1, preferences.size)
    assertEquals("少推荐晚间活动", preferences.single().value)
    assertEquals(90, preferences.single().weight)
  }

  @Test
  fun addManualPreferenceIgnoresBlankInput() {
    val existing = listOf(record(scope = "long", key = "manual_preference", value = "保留"))

    val result = manager.addManualPreference(existing, "   ")

    assertEquals(existing, result)
  }

  @Test
  fun removeLongPreferenceAtUsesNewestLongPreferenceOrder() {
    val older = record(id = "old", scope = "long", key = "manual_preference", value = "旧偏好", updatedAt = 1)
    val newer = record(id = "new", scope = "long", key = "manual_preference", value = "新偏好", updatedAt = 2)
    val short = record(id = "short", scope = "short", key = "latest_goal", value = "目标", updatedAt = 3)

    val (remaining, removed) = manager.removeLongPreferenceAt(listOf(older, short, newer), 0)

    assertNotNull(removed)
    assertEquals("新偏好", removed!!.value)
    assertFalse(remaining.any { it.id == "new" })
    assertTrue(remaining.any { it.id == "old" })
    assertTrue(remaining.any { it.id == "short" })
  }

  @Test
  fun removeLongPreferenceAtReturnsNullWhenIndexIsEmpty() {
    val existing = listOf(record(scope = "short", key = "latest_goal", value = "目标"))

    val (remaining, removed) = manager.removeLongPreferenceAt(existing, 0)

    assertEquals(existing, remaining)
    assertNull(removed)
  }

  @Test
  fun recordActionFeedbackStoresMidMemoryForSnoozeAndIgnore() {
    val action =
      SystemAction(
        id = "create_calendar_event#abc",
        title = "把 DDL 加入日历",
        summary = "写入日历",
        riskLevel = "medium",
        requiresApproval = true,
      )

    val snoozed = manager.recordActionFeedback(emptyList(), action, "snooze")
    val ignored = manager.recordActionFeedback(snoozed, action, "ignore")

    assertTrue(ignored.any { it.scope == "mid" && it.key == "action_feedback_snooze" })
    val ignoreRecord = ignored.first { it.scope == "mid" && it.key == "action_feedback_ignore" }
    assertEquals("create_calendar_event:把 DDL 加入日历", ignoreRecord.value)
    assertEquals(75, ignoreRecord.weight)
  }

  private fun record(
    id: String = "id",
    scope: String,
    key: String,
    value: String,
    updatedAt: Long = 1L,
  ): MemoryRecord =
    MemoryRecord(
      id = id,
      scope = scope,
      key = key,
      value = value,
      weight = 50,
      updatedAtEpochMs = updatedAt,
    )
}
