package ai.opencray.app.memory

import ai.opencray.app.domain.model.MemoryRecord
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

  private fun containsAny(text: String, needles: List<String>): Boolean =
    needles.any { needle -> text.contains(needle, ignoreCase = true) }
}
