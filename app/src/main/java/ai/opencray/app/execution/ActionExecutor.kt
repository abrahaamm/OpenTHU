package ai.opencray.app.execution

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.opencray.app.domain.model.SystemAction
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

data class ActionExecutionReport(
  val success: Boolean,
  val message: String,
  val recoverable: Boolean,
  val semantic: String = if (success) "action_executed" else "action_failed",
  val metadata: Map<String, Any?> = emptyMap(),

)

private data class CalendarWindow(
  val startMs: Long,
  val endMs: Long,
)

private data class CalendarEventBrief(
  val id: Long,
  val title: String,
  val startMs: Long,
  val endMs: Long,
)

private data class CampusActivityRecord(
  val title: String,
  val startTime: String = "",
  val location: String = "",
  val organizer: String = "",
  val category: String = "",
  val abstract: String = "",
  val url: String = "",
  val source: String = "",
) {
  fun toDataMap(): Map<String, String> =
    mapOf(
      "title" to title,
      "start_time" to startTime,
      "time" to startTime,
      "location" to location,
      "organizer" to organizer,
      "category" to category,
      "abstract" to abstract,
      "url" to url,
      "source" to source,
    ).filterValues { it.isNotBlank() }
}

class ActionExecutor(
  private val appContext: Context,
) {
  private val homeworkSkillExecutor = HomeworkSkillExecutor(appContext)
  private val courseSkillExecutor = CourseSkillExecutor(appContext)

  fun execute(action: SystemAction, goal: String): ActionExecutionReport {
    val actionId = action.id.substringBefore("#")
    return when (actionId) {
      "get_current_time" -> executeGetCurrentTime()
      "create_calendar_event" -> executeCreateCalendarEvent(action, goal)
      "detect_calendar_conflicts" -> executeConflictDetection(action, goal)
      "delete_calendar_event" -> executeDeleteCalendarEvent(action)
      "get_homework_cookie",
      "crawl_course_homeworks",
      "crawl_unsubmitted_homeworks",
      "preview_homework_attachments",
      "upload_homework_attachment",
      "submit_homework" -> homeworkSkillExecutor.execute(action)
      "get_semesters",
      "get_courses",
      "get_course_schedule" -> courseSkillExecutor.execute(action)
      "set_alarm_reminder", "set_alarm" -> executeAlarmIntent(action, goal)
      "create_reminder" -> executeCreateReminder(action, goal)
      "get_campus_activities" -> executeGetCampusActivities(action)
      "read_notifications" -> executeReadNotifications()
      "show_summary" -> executeShowSummary(action)
      "send_notification" -> executeSendNotification(action)
      "open_tsinghua_news" -> openWebPage("https://www.tsinghua.edu.cn")
      "open_url" -> {
        val url =
          (action.payload?.get("url") as? String)
            ?: action.params["url"]
        if (url.isNullOrBlank()) {
          ActionExecutionReport(
            success = false,
            message = "Missing required `url` for open_url.",
            recoverable = false,
            semantic = "invalid_param",
          )
        } else {
          openWebPage(url)
        }
      }
      "open_context_review" ->
        ActionExecutionReport(
          success = false,
          message = "open_context_review is not configured on this device.",
          recoverable = false,
          semantic = "not_configured",
        )
      else ->
        ActionExecutionReport(
          success = false,
          message = "Unsupported action: ${action.id}.",
          recoverable = false,
          semantic = "unsupported_action",
        )
    }
  }

  private fun executeGetCampusActivities(action: SystemAction): ActionExecutionReport {
    val (activities, source, loadError) = loadConfiguredCampusActivities()
    if (activities.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message =
          loadError.ifBlank {
            "手机端未配置校园活动数据源。请在设置页填写「校园活动文件」，或先同步校园活动 JSON 到设备。"
          },
        recoverable = false,
        semantic = "not_configured",
        metadata =
          mapOf(
            "activities" to emptyList<Map<String, String>>(),
            "source" to if (source.isBlank()) "not_configured" else source,
            "reason" to "missing_mobile_campus_source",
          ),
      )
    }

    val query = firstNonBlank(
      readActionString(action, "query"),
      readActionString(action, "question"),
      readActionString(action, "keyword"),
    )
    val keywords =
      parseCsvOrJsonArray(readActionString(action, "keywords"))
        .ifEmpty { tokenizeCampusQuery(query) }
    val limit = readActionString(action, "limit").toIntOrNull()?.coerceIn(1, 30) ?: 10
    val filtered =
      filterCampusActivities(activities, keywords)
        .take(limit)
    val result = if (filtered.isNotEmpty()) filtered else activities.take(limit)
    val summary =
      buildString {
        append("手机端校园活动检索完成：")
        append(if (filtered.isNotEmpty()) "匹配到 ${filtered.size} 条" else "未命中关键词，返回前 ${result.size} 条候选")
        append("。")
        result.take(6).forEach { item ->
          append("\n- ").append(item.title)
          val details = listOf(item.startTime, item.location).filter { it.isNotBlank() }.joinToString("，")
          if (details.isNotBlank()) append("（").append(details).append("）")
        }
      }

    return ActionExecutionReport(
      success = true,
      message = summary,
      recoverable = false,
      semantic = "campus_activities_loaded_on_device",
      metadata =
        mapOf(
          "status" to "activities_loaded",
          "answer" to summary,
          "summary" to summary,
          "activities" to result.map { it.toDataMap() },
          "count" to result.size,
          "source" to source,
          "query" to query,
        ),
    )
  }

  private fun loadConfiguredCampusActivities(): Triple<List<CampusActivityRecord>, String, String> {
    val inlineJson = readSetting("campus_activities_json")
    if (inlineJson.isNotBlank()) {
      return parseCampusActivities(inlineJson, "settings:campus_activities_json")
    }

    val pathText = readSetting("campus_file")
    if (pathText.isBlank()) {
      return Triple(emptyList(), "", "")
    }
    val file = File(pathText)
    if (!file.isFile || !file.canRead()) {
      return Triple(emptyList(), pathText, "手机端无法读取校园活动文件：$pathText")
    }
    if (file.length() > 2L * 1024L * 1024L) {
      return Triple(emptyList(), pathText, "校园活动文件超过 2MB，暂不在手机端读取：$pathText")
    }
    return runCatching {
      parseCampusActivities(file.readText(Charsets.UTF_8), pathText)
    }.getOrElse { throwable ->
      Triple(emptyList(), pathText, "校园活动文件解析失败：${throwable.message ?: throwable.javaClass.simpleName}")
    }
  }

  private fun parseCampusActivities(
    rawJson: String,
    source: String,
  ): Triple<List<CampusActivityRecord>, String, String> =
    runCatching {
      val trimmed = rawJson.trim()
      val array =
        if (trimmed.startsWith("[")) {
          JSONArray(trimmed)
        } else {
          val root = JSONObject(trimmed)
          root.optJSONArray("activities")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("data")
            ?: JSONArray()
        }
      val records =
        (0 until array.length())
          .mapNotNull { index -> array.optJSONObject(index)?.toCampusActivityRecord() }
          .filter { it.title.isNotBlank() }
      Triple(records, source, "")
    }.getOrElse { throwable ->
      Triple(emptyList(), source, "校园活动 JSON 解析失败：${throwable.message ?: throwable.javaClass.simpleName}")
    }

  private fun JSONObject.toCampusActivityRecord(): CampusActivityRecord =
    CampusActivityRecord(
      title = firstJsonString(this, "title", "name", "activity_title"),
      startTime = firstJsonString(this, "start_time", "time", "date", "event_time", "begin_time"),
      location = firstJsonString(this, "location", "venue", "place", "address"),
      organizer = firstJsonString(this, "organizer", "host", "source"),
      category = firstJsonString(this, "category", "type", "channel"),
      abstract = firstJsonString(this, "abstract", "summary", "content", "description", "snippet"),
      url = firstJsonString(this, "url", "link", "detail_url"),
      source = firstJsonString(this, "source", "channel", "provider"),
    )

  private fun filterCampusActivities(
    activities: List<CampusActivityRecord>,
    keywords: List<String>,
  ): List<CampusActivityRecord> {
    if (keywords.isEmpty()) return activities
    val normalizedKeywords = keywords.map { it.lowercase(Locale.getDefault()) }.filter { it.isNotBlank() }
    return activities.filter { activity ->
      val haystack =
        listOf(
          activity.title,
          activity.abstract,
          activity.location,
          activity.organizer,
          activity.category,
          activity.source,
        ).joinToString(" ").lowercase(Locale.getDefault())
      normalizedKeywords.any { keyword -> haystack.contains(keyword) }
    }
  }

  private fun tokenizeCampusQuery(query: String): List<String> =
    query
      .replace(Regex("[，。；、,.!?！？:：\\[\\]（）()\\n\\t]"), " ")
      .split(Regex("\\s+"))
      .map { it.trim() }
      .filter { it.length >= 2 }
      .take(8)

  private fun parseCsvOrJsonArray(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val text = raw.trim()
    if (text.startsWith("[")) {
      return runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { index -> arr.opt(index)?.toString()?.trim() }.filter { it.isNotEmpty() }
      }.getOrElse { emptyList() }
    }
    return text.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }
  }

  private fun readActionString(
    action: SystemAction,
    key: String,
  ): String {
    val fromParams = action.params[key]?.trim().orEmpty()
    if (fromParams.isNotBlank()) return fromParams
    return action.payload?.get(key)?.toString()?.trim().orEmpty()
  }

  private fun readSetting(key: String): String =
    appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
      .getString(key, "")
      .orEmpty()
      .trim()

  private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }.orEmpty()

  private fun firstJsonString(
    json: JSONObject,
    vararg keys: String,
  ): String {
    for (key in keys) {
      val value = json.optString(key, "").trim()
      if (value.isNotBlank() && value != "null") return value
    }
    return ""
  }

  private fun executeShowSummary(action: SystemAction): ActionExecutionReport {
    val title =
      (action.payload?.get("title") as? String)
        ?: action.params["title"]
        ?: "OpenTHU 摘要"
    val content =
      (action.payload?.get("content") as? String)
        ?: action.params["content"]
        ?: action.summary
    return ActionExecutionReport(
      success = true,
      message = "$title\n$content",
      recoverable = false,
      semantic = "summary_shown",
      metadata = mapOf(
        "title" to title,
        "content" to content,
        "format" to ((action.payload?.get("format") as? String) ?: action.params["format"] ?: "plain"),
      ),
    )
  }

  private fun executeSendNotification(action: SystemAction): ActionExecutionReport {
    val title =
      (action.payload?.get("title") as? String)
        ?: action.params["title"]
        ?: "OpenTHU 通知"
    val body =
      (action.payload?.get("body") as? String)
        ?: action.params["body"]
        ?: action.summary

    Handler(Looper.getMainLooper()).post {
      Toast.makeText(appContext, "$title\n$body", Toast.LENGTH_LONG).show()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      return ActionExecutionReport(
        success = true,
        message = "Notification text shown in-app because POST_NOTIFICATIONS permission is not granted: $title",
        recoverable = false,
        semantic = "notification_shown_in_app",
        metadata = mapOf("title" to title, "body" to body, "system_notification_posted" to false),
      )
    }

    val channelId = "opencray_agent"
    val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
        NotificationChannel(channelId, "OpenTHU Agent", NotificationManager.IMPORTANCE_DEFAULT),
      )
    }

    val notification =
      NotificationCompat.Builder(appContext, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .build()

    return runCatching {
      manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
      ActionExecutionReport(
        success = true,
        message = "Notification posted: $title",
        recoverable = false,
        semantic = "notification_posted",
        metadata = mapOf("title" to title, "body" to body, "system_notification_posted" to true),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Notification failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        semantic = "notification_failed",
        metadata = mapOf("title" to title, "body" to body),
      )
    }
  }

  private fun executeCreateReminder(
    action: SystemAction,
    goal: String,
  ): ActionExecutionReport {
    val dueTime =
      (action.payload?.get("due_time") as? String)
        ?: action.params["due_time"]
        ?: (action.payload?.get("time") as? String)
        ?: action.params["time"]
    if (dueTime.isNullOrBlank()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing required `due_time` or `time` for create_reminder.",
        recoverable = false,
        semantic = "invalid_param",
      )
    }
    val alarmAction = action.copy(
      id = "set_alarm",
      payload = mapOf(
        "time" to dueTime,
        "label" to ((action.payload?.get("title") as? String) ?: action.params["title"] ?: goal.take(40)),
        "vibrate" to true,
      ),
    )
    return executeAlarmIntent(alarmAction, goal).copy(
      semantic = "reminder_alarm_requested",
    )
  }

  private fun executeReadNotifications(): ActionExecutionReport {
    val enabledListeners = Settings.Secure.getString(appContext.contentResolver, "enabled_notification_listeners")
    val isEnabled = enabledListeners != null && enabledListeners.contains(appContext.packageName)

    if (!isEnabled) {
      val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      appContext.startActivity(intent)
      return ActionExecutionReport(
        success = false,
        message = "Missing notification access permission. Prompted user to enable it in Settings.",
        recoverable = false,
      )
    }

    val service = OpenTHUNotificationListenerService.instance
    if (service == null) {
      return ActionExecutionReport(
        success = false,
        message = "NotificationListenerService is active but not bound yet. Please wait or restart the app.",
        recoverable = true,
      )
    }

    val notifications = service.getUnreadNotifications()
    val parsedNotifications = mutableListOf<Map<String, Any>>()

    for (sbn in notifications) {
        val extras = sbn.notification.extras
        val title = extras.getString(android.app.Notification.EXTRA_TITLE)?.toString() ?: continue
        
        val textLines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
        val messages = extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
        
        val content = if (textLines != null && textLines.isNotEmpty()) {
            textLines.joinToString("\n") { it.toString() }
        } else if (messages != null && messages.isNotEmpty()) {
            messages.mapNotNull { 
                if (it is android.os.Bundle) it.getCharSequence("text")?.toString() else null 
            }.joinToString("\n")
        } else {
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        }

        val pkg = sbn.packageName
        parsedNotifications.add(mapOf(
            "package" to pkg,
            "title" to title,
            "text" to content,
            "post_time_ms" to sbn.postTime
        ))
    }

    if (parsedNotifications.isEmpty()) {
      return ActionExecutionReport(
        success = true,
        message = "No unread notifications found.",
        recoverable = false,
        semantic = "notifications_read",
        metadata = mapOf(
            "notification_count" to 0,
            "notifications" to emptyList<Map<String, Any>>()
        )
      )
    }

    val displayMessage = "Found ${parsedNotifications.size} unread notifications:\n" + 
        parsedNotifications.joinToString("\n") { "[${it["package"]}] ${it["title"]}: ${it["text"]}" }

    return ActionExecutionReport(
      success = true,
      message = displayMessage,
      recoverable = false,
      semantic = "notifications_read",
      metadata = mapOf(
          "notification_count" to parsedNotifications.size,
          "notifications" to parsedNotifications
      )
    )
  }

  private fun executeCreateCalendarEvent(
    action: SystemAction,
    goal: String,
  ): ActionExecutionReport {
    if (!hasCalendarWritePermission() || !hasCalendarReadPermission()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing calendar permission. Please grant READ_CALENDAR and WRITE_CALENDAR.",
        recoverable = false,
      )
    }

    val title = action.params["title"]?.trim().orEmpty().ifEmpty { goal.take(40).ifEmpty { "OpenTHU Event" } }
    val description = action.params["description"]?.trim().orEmpty().ifEmpty { goal }
    val window = deriveWindow(action.params, goal) ?: return ActionExecutionReport(
      success = false,
      message = "Invalid or missing start_time/end_time.",
      recoverable = false,
    )
    val conflictDecision = action.params["conflict_decision"]?.trim()?.lowercase() ?: "prompt_user"
    val conflicts = queryConflicts(window.startMs, window.endMs)

    if (conflicts.isNotEmpty()) {
      when (conflictDecision) {
        "skip_write" -> {
          return ActionExecutionReport(
            success = true,
            message = "已跳过写入（skip_write）。${conflictSummaryMessage(conflicts)}",
            recoverable = false,
            metadata = mapOf(
              "skipped" to true,
              "conflict_count" to conflicts.size,
              "conflicts" to conflictsToDataList(conflicts),
            ),
          )
        }
        "coexist" -> {
          // Android calendar supports overlapping events; proceed with insert below.
        }
        "delete_conflicts" -> {
          val allowDelete = action.params["allow_conflict_delete"]?.toBooleanStrictOrNull() ?: false
          if (!allowDelete) {
            return ActionExecutionReport(
              success = false,
              message = "冲突删除需要明确授权（allow_conflict_delete=true）。${conflictSummaryMessage(conflicts)}",
              recoverable = false,
              metadata = mapOf(
                "reason" to "allow_conflict_delete_not_set",
                "conflict_count" to conflicts.size,
                "conflicts" to conflictsToDataList(conflicts),
              ),
            )
          }
          deleteEventsByIds(conflicts.map { it.id })
        }
        else -> {
          return ActionExecutionReport(
            success = false,
            message = "检测到冲突，请选择策略 skip_write / coexist / delete_conflicts。${conflictSummaryMessage(conflicts)}",
            recoverable = false,
            metadata = mapOf(
              "reason" to "conflict_strategy_required",
              "conflict_count" to conflicts.size,
              "conflicts" to conflictsToDataList(conflicts),
            ),
          )
        }
      }
    }

    val calendarId = resolveWritableCalendarId()
    if (calendarId == null) {
      return ActionExecutionReport(
        success = false,
        message = "No writable calendar found on this device.",
        recoverable = false,
      )
    }

    return runCatching {
      val values =
        ContentValues().apply {
          put(CalendarContract.Events.CALENDAR_ID, calendarId)
          put(CalendarContract.Events.TITLE, title)
          put(CalendarContract.Events.DESCRIPTION, description)
          put(CalendarContract.Events.DTSTART, window.startMs)
          put(CalendarContract.Events.DTEND, window.endMs)
          put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
      val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
      val eventId = uri?.lastPathSegment ?: "unknown"
      ActionExecutionReport(
        success = true,
        message = "日历事项已创建：「$title」${formatEpochMs(window.startMs)}-${formatEpochMs(window.endMs)}（event_id=$eventId）",
        recoverable = false,
        metadata = mapOf(
          "event_id" to eventId,
          "title" to title,
          "start" to formatEpochMs(window.startMs),
          "end" to formatEpochMs(window.endMs),
          "calendar_id" to calendarId,
          "deleted_conflicts" to if (conflicts.isNotEmpty()) conflictsToDataList(conflicts) else emptyList<Any>(),
        ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "日历写入失败：${throwable.message ?: "未知错误"}",
        recoverable = true,
        metadata = mapOf("exception" to (throwable.message ?: "unknown")),
      )
    }
  }

  private fun executeGetCurrentTime(): ActionExecutionReport {
    val now = OffsetDateTime.now(ZoneId.systemDefault()).withNano(0)
    val hour = now.hour
    val minute = now.minute
    val localTime = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    return ActionExecutionReport(
      success = true,
      message = "Current local time captured: $localTime (${ZoneId.systemDefault().id})",
      recoverable = false,
      semantic = "current_time_captured",
      metadata = mapOf(
        "local_datetime" to now.toString(),
        "local_date" to now.toLocalDate().toString(),
        "local_time" to localTime,
        "timezone" to ZoneId.systemDefault().id,
        "epoch_ms" to now.toInstant().toEpochMilli(),
      ),
    )
  }

  private fun executeConflictDetection(
    action: SystemAction,
    goal: String,
  ): ActionExecutionReport {
    if (!hasCalendarReadPermission()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing READ_CALENDAR permission for conflict detection.",
        recoverable = false,
      )
    }
    val window = deriveWindow(action.params, goal) ?: return ActionExecutionReport(
      success = false,
      message = "Invalid or missing start_time/end_time.",
      recoverable = false,
    )
    val conflicts = queryConflicts(window.startMs, window.endMs)
    return ActionExecutionReport(
      success = true,
      message = if (conflicts.isEmpty()) {
        "未检测到时间冲突（已排除全天事件）。"
      } else {
        conflictSummaryMessage(conflicts)
      },
      recoverable = false,
      metadata = mapOf(
        "conflict_count" to conflicts.size,
        "conflicts" to conflictsToDataList(conflicts),
        "check_window_start" to formatEpochMs(window.startMs),
        "check_window_end" to formatEpochMs(window.endMs),
        "all_day_excluded" to true,
      ),
    )
  }

  private fun executeDeleteCalendarEvent(action: SystemAction): ActionExecutionReport {
    if (!hasCalendarWritePermission() || !hasCalendarReadPermission()) {
      return ActionExecutionReport(
        success = false,
        message = "Missing calendar permission. Please grant READ_CALENDAR and WRITE_CALENDAR.",
        recoverable = false,
      )
    }

    val confirmed = action.params["confirm_delete"]?.toBooleanStrictOrNull() ?: false
    if (!confirmed) {
      return ActionExecutionReport(
        success = false,
        message = "Delete blocked: confirm_delete=true is required for high-risk deletion.",
        recoverable = false,
      )
    }

    val directIds = parseIdList(action.params["event_ids"]) + parseSingleId(action.params["event_id"])
    val idsToDelete =
      if (directIds.isNotEmpty()) {
        directIds
      } else {
        val keyword = action.params["title_keyword"]?.trim().orEmpty()
        if (keyword.isEmpty()) {
          return ActionExecutionReport(
            success = false,
            message = "Delete requires event_id/event_ids or title_keyword.",
            recoverable = false,
          )
        }
        findEventsByTitleKeyword(keyword, limit = 50).map { it.id }
      }

    if (idsToDelete.isEmpty()) {
      return ActionExecutionReport(
        success = true,
        message = "No matching calendar events to delete.",
        recoverable = false,
      )
    }

    return runCatching {
      val deleted = deleteEventsByIds(idsToDelete)
      ActionExecutionReport(
        success = true,
        message = "已删除 $deleted 个日历事项（目标 ${idsToDelete.size} 个）。",
        recoverable = false,
        metadata = mapOf(
          "deleted_count" to deleted,
          "requested_ids" to idsToDelete,
        ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "日历删除失败：${throwable.message ?: "未知错误"}",
        recoverable = true,
        metadata = mapOf(
          "exception" to (throwable.message ?: "unknown"),
          "requested_ids" to idsToDelete,
        ),
      )
    }
  }

  private fun executeAlarmIntent(action: SystemAction, goal: String): ActionExecutionReport {
    val timeStr = action.payload?.get("time") as? String
      ?: return ActionExecutionReport(
        success = false,
        message = "Missing 'time' in payload",
        recoverable = false,
        semantic = "alarm_invalid_args",
      )

    val parsed = parseAlarmTime(timeStr)
      ?: return ActionExecutionReport(
        success = false,
        message = "Invalid 'time' format (expected local HH:mm or local ISO8601 datetime)",
        recoverable = false,
        semantic = "alarm_invalid_time_format",
      )

    val hourArg = parsed.first
    val minArg = parsed.second

    val labelArg = action.payload["label"] as? String ?: ""
    val vibrateArg = action.payload["vibrate"] as? Boolean ?: false

    // Note: The 'repeat' parameter is safely ignored here.
    // Setting AlarmClock.EXTRA_DAYS in Android requires an ArrayList<Integer> of Calendar.DAY_OF_WEEK.
    // It can be implemented strictly once the Agent contract is standardized without fuzzy inference.

    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM).apply {
        if (labelArg.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, labelArg)
        putExtra(AlarmClock.EXTRA_HOUR, hourArg)
        putExtra(AlarmClock.EXTRA_MINUTES, minArg)
        if (vibrateArg) putExtra(AlarmClock.EXTRA_VIBRATE, true)
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

    val report =
      launchIntent(
        intent = intent,
        successMessage = "Alarm creation requested in background mode for ${hourArg}:${minArg.toString().padStart(2, '0')}",
      )
    val alarmMeta =
      mapOf(
        "alarm_hour" to hourArg,
        "alarm_minute" to minArg,
        "label" to labelArg,
        "skip_ui" to true,
      )
    return if (report.success) {
      report.copy(
        semantic = "alarm_created_background_requested",
        metadata = alarmMeta,
      )
    } else {
      report.copy(
        semantic = "alarm_creation_failed",
        metadata = report.metadata + alarmMeta,
      )
    }
  }

  private fun parseAlarmTime(timeRaw: String): Pair<Int, Int>? {
    val text = timeRaw.trim()
    if (text.isEmpty()) return null

    // Preferred local wall-clock format.
    Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""").matchEntire(text)?.let { match ->
      val hour = match.groupValues[1].toIntOrNull() ?: return null
      val minute = match.groupValues[2].toIntOrNull() ?: return null
      return Pair(hour, minute)
    }

    // Local-time semantics for ISO-like strings:
    // keep the wall-clock hour/minute in text and do not apply timezone conversion.
    Regex(
      """^\d{4}-\d{2}-\d{2}T([01]\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?(?:\.\d+)?(?:Z|[+-][01]\d:[0-5]\d)?$""",
    ).matchEntire(text)?.let { match ->
      val hour = match.groupValues[1].toIntOrNull() ?: return null
      val minute = match.groupValues[2].toIntOrNull() ?: return null
      return Pair(hour, minute)
    }

    return null
  }

  private fun openWebPage(url: String): ActionExecutionReport {
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return launchIntent(intent, "Campus website opened")
  }

  /** Format a timestamp (epochMs) to local ISO datetime string for display. */
  private fun formatEpochMs(epochMs: Long): String {
    return runCatching {
      val instant = java.time.Instant.ofEpochMilli(epochMs)
      val zdt = instant.atZone(ZoneId.systemDefault())
      zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrElse { epochMs.toString() }
  }

  /** Convert conflict list to structured map list suitable for JSON submission. */
  private fun conflictsToDataList(conflicts: List<CalendarEventBrief>): List<Map<String, Any>> =
    conflicts.map { event ->
      mapOf(
        "id" to event.id,
        "title" to event.title,
        "start" to formatEpochMs(event.startMs),
        "end" to formatEpochMs(event.endMs),
      )
    }

  /** Build a human-readable conflict summary for the message field. */
  private fun conflictSummaryMessage(conflicts: List<CalendarEventBrief>): String {
    val items = conflicts.joinToString("; ") { event ->
      "「${event.title}」${formatEpochMs(event.startMs)}-${formatEpochMs(event.endMs)}"
    }
    return "冲突事项(${conflicts.size}): $items"
  }

  private fun launchIntent(intent: Intent, successMessage: String): ActionExecutionReport {
    return runCatching {
      appContext.startActivity(intent)
      ActionExecutionReport(
        success = true,
        message = successMessage,
        recoverable = false,
        semantic = "intent_launched",
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = throwable.message ?: "Intent execution failed",
        recoverable = true,
        semantic = "intent_launch_failed",
        metadata = mapOf("error" to (throwable.message ?: "Intent execution failed")),
      )
    }
  }

  private fun hasCalendarReadPermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

  private fun hasCalendarWritePermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

  private fun resolveWritableCalendarId(): Long? {
    val projection =
      arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        CalendarContract.Calendars.IS_PRIMARY,
      )
    val selection = "${CalendarContract.Calendars.VISIBLE}=1"
    val sort = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
    appContext.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      projection,
      selection,
      null,
      sort,
    )?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
      val accessIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
      while (cursor.moveToNext()) {
        val accessLevel = cursor.getInt(accessIdx)
        if (accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
          return cursor.getLong(idIdx)
        }
      }
    }
    return null
  }

  private fun deriveWindow(
    params: Map<String, String>,
    goal: String,
  ): CalendarWindow? {
    val extracted = extractIsoDateTime(goal)
    val startRaw = params["start_time"]?.trim().orEmpty().ifEmpty { extracted.firstOrNull().orEmpty() }
    val endRaw = params["end_time"]?.trim().orEmpty().ifEmpty { extracted.getOrNull(1).orEmpty() }

    val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    val now = OffsetDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)
    val start = runCatching { OffsetDateTime.parse(startRaw, formatter) }.getOrNull() ?: now.plusHours(1)
    val end = runCatching { OffsetDateTime.parse(endRaw, formatter) }.getOrNull() ?: start.plusHours(1)
    if (!end.isAfter(start)) return null
    return CalendarWindow(
      startMs = start.toInstant().toEpochMilli(),
      endMs = end.toInstant().toEpochMilli(),
    )
  }

  private fun queryConflicts(
    startMs: Long,
    endMs: Long,
  ): List<CalendarEventBrief> {
    val projection =
      arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
      )
    // Exclude all-day events (ALL_DAY=1) — their midnight UTC times cause false positives
    // against regular timed events.
    val selection =
      buildString {
        append("(")
        append("${CalendarContract.Events.DTSTART} < ?")
        append(" AND ")
        append("${CalendarContract.Events.DTEND} > ?")
        append(")")
        append(" AND ")
        append("(${CalendarContract.Events.DELETED} = 0 OR ${CalendarContract.Events.DELETED} IS NULL)")
        append(" AND ")
        append("(${CalendarContract.Events.ALL_DAY} = 0 OR ${CalendarContract.Events.ALL_DAY} IS NULL)")
      }
    val args = arrayOf(endMs.toString(), startMs.toString())
    val sort = "${CalendarContract.Events.DTSTART} ASC"
    val result = mutableListOf<CalendarEventBrief>()
    appContext.contentResolver.query(
      CalendarContract.Events.CONTENT_URI,
      projection,
      selection,
      args,
      sort,
    )?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
      val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
      val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
      val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
      while (cursor.moveToNext()) {
        val eventId = cursor.getLong(idIdx)
        val title = cursor.getString(titleIdx).orEmpty()
        val existingStart = cursor.getLong(startIdx)
        val existingEndRaw = cursor.getLong(endIdx)
        val existingEnd = max(existingEndRaw, existingStart + 60_000L)
        if (existingStart < endMs && startMs < existingEnd) {
          result += CalendarEventBrief(eventId, title, existingStart, existingEnd)
        }
      }
    }
    return result
  }

  private fun findEventsByTitleKeyword(
    keyword: String,
    limit: Int,
  ): List<CalendarEventBrief> {
    val projection =
      arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
      )
    val selection =
      "(${CalendarContract.Events.DELETED} = 0 OR ${CalendarContract.Events.DELETED} IS NULL) AND ${CalendarContract.Events.TITLE} LIKE ?"
    val args = arrayOf("%$keyword%")
    val sort = "${CalendarContract.Events.DTSTART} DESC"
    val result = mutableListOf<CalendarEventBrief>()
    appContext.contentResolver.query(
      CalendarContract.Events.CONTENT_URI,
      projection,
      selection,
      args,
      sort,
    )?.use { cursor ->
      val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
      val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
      val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
      val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
      while (cursor.moveToNext() && result.size < limit) {
        result +=
          CalendarEventBrief(
            id = cursor.getLong(idIdx),
            title = cursor.getString(titleIdx).orEmpty(),
            startMs = cursor.getLong(startIdx),
            endMs = cursor.getLong(endIdx),
          )
      }
    }
    return result
  }

  private fun deleteEventsByIds(ids: List<Long>): Int {
    var deletedCount = 0
    ids.forEach { id ->
      val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
      deletedCount += appContext.contentResolver.delete(uri, null, null)
    }
    return deletedCount
  }

  private fun parseIdList(raw: String?): List<Long> {
    if (raw.isNullOrBlank()) return emptyList()
    val text = raw.trim()
    if (text.startsWith("[")) {
      return runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { index -> arr.opt(index)?.toString()?.trim()?.toLongOrNull() }
      }.getOrElse { emptyList() }
    }
    return text.split(",").mapNotNull { it.trim().toLongOrNull() }
  }

  private fun parseSingleId(raw: String?): List<Long> {
    val parsed = raw?.trim()?.toLongOrNull() ?: return emptyList()
    return listOf(parsed)
  }

  private fun extractIsoDateTime(text: String): List<String> =
    Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})""")
      .findAll(text)
      .map { it.value }
      .toList()
}
