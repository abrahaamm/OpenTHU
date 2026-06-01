package ai.opencray.app.memory

import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.SystemAction
import java.util.UUID

class MemoryManager {
  fun updateFromGoal(existing: List<MemoryRecord>, goal: String): List<MemoryRecord> {
    val now = System.currentTimeMillis()
    val mutable = existing.toMutableList()

    mutable.add(
      0,
      MemoryRecord(
        id = UUID.randomUUID().toString(),
        scope = "short",
        key = "latest_goal",
        value = goal,
        weight = 100,
        updatedAtEpochMs = now,
      ),
    )

    if (containsAny(goal, listOf("不要", "不再", "别", "减少"))) {
      mutable.add(
        0,
        MemoryRecord(
          id = UUID.randomUUID().toString(),
          scope = "long",
          key = "negative_preference",
          value = goal,
          weight = 80,
          updatedAtEpochMs = now,
        ),
      )
    }

    if (containsAny(goal, listOf("课程", "考试", "ddl", "作业"))) {
      mutable.add(
        0,
        MemoryRecord(
          id = UUID.randomUUID().toString(),
          scope = "mid",
          key = "campus_focus",
          value = "schedule_and_deadline",
          weight = 70,
          updatedAtEpochMs = now,
        ),
      )
    }

    return mutable.distinctBy { "${it.scope}:${it.key}:${it.value}" }.take(20)
  }

  fun addManualPreference(existing: List<MemoryRecord>, preference: String): List<MemoryRecord> {
    val normalized = preference.trim()
    if (normalized.isEmpty()) return existing
    return addRecord(
      existing = existing,
      scope = "long",
      key = "manual_preference",
      value = normalized,
      weight = 90,
    )
  }

  fun removeLongPreferenceAt(
    existing: List<MemoryRecord>,
    index: Int,
  ): Pair<List<MemoryRecord>, MemoryRecord?> {
    val target =
      existing
        .filter { it.scope == "long" }
        .sortedByDescending { it.updatedAtEpochMs }
        .getOrNull(index)
        ?: return existing to null
    return existing.filterNot { it.id == target.id } to target
  }

  fun recordActionFeedback(
    existing: List<MemoryRecord>,
    action: SystemAction,
    feedback: String,
  ): List<MemoryRecord> =
    addRecord(
      existing = existing,
      scope = "mid",
      key = "action_feedback_$feedback",
      value = "${action.id.substringBefore("#")}:${action.title}",
      weight = if (feedback == "ignore") 75 else 60,
    )

  private fun addRecord(
    existing: List<MemoryRecord>,
    scope: String,
    key: String,
    value: String,
    weight: Int,
  ): List<MemoryRecord> {
    val now = System.currentTimeMillis()
    val record =
      MemoryRecord(
        id = UUID.randomUUID().toString(),
        scope = scope,
        key = key,
        value = value,
        weight = weight,
        updatedAtEpochMs = now,
      )
    return (listOf(record) + existing)
      .distinctBy { "${it.scope}:${it.key}:${it.value}" }
      .take(50)
  }

  private fun containsAny(text: String, needles: List<String>): Boolean =
    needles.any { needle -> text.contains(needle, ignoreCase = true) }
}
