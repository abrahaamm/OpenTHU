package ai.opencray.app.execution

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import ai.opencray.app.domain.model.SystemAction
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

data class ActionExecutionReport(
  val success: Boolean,
  val message: String,
  val recoverable: Boolean,
  /** Structured key-value data submitted to server (e.g. conflict details, created event id). */
  val data: Map<String, Any> = emptyMap(),
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

private data class HomeworkAttachment(
  val token: String,
  val fileName: String,
  val downloadUrl: String = "",
  val previewUrl: String = "",
)

private data class HomeworkRecord(
  val homeworkId: String,
  val studentHomeworkId: String,
  val title: String,
  val deadline: String,
  val submitted: Boolean,
  val courseId: String,
  val courseName: String,
  val detailUrl: String,
)

class ActionExecutor(
  private val appContext: Context,
) {
  fun execute(action: SystemAction, goal: String): ActionExecutionReport {
    val actionId = action.id.substringBefore("#")
    return when (actionId) {
      "create_calendar_event" -> executeCreateCalendarEvent(action, goal)
      "detect_calendar_conflicts" -> executeConflictDetection(action, goal)
      "delete_calendar_event" -> executeDeleteCalendarEvent(action)
      "crawl_course_homeworks" -> executeCrawlCourseHomeworks(action, unsubmittedOnly = false)
      "crawl_unsubmitted_homeworks" -> executeCrawlCourseHomeworks(action, unsubmittedOnly = true)
      "preview_homework_attachments" -> executePreviewHomeworkAttachments(action)
      "upload_homework_attachment" -> executeUploadHomeworkAttachment(action)
      "submit_homework" -> executeSubmitHomework(action)
      "set_alarm_reminder", "set_alarm" -> executeAlarmIntent(action, goal)
      "open_tsinghua_news" -> openWebPage("https://www.tsinghua.edu.cn")
      "open_url" -> {
        val url =
          (action.payload?.get("url") as? String)
            ?: action.params["url"]
            ?: "https://www.tsinghua.edu.cn"
        openWebPage(url)
      }
      "open_context_review" ->
        ActionExecutionReport(
          success = true,
          message = "Opened context review fallback path.",
          recoverable = false,
        )
      else ->
        ActionExecutionReport(
          success = true,
          message = "No-op fallback executed for ${action.id}.",
          recoverable = false,
        )
    }
  }

  private fun executeCrawlCourseHomeworks(
    action: SystemAction,
    unsubmittedOnly: Boolean,
  ): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val baseUrl = auth.baseUrl
    val cookie = auth.cookie
    val csrf = auth.csrf
    val courseIds = parseCsvOrJsonArray(action.params["course_ids"])
    if (courseIds.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "course_ids is required for homework crawl.",
        recoverable = false,
        data = mapOf("reason" to "missing_course_ids"),
      )
    }

    val records = mutableListOf<HomeworkRecord>()
    val endpointTypes = if (unsubmittedOnly) listOf("WJ") else listOf("WJ", "YJWG", "YPG")
    val endpointMap =
      mapOf(
        "WJ" to "/b/wlxt/kczy/zy/student/zyListWj",
        "YJWG" to "/b/wlxt/kczy/zy/student/zyListYjwg",
        "YPG" to "/b/wlxt/kczy/zy/student/zyListYpg",
      )

    return runCatching {
      courseIds.forEach { courseId ->
        endpointTypes.forEach { endpointType ->
          val endpoint = endpointMap[endpointType].orEmpty()
          val raw =
            postForm(
              url = "$baseUrl$endpoint",
              form = mapOf("wlkcid" to courseId),
              cookie = cookie,
              csrfToken = csrf,
            )
          val parsed = runCatching { JSONObject(raw) }.getOrNull()
          val items = extractHomeworkRecords(parsed, courseId, endpointType, baseUrl)
          records += items
        }
      }

      val deduped = records.distinctBy { "${it.homeworkId}::${it.studentHomeworkId}" }.sortedBy { it.deadline }
      val status = if (unsubmittedOnly) "unsubmitted_crawled" else "crawled"
      ActionExecutionReport(
        success = true,
        message = "Homework crawl completed: ${deduped.size} item(s).",
        recoverable = false,
        data =
          mapOf(
            "status" to status,
            "count" to deduped.size,
            "homeworks" to deduped.map { it.toDataMap() },
            "course_ids" to courseIds,
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Homework crawl failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "crawl_failed",
            "exception" to (throwable.message ?: "unknown"),
            "course_ids" to courseIds,
          ),
      )
    }
  }

  private fun executePreviewHomeworkAttachments(action: SystemAction): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val baseUrl = auth.baseUrl
    val homeworkId = action.params["homework_id"]?.trim().orEmpty()
    if (homeworkId.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "homework_id is required.",
        recoverable = false,
        data = mapOf("reason" to "missing_homework_id"),
      )
    }

    val detailUrl = "$baseUrl/b/wlxt/kczy/zy/student/detail"
    return runCatching {
      val raw =
        postForm(
          url = detailUrl,
          form = mapOf("id" to homeworkId),
          cookie = auth.cookie,
          csrfToken = auth.csrf,
        )
      val parsed = runCatching { JSONObject(raw) }.getOrNull()
      val attachments = extractAttachments(parsed, baseUrl)
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          "$baseUrl/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework attachment preview opened. Found ${attachments.size} attachment(s).",
        recoverable = false,
        data =
          mapOf(
            "status" to "preview_ready",
            "homework_id" to homeworkId,
            "attachments" to attachments.map { it.toDataMap() },
            "opened_url" to openUrl,
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Preview homework attachments failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "preview_failed",
            "homework_id" to homeworkId,
            "exception" to (throwable.message ?: "unknown"),
          ),
      )
    }
  }

  private fun executeUploadHomeworkAttachment(action: SystemAction): ActionExecutionReport {
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val homeworkId = action.params["homework_id"]?.trim().orEmpty()
    if (homeworkId.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "homework_id is required.",
        recoverable = false,
        data = mapOf("reason" to "missing_homework_id"),
      )
    }
    val fileRef = action.params["file_path"]?.trim().orEmpty()
    val fileUri = action.params["file_uri"]?.trim().orEmpty()
    if (fileRef.isEmpty() && fileUri.isEmpty()) {
      // No file yet: open a page and document picker so user can pick a file on-device.
      val pickerIntent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      launchIntent(pickerIntent, "Opened file picker for homework attachment.")
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      return ActionExecutionReport(
        success = false,
        message = "No file provided. Opened file picker and homework page for manual selection.",
        recoverable = false,
        data =
          mapOf(
            "status" to "awaiting_file_selection",
            "homework_id" to homeworkId,
            "opened_url" to openUrl,
          ),
      )
    }

    return runCatching {
      val uploadUrl = "${auth.baseUrl}/b/wlxt/kczy/zy/student/tjzy"
      val filePart = resolveFilePart(fileRef, fileUri, action.params["file_name"].orEmpty())
      val textBody = action.params["submission_text"].orEmpty()
      val responseBody =
        postMultipart(
          url = uploadUrl,
          cookie = auth.cookie,
          csrfToken = auth.csrf,
          textParts =
            mapOf(
              "xszyid" to homeworkId,
              "zynr" to textBody,
              "isDeleted" to "0",
            ),
          filePart = filePart,
        )
      val token = "upload_${System.currentTimeMillis()}"
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework attachment uploaded successfully.",
        recoverable = false,
        data =
          mapOf(
            "status" to "uploaded",
            "homework_id" to homeworkId,
            "attachment_token" to token,
            "file_name" to filePart.fileName,
            "opened_url" to openUrl,
            "upstream_response_excerpt" to responseBody.take(512),
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Upload homework attachment failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "upload_failed",
            "homework_id" to homeworkId,
            "exception" to (throwable.message ?: "unknown"),
          ),
      )
    }
  }

  private fun executeSubmitHomework(action: SystemAction): ActionExecutionReport {
    val confirmed = action.params["confirm_submit"]?.toBooleanStrictOrNull() ?: false
    if (!confirmed) {
      return ActionExecutionReport(
        success = false,
        message = "Submit blocked: confirm_submit=true is required for high-risk homework submission.",
        recoverable = false,
        data = mapOf("reason" to "confirm_submit_required"),
      )
    }
    val auth = resolveHomeworkAuth(action)
    if (!auth.ok) {
      return ActionExecutionReport(
        success = false,
        message = auth.message,
        recoverable = false,
        data = mapOf("reason" to "missing_auth"),
      )
    }
    val homeworkId = action.params["homework_id"]?.trim().orEmpty()
    if (homeworkId.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "homework_id is required.",
        recoverable = false,
        data = mapOf("reason" to "missing_homework_id"),
      )
    }

    val submissionText = action.params["submission_text"].orEmpty()
    var fileRef = action.params["file_path"]?.trim().orEmpty()
    var fileUri = action.params["file_uri"]?.trim().orEmpty()
    val localFilePaths = parseCsvOrJsonArray(action.params["local_file_paths"])
    if (fileRef.isEmpty() && fileUri.isEmpty() && localFilePaths.isNotEmpty()) {
      fileRef = localFilePaths.first()
    }
    val attachmentTokens = parseCsvOrJsonArray(action.params["attachment_tokens"])
    val hasFile = fileRef.isNotEmpty() || fileUri.isNotEmpty()
    if (submissionText.isBlank() && !hasFile && attachmentTokens.isEmpty()) {
      return ActionExecutionReport(
        success = false,
        message = "submit_homework requires submission_text or file_path/file_uri/local_file_paths or attachment_tokens.",
        recoverable = false,
        data = mapOf("reason" to "missing_submission_content"),
      )
    }

    return runCatching {
      val submitUrl = "${auth.baseUrl}/b/wlxt/kczy/zy/student/tjzy"
      val filePart = if (hasFile) resolveFilePart(fileRef, fileUri, action.params["file_name"].orEmpty()) else null
      val attachmentTokenText = attachmentTokens.joinToString(",")
      val responseBody =
        postMultipart(
          url = submitUrl,
          cookie = auth.cookie,
          csrfToken = auth.csrf,
          textParts =
            mapOf(
              "xszyid" to homeworkId,
              "zynr" to submissionText,
              "fjids" to attachmentTokenText,
              "isDeleted" to "0",
            ),
          filePart = filePart,
        )
      val openUrl =
        action.params["homework_detail_url"]?.trim().orEmpty().ifEmpty {
          "${auth.baseUrl}/f/wlxt/kczy/zy/student/viewZy?zyid=$homeworkId"
        }
      openWebPage(openUrl)
      ActionExecutionReport(
        success = true,
        message = "Homework submitted successfully.",
        recoverable = false,
        data =
          mapOf(
            "status" to "submitted",
            "homework_id" to homeworkId,
            "submitted_at" to Instant.now().toString(),
            "attachment_tokens" to attachmentTokens,
            "local_file_paths" to localFilePaths,
            "opened_url" to openUrl,
            "upstream_response_excerpt" to responseBody.take(512),
          ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "Submit homework failed: ${throwable.message ?: "unknown"}",
        recoverable = true,
        data =
          mapOf(
            "reason" to "submit_failed",
            "homework_id" to homeworkId,
            "exception" to (throwable.message ?: "unknown"),
          ),
      )
    }
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
            data = mapOf(
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
              data = mapOf(
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
            data = mapOf(
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
        data = mapOf(
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
        data = mapOf("exception" to (throwable.message ?: "unknown")),
      )
    }
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
      data = mapOf(
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
        data = mapOf(
          "deleted_count" to deleted,
          "requested_ids" to idsToDelete,
        ),
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = "日历删除失败：${throwable.message ?: "未知错误"}",
        recoverable = true,
        data = mapOf(
          "exception" to (throwable.message ?: "unknown"),
          "requested_ids" to idsToDelete,
        ),
      )
    }
  }

  private fun executeAlarmIntent(action: SystemAction, goal: String): ActionExecutionReport {
    val timeStr = action.payload?.get("time") as? String
      ?: return ActionExecutionReport(false, "Missing 'time' in payload", false)

    val parsed = parseAlarmTime(timeStr)
      ?: return ActionExecutionReport(false, "Invalid 'time' format (expected ISO8601 UTC or HH:mm)", false)

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
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

    return launchIntent(intent, "Alarm setup intent launched for ${hourArg}:${minArg.toString().padStart(2, '0')}")
  }

  private fun parseAlarmTime(timeRaw: String): Pair<Int, Int>? {
    runCatching {
      val zdt = Instant.parse(timeRaw).atZone(ZoneId.systemDefault())
      return Pair(zdt.hour, zdt.minute)
    }

    val match = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""").matchEntire(timeRaw.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return Pair(hour, minute)
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
      ActionExecutionReport(success = true, message = successMessage, recoverable = false)
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = throwable.message ?: "Intent execution failed",
        recoverable = true,
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

  private data class HomeworkAuth(
    val ok: Boolean,
    val message: String,
    val baseUrl: String = "https://learn.tsinghua.edu.cn",
    val cookie: String = "",
    val csrf: String = "",
  )

  private data class MultipartFilePart(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
  )

  private fun resolveHomeworkAuth(action: SystemAction): HomeworkAuth {
    val cookieFromParams = action.params["session_cookie"].orEmpty().trim()
    val cookieFromPayload = action.payload?.get("session_cookie")?.toString()?.trim().orEmpty()
    val cookie = cookieFromParams.ifEmpty { cookieFromPayload }
    val baseUrl =
      action.params["learn_base_url"]?.trim().orEmpty().ifEmpty {
        action.payload?.get("learn_base_url")?.toString()?.trim().orEmpty()
      }.ifEmpty { "https://learn.tsinghua.edu.cn" }
    val csrf =
      action.params["csrf_token"]?.trim().orEmpty().ifEmpty {
        action.payload?.get("csrf_token")?.toString()?.trim().orEmpty()
      }
    if (cookie.isEmpty()) {
      return HomeworkAuth(
        ok = false,
        message = "Missing session_cookie for homework action. Please pass an authenticated Learn cookie.",
      )
    }
    return HomeworkAuth(ok = true, message = "ok", baseUrl = baseUrl, cookie = cookie, csrf = csrf)
  }

  private fun postForm(
    url: String,
    form: Map<String, String>,
    cookie: String,
    csrfToken: String,
  ): String {
    val body =
      form.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
      }
    val conn = (URL(appendCsrf(url, csrfToken)).openConnection() as HttpURLConnection)
    conn.requestMethod = "POST"
    conn.connectTimeout = 12_000
    conn.readTimeout = 18_000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    conn.setRequestProperty("Cookie", cookie)
    conn.outputStream.use { os ->
      val bytes = body.toByteArray(StandardCharsets.UTF_8)
      os.write(bytes)
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    conn.disconnect()
    if (code !in 200..299) {
      throw IllegalStateException("HTTP $code for $url: ${raw.take(256)}")
    }
    return raw
  }

  private fun postMultipart(
    url: String,
    cookie: String,
    csrfToken: String,
    textParts: Map<String, String>,
    filePart: MultipartFilePart?,
  ): String {
    val boundary = "----OpenTHUBoundary${System.currentTimeMillis()}"
    val conn = (URL(appendCsrf(url, csrfToken)).openConnection() as HttpURLConnection)
    conn.requestMethod = "POST"
    conn.connectTimeout = 12_000
    conn.readTimeout = 24_000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    conn.setRequestProperty("Cookie", cookie)

    DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
      textParts.forEach { (name, value) ->
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(StandardCharsets.UTF_8))
        out.writeBytes("\r\n")
      }
      if (filePart != null) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes(
          "Content-Disposition: form-data; name=\"fileupload\"; filename=\"${filePart.fileName}\"\r\n",
        )
        out.writeBytes("Content-Type: ${filePart.mimeType}\r\n\r\n")
        out.write(filePart.bytes)
        out.writeBytes("\r\n")
      }
      out.writeBytes("--$boundary--\r\n")
      out.flush()
    }

    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    conn.disconnect()
    if (code !in 200..299) {
      throw IllegalStateException("HTTP $code for $url: ${raw.take(256)}")
    }
    return raw
  }

  private fun resolveFilePart(
    filePath: String,
    fileUriText: String,
    overrideFileName: String,
  ): MultipartFilePart {
    if (filePath.isNotBlank()) {
      val file = File(filePath)
      if (!file.exists() || !file.isFile) {
        throw IllegalArgumentException("File not found at file_path: $filePath")
      }
      val bytes = FileInputStream(file).use { input -> input.readBytes() }
      val resolvedName = overrideFileName.ifBlank { file.name.ifBlank { "upload.bin" } }
      return MultipartFilePart(
        fileName = resolvedName,
        mimeType = guessMimeType(resolvedName),
        bytes = bytes,
      )
    }
    if (fileUriText.isNotBlank()) {
      val uri = Uri.parse(fileUriText)
      val bytes =
        appContext.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
          ?: throw IllegalArgumentException("Cannot open file_uri: $fileUriText")
      val name = overrideFileName.ifBlank { guessFileNameFromUri(uri) }
      return MultipartFilePart(
        fileName = name,
        mimeType = guessMimeType(name),
        bytes = bytes,
      )
    }
    throw IllegalArgumentException("Either file_path or file_uri is required.")
  }

  private fun parseCsvOrJsonArray(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val text = raw.trim()
    if (text.startsWith("[")) {
      return runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { index -> arr.opt(index)?.toString()?.trim() }.filter { it.isNotEmpty() }
      }.getOrElse { emptyList() }
    }
    return text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
  }

  private fun extractHomeworkRecords(
    root: JSONObject?,
    courseId: String,
    endpointType: String,
    baseUrl: String,
  ): List<HomeworkRecord> {
    if (root == null) return emptyList()
    val list = mutableListOf<HomeworkRecord>()
    val candidates = mutableListOf<JSONObject>()
    collectObjectsRecursively(root, candidates)
    candidates.forEach { obj ->
      val id = obj.optString("id").ifBlank { obj.optString("zyid") }
      val xszyid = obj.optString("xszyid").ifBlank { obj.optString("studentHomeworkId") }
      val title = obj.optString("bt").ifBlank { obj.optString("title") }
      if (id.isBlank() || title.isBlank()) return@forEach
      val deadline = obj.optString("jzsj").ifBlank { obj.optString("deadline") }
      val submitted = when (endpointType) {
        "WJ" -> false
        else -> true
      }
      val detailUrl = "$baseUrl/f/wlxt/kczy/zy/student/viewZy?zyid=$id"
      list +=
        HomeworkRecord(
          homeworkId = id,
          studentHomeworkId = xszyid,
          title = title,
          deadline = deadline,
          submitted = submitted,
          courseId = courseId,
          courseName = obj.optString("kcmc"),
          detailUrl = detailUrl,
        )
    }
    return list
  }

  private fun extractAttachments(
    root: JSONObject?,
    baseUrl: String,
  ): List<HomeworkAttachment> {
    if (root == null) return emptyList()
    val objects = mutableListOf<JSONObject>()
    collectObjectsRecursively(root, objects)
    val result = mutableListOf<HomeworkAttachment>()
    objects.forEach { obj ->
      val name = obj.optString("name").ifBlank { obj.optString("wjmc") }
      val token = obj.optString("id").ifBlank { obj.optString("wjid") }
      if (name.isBlank() && token.isBlank()) return@forEach
      val download = obj.optString("downloadUrl")
      val preview = obj.optString("previewUrl")
      result +=
        HomeworkAttachment(
          token = token.ifBlank { "att_${result.size + 1}" },
          fileName = name.ifBlank { "attachment_${result.size + 1}" },
          downloadUrl = absolutizeUrl(download, baseUrl),
          previewUrl = absolutizeUrl(preview, baseUrl),
        )
    }
    return result.distinctBy { "${it.token}::${it.fileName}" }
  }

  private fun collectObjectsRecursively(
    root: Any?,
    collector: MutableList<JSONObject>,
  ) {
    when (root) {
      is JSONObject -> {
        collector += root
        val keys = root.keys()
        while (keys.hasNext()) {
          val key = keys.next()
          collectObjectsRecursively(root.opt(key), collector)
        }
      }
      is JSONArray -> {
        for (i in 0 until root.length()) {
          collectObjectsRecursively(root.opt(i), collector)
        }
      }
    }
  }

  private fun appendCsrf(
    url: String,
    csrfToken: String,
  ): String {
    if (csrfToken.isBlank()) return url
    if (url.contains("token=")) return url
    val sep = if (url.contains("?")) "&" else "?"
    return "$url${sep}token=${urlEncode(csrfToken)}"
  }

  private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

  private fun guessMimeType(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
      lower.endsWith(".pdf") -> "application/pdf"
      lower.endsWith(".doc") -> "application/msword"
      lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      lower.endsWith(".ppt") -> "application/vnd.ms-powerpoint"
      lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
      lower.endsWith(".txt") -> "text/plain"
      lower.endsWith(".zip") -> "application/zip"
      else -> "application/octet-stream"
    }
  }

  private fun guessFileNameFromUri(uri: Uri): String {
    val segment = uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
    return if (segment.isBlank()) "upload.bin" else segment
  }

  private fun HomeworkRecord.toDataMap(): Map<String, Any> =
    mapOf(
      "homework_id" to homeworkId,
      "student_homework_id" to studentHomeworkId,
      "title" to title,
      "deadline" to deadline,
      "submitted" to submitted,
      "course_id" to courseId,
      "course_name" to courseName,
      "detail_url" to detailUrl,
    )

  private fun HomeworkAttachment.toDataMap(): Map<String, Any> =
    mapOf(
      "attachment_token" to token,
      "file_name" to fileName,
      "download_url" to downloadUrl,
      "preview_url" to previewUrl,
    )

  private fun absolutizeUrl(url: String, baseUrl: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    if (trimmed.startsWith("/")) return baseUrl.trimEnd('/') + trimmed
    return "$baseUrl/$trimmed"
  }

  private fun extractIsoDateTime(text: String): List<String> =
    Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})""")
      .findAll(text)
      .map { it.value }
      .toList()
}
