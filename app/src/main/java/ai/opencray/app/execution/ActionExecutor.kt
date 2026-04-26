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
    val timeStr = action.payload?.get("time") as? String
      ?: return ActionExecutionReport(false, "Missing 'time' in payload", false)

    val zdt = runCatching { Instant.parse(timeStr).atZone(ZoneId.systemDefault()) }.getOrNull()
      ?: return ActionExecutionReport(false, "Invalid 'time' format (must be ISO8601 UTC)", false)

    val hourArg = zdt.hour
    val minArg = zdt.minute

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
