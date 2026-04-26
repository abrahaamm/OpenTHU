package ai.opencray.app.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import ai.opencray.app.domain.model.SystemAction
import java.time.Instant
import java.time.ZoneId

data class ActionExecutionReport(
  val success: Boolean,
  val message: String,
  val recoverable: Boolean,
)

class ActionExecutor(
  private val appContext: Context,
) {
  fun execute(action: SystemAction, goal: String): ActionExecutionReport {
    return when (action.id) {
      "create_calendar_event" -> executeCalendarIntent(goal)
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

  private fun executeCalendarIntent(goal: String): ActionExecutionReport {
    val intent =
      Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, "OpenTHU campus task")
        putExtra(CalendarContract.Events.DESCRIPTION, goal)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

    return launchIntent(intent, "Calendar intent launched")
  }

  private fun executeAlarmIntent(action: SystemAction, goal: String): ActionExecutionReport {
    // Parse time argument from LangGraph payload if available (e.g. ISO8601 UTC or HH:mm format)
    var hourArg = 8
    var minArg = 0
    val timeStr = action.payload?.get("time") as? String
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
    
    val labelArg = (action.payload?.get("label") as? String) ?: "OpenTHU task: ${goal.take(20)}"
    val vibrateArg = (action.payload?.get("vibrate") as? Boolean) ?: false

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
}
