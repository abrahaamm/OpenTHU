package ai.opencray.app.memory

import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.SystemAction
import java.util.UUID

class MemoryManager {
  fun updateFromGoal(existing: List<MemoryRecord>, goal: String): List<MemoryRecord> {
    val normalized = goal.trim()
    if (normalized.isEmpty()) return existing

    val now = System.currentTimeMillis()
    val mutable = existing.toMutableList()

    mutable.add(
      0,
      MemoryRecord(
        id = UUID.randomUUID().toString(),
        scope = "short",
        key = "latest_goal",
        value = normalized,
        weight = 100,
        updatedAtEpochMs = now,
      ),
    )

    if (containsAny(normalized, NEGATIVE_PREFERENCE_KEYWORDS)) {
      mutable.add(
        0,
        MemoryRecord(
          id = UUID.randomUUID().toString(),
          scope = "long",
          key = "negative_preference",
          value = normalized,
          weight = 86,
          updatedAtEpochMs = now,
        ),
      )
    }

    if (containsAny(normalized, CAMPUS_FOCUS_KEYWORDS)) {
      mutable.add(
        0,
        MemoryRecord(
          id = UUID.randomUUID().toString(),
          scope = "mid",
          key = "campus_focus",
          value = normalized.take(160),
          weight = 70,
          updatedAtEpochMs = now,
        ),
      )
    }

    return mutable.distinctBy { "${it.scope}:${it.key}:${it.value}" }.take(MAX_MEMORY_RECORDS)
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

  fun updateLongPreferenceAt(
    existing: List<MemoryRecord>,
    index: Int,
    preference: String,
  ): Pair<List<MemoryRecord>, MemoryRecord?> {
    val normalized = preference.trim()
    if (normalized.isEmpty()) return existing to null
    val target =
      existing
        .filter { it.scope == "long" }
        .sortedByDescending { it.updatedAtEpochMs }
        .getOrNull(index)
        ?: return existing to null
    val updated =
      target.copy(
        value = normalized,
        weight = maxOf(target.weight, 90),
        updatedAtEpochMs = System.currentTimeMillis(),
      )
    return existing.map { if (it.id == target.id) updated else it } to updated
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
      .take(MAX_MEMORY_RECORDS)
  }

  private fun containsAny(text: String, needles: List<String>): Boolean =
    needles.any { needle -> text.contains(needle, ignoreCase = true) }

  private companion object {
    private const val MAX_MEMORY_RECORDS = 100

    private val NEGATIVE_PREFERENCE_KEYWORDS =
      listOf(
        "不要",
        "不再",
        "别",
        "减少",
        "不喜欢",
        "不想",
        "避免",
        "排除",
        "不要给我推送",
        "不要推荐",
        "don't",
        "do not",
        "avoid",
        "exclude",
      )

    private val CAMPUS_FOCUS_KEYWORDS =
      listOf(
        "课程",
        "课表",
        "考试",
        "ddl",
        "deadline",
        "作业",
        "homework",
        "assignment",
        "日历",
        "提醒",
      )
  }
}
