package ai.opencray.app.bridge

import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.execution.ActionExecutionReport
import ai.opencray.app.execution.ActionExecutor
import org.json.JSONArray
import org.json.JSONObject

fun interface ActionExecutionGateway {
  fun execute(action: SystemAction, goal: String): ActionExecutionReport
}

class PythonSkillBridgeExecutor(
  private val executionGateway: ActionExecutionGateway,
) {
  constructor(actionExecutor: ActionExecutor) : this(
    ActionExecutionGateway { action, goal -> actionExecutor.execute(action, goal) },
  )

  fun executeSkillInvocationJson(invocationJson: String): String {
    val invocation = JSONObject(invocationJson)
    return executeSkillInvocation(invocation).toString()
  }

  fun executeSkillInvocation(invocation: JSONObject): JSONObject {
    val requestId = invocation.optString("request_id")
    val skillName = invocation.optString("skill_name")
    val args = invocation.optJSONObject("args") ?: JSONObject()
    val params = mutableMapOf<String, String>()
    val keys = args.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      params[key] = toParamString(args.opt(key))
    }

    val riskLevel = invocation.optString("risk_level", "medium")
    val requiresApproval = invocation.optBoolean("requires_approval", false)
    val description = invocation.optString("description", "python bridge invocation")
    val goal = params["title"].takeUnless { it.isNullOrBlank() } ?: description

    val action =
      SystemAction(
        id = skillName,
        title = skillName,
        summary = "invoked from python bridge",
        riskLevel = riskLevel,
        requiresApproval = requiresApproval,
        params = params,
        explain = "dispatched by PythonSkillBridgeExecutor",
      )

    val report = executionGateway.execute(action, goal)
    val code = mapCode(skillName, report)
    val data = buildData(skillName, code, report)
    return JSONObject()
      .put("request_id", requestId)
      .put("code", code)
      .put("source", "android_kotlin_bridge")
      .put("data", data)
  }

  private fun mapCode(skillName: String, report: ActionExecutionReport): String {
    if (report.success) return "OK"
    val message = report.message
    if (message.contains("confirm_delete=true", ignoreCase = true)) return "APPROVAL_REQUIRED"
    if (skillName == "create_calendar_event" &&
      message.contains("Choose skip_write / coexist / delete_conflicts", ignoreCase = true)
    ) {
      return "APPROVAL_REQUIRED"
    }
    if (message.contains("Invalid", ignoreCase = true) ||
      message.contains("Missing", ignoreCase = true) ||
      message.contains("requires event_id/event_ids", ignoreCase = true) ||
      message.contains("requires explicit confirmation", ignoreCase = true)
    ) {
      return "INVALID_PARAM"
    }
    if (message.contains("permission", ignoreCase = true)) return "ACTION_NOT_ALLOWED"
    return "SKILL_EXECUTION_FAILED"
  }

  private fun buildData(
    skillName: String,
    code: String,
    report: ActionExecutionReport,
  ): JSONObject {
    val message = report.message
    return when (skillName) {
      "create_calendar_event" -> {
        val status =
          when {
            code == "APPROVAL_REQUIRED" -> "conflict_detected"
            report.success && message.contains("skipped writing", ignoreCase = true) -> "skipped_conflict"
            report.success -> "created"
            else -> "failed"
          }
        JSONObject()
          .put("status", status)
          .put("event_id", parseEventId(message))
          .put("conflict_count", parseConflictCount(message))
          .put("message", message)
      }
      "detect_calendar_conflicts" -> {
        JSONObject()
          .put("status", if (report.success) "detected" else "failed")
          .put("supports_overlap", true)
          .put("conflict_count", parseConflictCount(message))
          .put("decision_options", JSONArray(listOf("skip_write", "coexist", "delete_conflicts")))
          .put("message", message)
      }
      "delete_calendar_event" -> {
        val status =
          when {
            code == "APPROVAL_REQUIRED" -> "awaiting_confirmation"
            report.success -> "deleted"
            else -> "failed"
          }
        JSONObject()
          .put("status", status)
          .put("deleted_count", parseDeletedCount(message))
          .put("high_risk", true)
          .put("message", message)
      }
      else -> JSONObject()
        .put("status", if (report.success) "ok" else "failed")
        .put("message", message)
    }
  }

  private fun parseEventId(message: String): String? {
    val match = Regex("""event_id=([A-Za-z0-9_-]+)""").find(message) ?: return null
    return match.groupValues[1]
  }

  private fun parseConflictCount(message: String): Int {
    val direct = Regex("""conflicts?=(\d+)""", RegexOption.IGNORE_CASE).find(message)
    if (direct != null) return direct.groupValues[1].toIntOrNull() ?: 0
    val detect = Regex("""completed:\s*(\d+)\s*overlap""", RegexOption.IGNORE_CASE).find(message)
    if (detect != null) return detect.groupValues[1].toIntOrNull() ?: 0
    val conflict = Regex("""Conflict detected\s*\((\d+)\)""", RegexOption.IGNORE_CASE).find(message)
    if (conflict != null) return conflict.groupValues[1].toIntOrNull() ?: 0
    return 0
  }

  private fun parseDeletedCount(message: String): Int {
    val match = Regex("""Deleted\s+(\d+)\s+calendar event""", RegexOption.IGNORE_CASE).find(message)
    return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
  }

  private fun toParamString(value: Any?): String {
    return when (value) {
      null -> ""
      is JSONObject -> value.toString()
      is JSONArray -> value.toString()
      else -> value.toString()
    }
  }
}
