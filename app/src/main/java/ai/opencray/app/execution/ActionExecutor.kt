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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import org.json.JSONArray

data class ActionExecutionReport(
  val success: Boolean,
  val message: String,
  val recoverable: Boolean,
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

class ActionExecutor(
  private val appContext: Context,
) {
  fun execute(action: SystemAction, goal: String): ActionExecutionReport {
    return when (action.id) {
      "create_calendar_event" -> executeCreateCalendarEvent(action, goal)
      "detect_calendar_conflicts" -> executeConflictDetection(action, goal)
      "delete_calendar_event" -> executeDeleteCalendarEvent(action)
      "set_alarm_reminder", "set_alarm" -> executeAlarmIntent(action, goal)
      "open_tsinghua_news" -> openWebPage("https://www.tsinghua.edu.cn")
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
            message = "Conflict detected (${conflicts.size}), skipped writing by decision.",
            recoverable = false,
          )
        }
        "coexist" -> {
          // Android calendar supports overlapping events.
        }
        "delete_conflicts" -> {
          val allowDelete = action.params["allow_conflict_delete"]?.toBooleanStrictOrNull() ?: false
          if (!allowDelete) {
            return ActionExecutionReport(
              success = false,
              message = "Conflict delete requires explicit confirmation (allow_conflict_delete=true).",
              recoverable = false,
            )
          }
          deleteEventsByIds(conflicts.map { it.id })
        }
        else -> {
          return ActionExecutionReport(
            success = false,
            message = "Conflict detected (${conflicts.size}). Choose skip_write / coexist / delete_conflicts.",
            recoverable = false,
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
        message = "Calendar event created (event_id=$eventId, conflicts=${conflicts.size}).",
        recoverable = false,
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = throwable.message ?: "Calendar insert failed",
        recoverable = true,
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
    val overlapSupported = true
    return ActionExecutionReport(
      success = true,
      message =
        buildString {
          append("Conflict check completed: ${conflicts.size} overlap(s). ")
          append("Android calendar overlap supported=$overlapSupported. ")
          append("Options: skip_write | coexist | delete_conflicts.")
        },
      recoverable = false,
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
        message = "Deleted $deleted calendar event(s).",
        recoverable = false,
      )
    }.getOrElse { throwable ->
      ActionExecutionReport(
        success = false,
        message = throwable.message ?: "Calendar delete failed",
        recoverable = true,
      )
    }
  }

  private fun executeAlarmIntent(action: SystemAction, goal: String): ActionExecutionReport {
    // Parse time argument from LangGraph payload if available (e.g. ISO8601 UTC or HH:mm format)
    var hourArg = 8
    var minArg = 0
    val timeStr = action.params["time"] ?: (action.payload?.get("time") as? String)
    if (!timeStr.isNullOrBlank()) {
      runCatching {
        // Try parsing ISO8601 UTC to local device time (per API.md: "2026-04-28T07:30:00Z")
        val zdt = Instant.parse(timeStr).atZone(ZoneId.systemDefault())
        hourArg = zdt.hour
        minArg = zdt.minute
      }.onFailure {
        // Fallback for simple "HH:mm"
        val parts = timeStr.split(":")
        if (parts.size >= 2) {
          hourArg = parts[0].toIntOrNull() ?: 8
          minArg = parts[1].toIntOrNull() ?: 0
        }
      }
    }
    
    val labelArg = action.params["label"] ?: (action.payload?.get("label") as? String) ?: "OpenTHU task: ${goal.take(20)}"
    val vibrateArg =
      action.params["vibrate"]?.toBooleanStrictOrNull() ?: (action.payload?.get("vibrate") as? Boolean) ?: false

    // Note: The 'repeat' parameter is safely ignored here.
    // RD.md (v1.1-draft) mentions it as an optional parameter, but lacks a strict format definition (e.g. List of days vs Boolean). 
    // API.md entirely omits it. Setting AlarmClock.EXTRA_DAYS in Android requires an ArrayList<Integer> of 
    // Calendar.DAY_OF_WEEK constants, which we can implement once the Agent contract is standardized.

    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_MESSAGE, labelArg)
        putExtra(AlarmClock.EXTRA_HOUR, hourArg)
        putExtra(AlarmClock.EXTRA_MINUTES, minArg)
        if (vibrateArg) putExtra(AlarmClock.EXTRA_VIBRATE, true)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

    return launchIntent(intent, "Alarm setup intent launched for ${hourArg}:${minArg.toString().padStart(2, '0')}")
  }

  private fun openWebPage(url: String): ActionExecutionReport {
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return launchIntent(intent, "Campus website opened")
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
    val selection =
      buildString {
        append("(")
        append("${CalendarContract.Events.DTSTART} < ?")
        append(" AND ")
        append("${CalendarContract.Events.DTEND} > ?")
        append(")")
        append(" AND ")
        append("(${CalendarContract.Events.DELETED} = 0 OR ${CalendarContract.Events.DELETED} IS NULL)")
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
