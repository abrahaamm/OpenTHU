package ai.opencray.app.agent

import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.SystemAction
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ActionPlanner {
  fun plan(goal: String, snapshot: RuntimeSnapshot): List<SystemAction> {
    val normalized = goal.lowercase()
    val actions = mutableListOf<SystemAction>()
    val calendarWindow = deriveCalendarWindow(goal = goal)
    val conflictDecision = deriveConflictDecision(normalized)

    if (containsAny(normalized, listOf("课程", "课表", "class", "schedule", "考试", "ddl", "deadline", "作业"))) {
      val createRisk = if (conflictDecision == "delete_conflicts") "high" else "medium"
      actions +=
        SystemAction(
          id = "create_calendar_event",
          title = "Create Calendar Event",
          summary = "Write a campus event into device calendar with conflict policy.",
          riskLevel = createRisk,
          requiresApproval = true,
          params =
            mapOf(
              "title" to deriveCalendarTitle(goal),
              "description" to goal,
              "start_time" to calendarWindow.first,
              "end_time" to calendarWindow.second,
              "conflict_decision" to conflictDecision,
              "allow_conflict_delete" to if (conflictDecision == "delete_conflicts") "true" else "false",
            ),
          confidence = 85,
          explain = "Goal mentions course/exam/deadline semantics.",
        )

      if (containsAny(normalized, listOf("冲突", "conflict", "重叠", "overlap"))) {
        actions +=
          SystemAction(
            id = "detect_calendar_conflicts",
            title = "Detect Calendar Conflicts",
            summary = "Check overlaps before writing the new calendar event.",
            riskLevel = "low",
            requiresApproval = false,
            params =
              mapOf(
                "start_time" to calendarWindow.first,
                "end_time" to calendarWindow.second,
              ),
            confidence = 84,
            explain = "Goal explicitly asks conflict checks.",
          )
      }
      actions +=
        SystemAction(
          id = "set_alarm_reminder",
          title = "Set Reminder Alarm",
          summary = "Set a reminder alarm before the parsed campus event time.",
          riskLevel = "medium",
          requiresApproval = false,
          payload =
            mapOf(
              "time" to deriveAlarmTime(goal),
              "label" to deriveCalendarTitle(goal),
              "vibrate" to true,
            ),
          confidence = 80,
          explain = "Campus tasks usually require time-sensitive reminders.",
        )
    }

    if (containsAny(normalized, listOf("资讯", "新闻", "校园网", "活动", "news", "event"))) {
      actions +=
        SystemAction(
          id = "get_campus_activities",
          title = "Get Campus Activities",
          summary = "Fetch campus activity information and official source links.",
          riskLevel = "low",
          requiresApproval = false,
          confidence = 90,
          explain = "Goal asks for campus information reading.",
        )
    }

    if (containsAny(normalized, listOf("未读通知", "系统通知", "手机通知", "通知栏", "读取通知", "read notifications", "unread notifications"))) {
      actions +=
        SystemAction(
          id = "read_notifications",
          title = "Read System Notifications",
          summary = "Read unread notifications from Android notification listener.",
          riskLevel = "low",
          requiresApproval = false,
          confidence = 88,
          explain = "Goal asks for unread system notifications.",
        )
    }

    if (containsAny(normalized, listOf("提醒", "待办", "reminder"))) {
      actions +=
        SystemAction(
          id = "create_reminder",
          title = "Create Reminder",
          summary = "Create a local reminder using alarm or notification fallback.",
          riskLevel = "medium",
          requiresApproval = false,
          payload =
            mapOf(
              "title" to deriveCalendarTitle(goal),
              "due_time" to deriveReminderTime(goal),
            ),
          confidence = 82,
          explain = "Goal asks for a reminder.",
        )
    }

    if (containsAny(normalized, listOf("闹钟", "alarm"))) {
      actions +=
        SystemAction(
          id = "set_alarm",
          title = "Set Alarm",
          summary = "Set a local Android alarm.",
          riskLevel = "low",
          requiresApproval = false,
          payload =
            mapOf(
              "time" to deriveAlarmTime(goal),
              "label" to deriveCalendarTitle(goal),
              "vibrate" to true,
            ),
          confidence = 86,
          explain = "Goal asks for an alarm.",
        )
    }

    if (containsAny(normalized, listOf("搜索", "查找", "search"))) {
      val encoded = URLEncoder.encode(goal, StandardCharsets.UTF_8.name())
      actions +=
        SystemAction(
          id = "open_url",
          title = "Open Search",
          summary = "Open a web search page for this query.",
          riskLevel = "low",
          requiresApproval = false,
          payload = mapOf("url" to "https://duckduckgo.com/?q=$encoded"),
          confidence = 72,
          explain = "Local fallback opens a browser search when Agent-Core is unavailable.",
        )
    }

    if (containsAny(normalized, listOf("删除日历", "删除日程", "删除事项", "删除事件", "delete calendar", "delete event", "remove event"))) {
      val explicitDeleteConfirm = containsAny(normalized, listOf("确认删除", "确定删除", "同意删除", "confirm delete", "yes delete"))
      val targetEventIds = extractEventIds(goal)
      actions +=
        SystemAction(
          id = "delete_calendar_event",
          title = "Delete Calendar Event",
          summary = "Delete matching calendar events from system calendar (high risk).",
          riskLevel = "high",
          requiresApproval = true,
          params =
            mapOf(
              "event_ids" to targetEventIds.joinToString(","),
              "event_id" to targetEventIds.firstOrNull().orEmpty(),
              "title_keyword" to deriveCalendarTitle(goal),
              "confirm_delete" to explicitDeleteConfirm.toString(),
            ),
          confidence = 78,
          explain = "Goal requests destructive calendar delete behavior.",
        )
    }

    if (actions.isEmpty()) {
      val fallbackAction =
        snapshot.systemActions.firstOrNull()?.copy(
          id = "fallback_contextual_action",
          title = "Fallback Contextual Action",
          summary = "No explicit domain intent detected. Keep context synced and suggest next step.",
          riskLevel = "low",
          requiresApproval = false,
          confidence = 55,
          explain = "Use safe fallback when intent confidence is low.",
          status = "planned",
          lastResult = null,
        ) ?: fallbackActionFromScratch()
      actions += fallbackAction
    }

    return actions
  }

  private fun fallbackActionFromScratch(): SystemAction =
    SystemAction(
      id = "fallback_contextual_action",
      title = "Fallback Contextual Action",
      summary = "Prepare a safe next suggestion based on current context signals.",
      riskLevel = "low",
      requiresApproval = false,
      confidence = 50,
      explain = "No high-confidence entity recognized.",
    )

  private fun containsAny(text: String, needles: List<String>): Boolean =
    needles.any { needle -> text.contains(needle) }

  private fun deriveConflictDecision(normalizedGoal: String): String {
    return when {
      containsAny(normalizedGoal, listOf("不写入", "不要写", "跳过写入", "skip write", "skip")) -> "skip_write"
      containsAny(normalizedGoal, listOf("同时存在", "共存", "保留原有", "coexist", "keep existing")) -> "coexist"
      containsAny(normalizedGoal, listOf("删除原有", "替换", "覆盖", "删掉原来的", "delete existing", "replace")) -> "delete_conflicts"
      else -> "prompt_user"
    }
  }

  private fun deriveCalendarTitle(goal: String): String {
    val compact = goal.replace("\n", " ").trim()
    return if (compact.isEmpty()) "OpenTHU Calendar Event" else compact.take(40)
  }

  private fun deriveCalendarWindow(goal: String): Pair<String, String> {
    val isoMatches =
      Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})""")
        .findAll(goal)
        .map { it.value }
        .toList()

    if (isoMatches.size >= 2) {
      return isoMatches[0] to isoMatches[1]
    }

    val now = OffsetDateTime.now(ZoneOffset.UTC)
    val start = now.plusHours(1).withSecond(0).withNano(0)
    val end = start.plusHours(1)
    val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    return formatter.format(start) to formatter.format(end)
  }

  private fun deriveReminderTime(goal: String): String {
    val alarmTime = deriveAlarmTime(goal)
    val now = OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)
    val hour = alarmTime.substringBefore(":").toIntOrNull() ?: now.plusHours(2).hour
    val minute = alarmTime.substringAfter(":", "00").toIntOrNull() ?: 0
    val candidate = now.withHour(hour).withMinute(minute)
    val due = if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(due)
  }

  private fun deriveAlarmTime(goal: String): String {
    Regex("""(?<!\d)([01]?\d|2[0-3])\s*[：:]\s*([0-5]\d)(?!\d)""")
      .find(goal)
      ?.let { match ->
        val hour = match.groupValues[1].padStart(2, '0')
        val minute = match.groupValues[2]
        return "$hour:$minute"
      }

    Regex("""(?<!\d)(\d{1,2})\s*点\s*(半|([0-5]?\d)\s*分?)?""")
      .find(goal)
      ?.let { match ->
        val hour = (match.groupValues[1].toIntOrNull() ?: return@let).coerceIn(0, 23)
        val minute =
          when {
            match.groupValues[2] == "半" -> 30
            match.groupValues[3].isNotBlank() -> (match.groupValues[3].toIntOrNull() ?: 0).coerceIn(0, 59)
            else -> 0
          }
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
      }

    return "08:00"
  }

  private fun extractEventIds(goal: String): List<String> {
    val eventLike = Regex("""(?:evt_|event_id[:=]?)\s*([A-Za-z0-9_-]+)""")
      .findAll(goal)
      .map { it.groupValues[1] }
      .filter { it.isNotBlank() }
      .toList()
    if (eventLike.isNotEmpty()) return eventLike

    return Regex("""\b\d{3,}\b""")
      .findAll(goal)
      .map { it.value }
      .toList()
  }
}
