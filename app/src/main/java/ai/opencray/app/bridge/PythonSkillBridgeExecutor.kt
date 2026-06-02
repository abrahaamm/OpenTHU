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
    val reason = report.metadata["reason"]?.toString()?.trim().orEmpty()
    val message = report.message
    if (
      reason == "conflict_strategy_required" ||
      reason == "allow_conflict_delete_not_set" ||
      reason == "confirm_submit_required"
    ) {
      return "APPROVAL_REQUIRED"
    }
    if (reason == "login_required" || report.semantic == "homework_cookie_login_required" || report.semantic == "course_info_auth") {
      return "NOT_CONFIGURED"
    }
    if (message.contains("confirm_delete=true", ignoreCase = true) ||
      message.contains("confirm_submit=true", ignoreCase = true)
    ) {
      return "APPROVAL_REQUIRED"
    }
    if (skillName == "create_calendar_event" &&
      message.contains("Choose skip_write / coexist / delete_conflicts", ignoreCase = true)
    ) {
      return "APPROVAL_REQUIRED"
    }
    if (reason == "missing_auth" ||
      reason == "missing_credentials" ||
      reason == "invalid_cookies" ||
      reason == "credential_login_not_implemented" ||
      reason == "missing_homework_id" ||
      reason == "missing_course_id" ||
      reason == "missing_submission_content" ||
      reason == "missing_file" ||
      message.contains("Invalid", ignoreCase = true) ||
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
      "get_homework_cookie" -> {
        val reportedStatus = report.metadata["status"]?.toString().orEmpty()
        val data =
          JSONObject()
            .put("status", reportedStatus.ifBlank { if (report.success) "cookie_ready" else "failed" })
            .put("message", message)
        putReportData(data, report)
      }
      "crawl_course_homeworks" -> {
        val data =
          JSONObject()
            .put("status", if (report.success) "crawled" else reportedFailureStatus(report))
            .put("message", message)
        putReportData(data, report)
      }
      "crawl_unsubmitted_homeworks" -> {
        val data =
          JSONObject()
            .put("status", if (report.success) "unsubmitted_crawled" else reportedFailureStatus(report))
            .put("message", message)
        putReportData(data, report)
      }
      "preview_homework_attachments" -> {
        val data =
          JSONObject()
            .put("status", if (report.success) "preview_ready" else reportedFailureStatus(report))
            .put("message", message)
        putReportData(data, report)
      }
      "upload_homework_attachment" -> {
        val status =
          when {
            code == "APPROVAL_REQUIRED" -> "awaiting_confirmation"
            report.success -> "uploaded"
            else -> reportedFailureStatus(report)
          }
        val data =
          JSONObject()
            .put("status", status)
            .put("message", message)
        putReportData(data, report)
      }
      "submit_homework" -> {
        val status =
          when {
            code == "APPROVAL_REQUIRED" -> "awaiting_confirmation"
            report.success -> "submitted"
            else -> reportedFailureStatus(report)
          }
        val data =
          JSONObject()
            .put("status", status)
            .put("high_risk", true)
            .put("message", message)
        putReportData(data, report)
      }
      "get_semesters" -> {
        val data =
          JSONObject()
            .put("status", if (report.success) "ok" else reportedFailureStatus(report))
            .put("message", message)
        report.metadata["current_semester"]?.let { data.put("current_semester", it.toString()) }
        val semesters = report.metadata["semesters"]
        if (semesters is List<*>) {
          data.put("semesters", JSONArray(semesters.mapNotNull { it as? Map<*, *> }.map { toJsonObject(it) }))
          data.put("semester_count", semesters.size)
        }
        putReportData(data, report)
      }
      "get_courses" -> {
        val data =
          JSONObject()
            .put("status", if (report.success) "ok" else reportedFailureStatus(report))
            .put("message", message)
        report.metadata["semester_id"]?.let { data.put("semester_id", it.toString()) }
        val courses = report.metadata["courses"]
        if (courses is List<*>) {
          data.put("courses", JSONArray(courses.mapNotNull { it as? Map<*, *> }.map { toJsonObject(it) }))
          data.put("course_count", courses.size)
        }
        putReportData(data, report)
      }
      "get_course_schedule" -> {
        val data =
          JSONObject()
            .put("status", if (report.success) "ok" else reportedFailureStatus(report))
            .put("message", message)
        report.metadata["semester"]?.let { semester ->
          if (semester is Map<*, *>) data.put("semester", toJsonObject(semester))
        }
        val scheduleEntries = report.metadata["schedule_entries"]
        if (scheduleEntries is List<*>) {
          data.put("schedule_entries", JSONArray(scheduleEntries.mapNotNull { it as? Map<*, *> }.map { toJsonObject(it) }))
          data.put("schedule_count", scheduleEntries.size)
        }
        val courses = report.metadata["courses"]
        if (courses is List<*>) {
          data.put("courses", JSONArray(courses.mapNotNull { it as? Map<*, *> }.map { toJsonObject(it) }))
        }
        val warnings = report.metadata["warnings"]
        if (warnings is List<*>) {
          data.put("warnings", JSONArray(warnings.map { it.toString() }))
        }
        report.metadata["source"]?.let { data.put("source", it.toString()) }
        putReportData(data, report)
      }
      else -> JSONObject()
        .let { data ->
          data.put("status", if (report.success) "ok" else "failed")
          data.put("message", message)
          putReportData(data, report)
        }
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

  private fun putReportData(
    json: JSONObject,
    report: ActionExecutionReport,
  ): JSONObject {
    report.metadata.forEach { (key, value) ->
      if (!json.has(key)) {
        json.put(key, toJsonValue(value))
      }
    }
    return json
  }

  private fun reportedFailureStatus(report: ActionExecutionReport): String =
    report.metadata["status"]?.toString()?.takeIf { it.isNotBlank() } ?: "failed"

  private fun toJsonValue(value: Any?): Any? =
    when (value) {
      null -> JSONObject.NULL
      is Map<*, *> -> toJsonObject(value)
      is List<*> -> toJsonArray(value)
      is Array<*> -> toJsonArray(value.toList())
      else -> value
    }

  private fun toJsonObject(map: Map<*, *>): JSONObject {
    val json = JSONObject()
    map.forEach { (key, value) ->
      if (key is String) {
        json.put(key, toJsonValue(value))
      }
    }
    return json
  }

  private fun toJsonArray(list: List<*>): JSONArray {
    val array = JSONArray()
    list.forEach { item -> array.put(toJsonValue(item)) }
    return array
  }
}
