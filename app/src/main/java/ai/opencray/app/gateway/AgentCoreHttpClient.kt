package ai.opencray.app.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class GatewayConfig(
  val host: String,
  val port: Int,
  val tlsEnabled: Boolean,
)

data class GatewayResult<T>(
  val success: Boolean,
  val code: String,
  val message: String,
  val data: T? = null,
)

data class PlannedSkillInvocation(
  val taskId: String,
  val requestId: String,
  val skillName: String,
  val args: Map<String, Any?>,
  val riskLevel: String,
  val requiresApproval: Boolean,
  val description: String,
  val status: String,
)

data class PlanTaskData(
  val taskId: String,
  val taskStatus: String,
  val approvedSkills: List<PlannedSkillInvocation>,
  val blockedSkills: List<PlannedSkillInvocation>,
)

data class DispatchSkillInvocation(
  val taskId: String,
  val requestId: String,
  val skillName: String,
  val args: Map<String, Any?>,
  val riskLevel: String,
  val requiresApproval: Boolean,
  val description: String,
  val status: String,
)

data class SubmitResultData(
  val taskId: String,
  val taskStatus: String,
  val receivedResultCount: Int,
)

data class ChatTurnData(
  val shouldPlan: Boolean,
  val reply: String,
  val mode: String,
  val confidence: Double,
  val source: String,
)

class AgentCoreHttpClient {
  fun registerDevice(
    config: GatewayConfig,
    userId: String,
    deviceId: String,
    capabilities: List<String>,
    appVersion: String = "0.1.0",
  ): GatewayResult<Unit> {
    val payload =
      JSONObject()
        .put("device_id", deviceId)
        .put("user_id", userId)
        .put("platform", "android")
        .put("app_version", appVersion)
        .put("capabilities", JSONArray(capabilities))

    val result = requestJson(config, "POST", "/api/v1/devices/register", payload)
    return if (result.success) {
      GatewayResult(success = true, code = result.code, message = result.message, data = Unit)
    } else {
      GatewayResult(success = false, code = result.code, message = result.message, data = null)
    }
  }

  fun planTask(
    config: GatewayConfig,
    userId: String,
    deviceId: String,
    goal: String,
    approveSensitive: Boolean = true,
    session: Map<String, Any?> = emptyMap(),
  ): GatewayResult<PlanTaskData> {
    val payload =
      JSONObject()
        .put("device_id", deviceId)
        .put("user_id", userId)
        .put("goal", goal)
        .put("approve_sensitive", approveSensitive)
        .put("session", session.toJsonObject())

    val result = requestJson(config, "POST", "/api/v1/agent/tasks/plan", payload)
    if (!result.success || result.data == null) {
      return GatewayResult(success = false, code = result.code, message = result.message, data = null)
    }

    val rootData = result.data.optJSONObject("data")
    if (rootData == null) {
      return GatewayResult(success = false, code = "MALFORMED_RESPONSE", message = "Missing data in plan response", data = null)
    }

    val taskId = rootData.optString("task_id", "")
    val taskStatus = rootData.optString("task_status", "planned")
    val planOnlyData =
      rootData
        .optJSONObject("plan_only_response")
        ?.optJSONObject("data")

    val approved =
      parseSkillInvocations(
        planOnlyData?.optJSONArray("approved_skills"),
        fallbackTaskId = taskId,
      )
    val blocked =
      parseSkillInvocations(
        planOnlyData?.optJSONArray("blocked_skills"),
        fallbackTaskId = taskId,
      )

    return GatewayResult(
      success = true,
      code = result.code,
      message = result.message,
      data =
        PlanTaskData(
          taskId = taskId,
          taskStatus = taskStatus,
          approvedSkills = approved,
          blockedSkills = blocked,
        ),
    )
  }

  fun chatTurn(
    config: GatewayConfig,
    userId: String,
    deviceId: String,
    message: String,
    session: Map<String, Any?> = emptyMap(),
    history: List<Map<String, String>> = emptyList(),
  ): GatewayResult<ChatTurnData> {
    val payload =
      JSONObject()
        .put("device_id", deviceId)
        .put("user_id", userId)
        .put("message", message)
        .put("session", session.toJsonObject())
        .put("history", history.map { it.toJsonObject() }.toJsonArray())

    val result = requestJson(config, "POST", "/api/v1/agent/chat", payload)
    if (!result.success || result.data == null) {
      return GatewayResult(success = false, code = result.code, message = result.message, data = null)
    }

    val rootData = result.data.optJSONObject("data")
      ?: return GatewayResult(success = false, code = "MALFORMED_RESPONSE", message = "Missing data in chat response", data = null)

    return GatewayResult(
      success = true,
      code = result.code,
      message = result.message,
      data =
        ChatTurnData(
          shouldPlan = rootData.optBoolean("should_plan", false),
          reply = rootData.optString("reply", ""),
          mode = rootData.optString("mode", "chat"),
          confidence = rootData.optDouble("confidence", 0.0),
          source = rootData.optString("source", ""),
        ),
    )
  }

  fun pullNext(
    config: GatewayConfig,
    deviceId: String,
  ): GatewayResult<DispatchSkillInvocation?> {
    val result = requestJson(config, "GET", "/api/v1/agent/tasks/next?device_id=$deviceId", null)
    if (!result.success || result.data == null) {
      return GatewayResult(success = false, code = result.code, message = result.message, data = null)
    }
    if (result.code == "NO_TASK") {
      return GatewayResult(success = true, code = "NO_TASK", message = result.message, data = null)
    }

    val rootData = result.data.optJSONObject("data")
    val invocation = rootData?.optJSONObject("skill_invocation")
    if (rootData == null || invocation == null) {
      return GatewayResult(success = false, code = "MALFORMED_RESPONSE", message = "Missing skill invocation in next-task response", data = null)
    }

    val args = invocation.optJSONObject("args")?.toMap() ?: emptyMap()
    return GatewayResult(
      success = true,
      code = result.code,
      message = result.message,
      data =
        DispatchSkillInvocation(
          taskId = rootData.optString("task_id", ""),
          requestId = rootData.optString("request_id", ""),
          skillName = invocation.optString("skill_name", ""),
          args = args,
          riskLevel = invocation.optString("risk_level", "low"),
          requiresApproval = invocation.optBoolean("requires_approval", false),
          description = invocation.optString("description", ""),
          status = invocation.optString("status", "approved"),
        ),
    )
  }

  fun submitResult(
    config: GatewayConfig,
    taskId: String,
    deviceId: String,
    requestId: String,
    skillName: String,
    code: String,
    message: String,
    data: Map<String, Any?>,
  ): GatewayResult<SubmitResultData> {
    val payload =
      JSONObject()
        .put("device_id", deviceId)
        .put("request_id", requestId)
        .put("skill_name", skillName)
        .put("code", code)
        .put("message", message)
        .put("data", data.toJsonObject())
        .put("source", "android_app")
        .put("from_cache", false)

    val result = requestJson(config, "POST", "/api/v1/agent/tasks/$taskId/result", payload)
    if (!result.success || result.data == null) {
      return GatewayResult(success = false, code = result.code, message = result.message, data = null)
    }

    val rootData = result.data.optJSONObject("data")
    if (rootData == null) {
      return GatewayResult(success = false, code = "MALFORMED_RESPONSE", message = "Missing result-ack data", data = null)
    }

    return GatewayResult(
      success = true,
      code = result.code,
      message = result.message,
      data =
        SubmitResultData(
          taskId = rootData.optString("task_id", taskId),
          taskStatus = rootData.optString("task_status", ""),
          receivedResultCount = rootData.optInt("received_result_count", 0),
        ),
    )
  }

  private fun parseSkillInvocations(
    array: JSONArray?,
    fallbackTaskId: String,
  ): List<PlannedSkillInvocation> {
    if (array == null) return emptyList()
    val result = mutableListOf<PlannedSkillInvocation>()
    for (i in 0 until array.length()) {
      val item = array.optJSONObject(i) ?: continue
      result +=
        PlannedSkillInvocation(
          taskId = item.optString("task_id", fallbackTaskId),
          requestId = item.optString("request_id", ""),
          skillName = item.optString("skill_name", ""),
          args = item.optJSONObject("args")?.toMap() ?: emptyMap(),
          riskLevel = item.optString("risk_level", "low"),
          requiresApproval = item.optBoolean("requires_approval", false),
          description = item.optString("description", ""),
          status = item.optString("status", "planned"),
        )
    }
    return result
  }

  private fun requestJson(
    config: GatewayConfig,
    method: String,
    path: String,
    payload: JSONObject?,
  ): GatewayResult<JSONObject> {
    val protocol = if (config.tlsEnabled) "https" else "http"
    val url = URL("$protocol://${config.host}:${config.port}$path")
    val connection = (url.openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = 10_000
      readTimeout = 60_000
      useCaches = false
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Connection", "close")
      if (payload != null) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
      }
    }

    return runCatching {
      if (payload != null) {
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
          writer.write(payload.toString())
        }
      }

      val status = connection.responseCode
      val raw =
        (if (status in 200..299) connection.inputStream else connection.errorStream)
          ?.bufferedReader(StandardCharsets.UTF_8)
          ?.use(BufferedReader::readText)
          .orEmpty()

      val parsed = runCatching { JSONObject(raw) }.getOrNull()
      val code = parsed?.optString("code", if (status in 200..299) "OK" else "HTTP_$status") ?: if (status in 200..299) "OK" else "HTTP_$status"
      val message = parsed?.optString("message", "").orEmpty().ifEmpty { if (status in 200..299) "ok" else "http_error_$status" }
      val success = status in 200..299

      GatewayResult(
        success = success,
        code = code,
        message = message,
        data = parsed ?: JSONObject(),
      )
    }.getOrElse { throwable ->
      GatewayResult(
        success = false,
        code = "NETWORK_ERROR",
        message = throwable.message ?: "network_error",
        data = JSONObject(),
      )
    }.also {
      connection.disconnect()
    }
  }
}

private fun JSONObject.toMap(): Map<String, Any?> {
  val map = linkedMapOf<String, Any?>()
  val keys = keys()
  while (keys.hasNext()) {
    val key = keys.next()
    map[key] = toAny(opt(key))
  }
  return map
}

private fun JSONArray.toList(): List<Any?> {
  val list = mutableListOf<Any?>()
  for (i in 0 until length()) {
    list += toAny(opt(i))
  }
  return list
}

private fun toAny(value: Any?): Any? =
  when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> value.toList()
    else -> value
  }

private fun Map<String, Any?>.toJsonObject(): JSONObject {
  val json = JSONObject()
  forEach { (key, value) ->
    json.put(key, value.toJsonValue())
  }
  return json
}

private fun List<Any?>.toJsonArray(): JSONArray {
  val array = JSONArray()
  forEach { item -> array.put(item.toJsonValue()) }
  return array
}

private fun Any?.toJsonValue(): Any? =
  when (this) {
    null -> JSONObject.NULL
    is Map<*, *> ->
      entries
        .filter { it.key is String }
        .associate { it.key as String to it.value }
        .toJsonObject()
    is List<*> -> filterNotNull().toJsonArray()
    else -> this
  }
