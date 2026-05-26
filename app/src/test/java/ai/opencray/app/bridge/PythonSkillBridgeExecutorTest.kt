package ai.opencray.app.bridge

import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.execution.ActionExecutionReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonSkillBridgeExecutorTest {
  private class CapturingGateway(
    var nextReport: ActionExecutionReport,
  ) : ActionExecutionGateway {
    var lastAction: SystemAction? = null
    var lastGoal: String? = null

    override fun execute(action: SystemAction, goal: String): ActionExecutionReport {
      lastAction = action
      lastGoal = goal
      return nextReport
    }
  }

  @Test
  fun mapsInvocationJsonToSystemActionAndOkResult() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = true,
          message = "Calendar event created (event_id=evt_123, conflicts=1).",
          recoverable = false,
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_1")
        .put("skill_name", "create_calendar_event")
        .put("risk_level", "medium")
        .put("requires_approval", true)
        .put("description", "fallback description")
        .put(
          "args",
          JSONObject()
            .put("title", "AI midterm")
            .put("start_time", "2026-05-02T09:00:00Z")
            .put("end_time", "2026-05-02T11:00:00Z")
            .put("confirm_delete", false)
            .put("event_ids", JSONArray(listOf("100", "200"))),
        )

    val result = executor.executeSkillInvocation(invocation)
    val action = gateway.lastAction
    assertNotNull(action)
    assertEquals("create_calendar_event", action!!.id)
    assertEquals("medium", action.riskLevel)
    assertTrue(action.requiresApproval)
    assertEquals("AI midterm", action.params["title"])
    assertEquals("2026-05-02T09:00:00Z", action.params["start_time"])
    assertEquals("false", action.params["confirm_delete"])
    assertEquals("[\"100\",\"200\"]", action.params["event_ids"])
    assertEquals("AI midterm", gateway.lastGoal)

    assertEquals("req_1", result.getString("request_id"))
    assertEquals("OK", result.getString("code"))
    assertEquals("android_kotlin_bridge", result.getString("source"))
    val data = result.getJSONObject("data")
    assertEquals("created", data.getString("status"))
    assertEquals("evt_123", data.getString("event_id"))
    assertEquals(1, data.getInt("conflict_count"))
  }

  @Test
  fun usesDescriptionAsGoalWhenNoTitleAndParsesDetectResult() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = true,
          message = "Conflict check completed: 2 overlap(s). Android calendar overlap supported=true. Options: skip_write | coexist | delete_conflicts.",
          recoverable = false,
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_2")
        .put("skill_name", "detect_calendar_conflicts")
        .put("description", "detect for DDL candidate")
        .put(
          "args",
          JSONObject()
            .put("start_time", "2026-05-02T09:00:00Z")
            .put("end_time", "2026-05-02T11:00:00Z"),
        )

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("detect for DDL candidate", gateway.lastGoal)
    assertEquals("detect_calendar_conflicts", gateway.lastAction!!.id)
    assertEquals("OK", result.getString("code"))
    val data = result.getJSONObject("data")
    assertEquals("detected", data.getString("status"))
    assertEquals(2, data.getInt("conflict_count"))
    assertTrue(data.getBoolean("supports_overlap"))
  }

  @Test
  fun mapsDeleteConfirmationFailureToApprovalRequired() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = false,
          message = "Delete blocked: confirm_delete=true is required for high-risk deletion.",
          recoverable = false,
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_3")
        .put("skill_name", "delete_calendar_event")
        .put("args", JSONObject().put("event_id", "1000").put("confirm_delete", false))

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("APPROVAL_REQUIRED", result.getString("code"))
    val data = result.getJSONObject("data")
    assertEquals("awaiting_confirmation", data.getString("status"))
    assertTrue(data.getBoolean("high_risk"))
  }

  @Test
  fun mapsCreateConflictPromptToApprovalRequired() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = false,
          message = "Conflict detected (3). Choose skip_write / coexist / delete_conflicts.",
          recoverable = false,
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_4")
        .put("skill_name", "create_calendar_event")
        .put(
          "args",
          JSONObject()
            .put("title", "conflict candidate")
            .put("start_time", "2026-05-02T09:00:00Z")
            .put("end_time", "2026-05-02T11:00:00Z"),
        )

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("APPROVAL_REQUIRED", result.getString("code"))
    val data = result.getJSONObject("data")
    assertEquals("conflict_detected", data.getString("status"))
    assertEquals(3, data.getInt("conflict_count"))
  }

  @Test
  fun mapsMissingParamMessageToInvalidParam() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = false,
          message = "Missing READ_CALENDAR permission for conflict detection.",
          recoverable = false,
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_5")
        .put("skill_name", "detect_calendar_conflicts")
        .put("args", JSONObject())

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("INVALID_PARAM", result.getString("code"))
    assertEquals("failed", result.getJSONObject("data").getString("status"))
  }

  @Test
  fun mapsPermissionMessageToActionNotAllowed() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = false,
          message = "calendar permission denied by system",
          recoverable = false,
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_6")
        .put("skill_name", "create_calendar_event")
        .put(
          "args",
          JSONObject()
            .put("title", "perm check")
            .put("start_time", "2026-05-02T09:00:00Z")
            .put("end_time", "2026-05-02T11:00:00Z"),
        )

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("ACTION_NOT_ALLOWED", result.getString("code"))
    assertEquals("failed", result.getJSONObject("data").getString("status"))
    assertFalse(result.getJSONObject("data").optString("message").isBlank())
  }

  @Test
  fun mapsHomeworkSubmitConfirmRequirementToApprovalRequired() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = false,
          message = "Submit blocked: confirm_submit=true is required for high-risk homework submission.",
          recoverable = false,
          metadata = mapOf("reason" to "confirm_submit_required"),
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_hw_submit_1")
        .put("skill_name", "submit_homework")
        .put("args", JSONObject().put("homework_id", "hw_1").put("confirm_submit", false))

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("APPROVAL_REQUIRED", result.getString("code"))
    val data = result.getJSONObject("data")
    assertEquals("awaiting_confirmation", data.getString("status"))
    assertTrue(data.getBoolean("high_risk"))
    assertEquals("confirm_submit_required", data.getString("reason"))
  }

  @Test
  fun mapsHomeworkCrawlStructuredMetadataFromReport() {
    val homeworks =
      listOf(
        mapOf(
          "homework_id" to "hw_101",
          "title" to "Project 1",
          "submitted" to false,
        ),
      )
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = true,
          message = "Homework crawl completed: 1 item(s).",
          recoverable = false,
          metadata =
            mapOf(
              "status" to "crawled",
              "count" to 1,
              "homeworks" to homeworks,
              "course_ids" to listOf("2026spring-ai"),
            ),
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_hw_crawl_1")
        .put("skill_name", "crawl_course_homeworks")
        .put("args", JSONObject().put("course_ids", JSONArray(listOf("2026spring-ai"))))

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("OK", result.getString("code"))
    val data = result.getJSONObject("data")
    assertEquals("crawled", data.getString("status"))
    assertEquals(1, data.getInt("count"))
    assertEquals(1, data.getJSONArray("homeworks").length())
    assertEquals("hw_101", data.getJSONArray("homeworks").getJSONObject(0).getString("homework_id"))
    assertEquals("2026spring-ai", data.getJSONArray("course_ids").getString(0))
  }

  @Test
  fun mapsGetHomeworkCookieResultToCookieReady() {
    val gateway =
      CapturingGateway(
        ActionExecutionReport(
          success = true,
          message = "Homework cookie loaded from provided Learn cookie.",
          recoverable = false,
          metadata =
            mapOf(
              "status" to "cookie_ready",
              "cookie_source" to "provided_cookie",
              "has_csrf" to true,
            ),
        ),
      )
    val executor = PythonSkillBridgeExecutor(gateway)
    val invocation =
      JSONObject()
        .put("request_id", "req_cookie_1")
        .put("skill_name", "get_homework_cookie")
        .put("args", JSONObject().put("cookies", "JSESSIONID=abc; XSRF-TOKEN=xyz"))

    val result = executor.executeSkillInvocation(invocation)
    assertEquals("OK", result.getString("code"))
    val data = result.getJSONObject("data")
    assertEquals("cookie_ready", data.getString("status"))
    assertEquals("provided_cookie", data.getString("cookie_source"))
    assertTrue(data.getBoolean("has_csrf"))
  }
}
