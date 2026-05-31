package ai.opencray.app.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import ai.opencray.app.bridge.PythonSkillBridgeExecutor
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.data.repository.ChatRepository
import ai.opencray.app.data.repository.RuntimeRepository
import ai.opencray.app.domain.model.AgentTask
import ai.opencray.app.domain.model.AuditEntry
import ai.opencray.app.domain.model.PendingConflictResolution
import ai.opencray.app.domain.model.PlanningCard
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.execution.ActionExecutor
import ai.opencray.app.execution.ActionExecutionReport
import ai.opencray.app.feature.chat.AgentEvent
import ai.opencray.app.feature.chat.AgentEventOption
import ai.opencray.app.feature.chat.AgentEventType
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.gateway.AgentCoreHttpClient
import ai.opencray.app.gateway.AgentStreamEvent
import ai.opencray.app.gateway.DispatchSkillInvocation
import ai.opencray.app.gateway.GatewayConfig
import ai.opencray.app.gateway.PlanTaskData
import ai.opencray.app.gateway.PlannedSkillInvocation
import ai.opencray.app.memory.MemoryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import java.util.Collections
import java.util.UUID
import kotlin.math.min
import java.util.Locale

/**
 * Delegate that allows the runtime to request Android runtime permissions from an Activity.
 * Set this from MainActivity via [OpenCrayRuntime.setCalendarPermissionDelegate].
 */
fun interface CalendarPermissionDelegate {
  fun requestCalendarPermissions()
}

private data class LocalLlmConfig(
  val apiKey: String,
  val model: String,
  val baseUrl: String,
) {
  fun chatCompletionsEndpoint(): String {
    val normalizedBase = baseUrl.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
    return if (normalizedBase.endsWith("/chat/completions")) {
      normalizedBase
    } else {
      "$normalizedBase/chat/completions"
    }
  }
}

class OpenCrayRuntime(
  appContext: Context,
  private val runtimeRepository: RuntimeRepository,
  private val chatRepository: ChatRepository,
) {
  private val appContext: Context = appContext.applicationContext
  private val memoryManager = MemoryManager()
  private val actionExecutor = ActionExecutor(this.appContext)
  private val pythonSkillBridgeExecutor = PythonSkillBridgeExecutor(actionExecutor)
  private val gatewayClient = AgentCoreHttpClient()
  private val deviceId =
    Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
      ?.takeIf { it.isNotBlank() }
      ?.let { "android-$it" }
      ?: "android-${UUID.randomUUID().toString().take(12)}"

  @Volatile private var gatewayRegistered = false
  @Volatile private var dispatchLoopRunning = false
  @Volatile private var calendarPermissionDelegate: CalendarPermissionDelegate? = null
  private val serverSummaryTaskIds = Collections.synchronizedSet(mutableSetOf<String>())
  /** Stored callback invoked by [notifyCalendarPermissionGranted] to retry a deferred action. */
  @Volatile private var pendingPermissionCallback: (() -> Unit)? = null

  fun snapshot(): RuntimeSnapshot = runtimeRepository.getSnapshot()

  fun chatMessages(): List<ChatMessage> = chatRepository.getMessages()

  fun activeConversationId(): String = chatRepository.getActiveConversationId()

  fun createConversation(
    conversationId: String,
    initialMessages: List<ChatMessage>,
  ) {
    chatRepository.createConversation(conversationId, initialMessages)
  }

  fun selectConversation(conversationId: String): Boolean = chatRepository.selectConversation(conversationId)

  fun deleteConversation(conversationId: String): Boolean = chatRepository.deleteConversation(conversationId)

  fun deletePlanningCard(cardId: String) {
    val current = snapshot()
    runtimeRepository.replaceSnapshot(
      current.copy(
        planningCards = current.planningCards.filterNot { it.id == cardId },
        dismissedPlanningCardIds = current.dismissedPlanningCardIds + cardId,
        recentEvents = listOf("Planning card deleted: $cardId") + current.recentEvents,
      ),
    )
  }

  fun movePlanningCard(
    cardId: String,
    offset: Int,
  ) {
    if (offset == 0) return
    val current = snapshot()
    val cards = current.planningCards.toMutableList()
    val fromIndex = cards.indexOfFirst { it.id == cardId }
    if (fromIndex < 0) return
    val toIndex = (fromIndex + offset).coerceIn(0, cards.lastIndex)
    if (fromIndex == toIndex) return
    val item = cards.removeAt(fromIndex)
    cards.add(toIndex, item)
    runtimeRepository.replaceSnapshot(current.copy(planningCards = cards))
  }

  /**
   * Set a delegate so the runtime can request calendar permissions from the Activity context.
   * Call this from MainActivity.onCreate / onStart.
   */
  fun setCalendarPermissionDelegate(delegate: CalendarPermissionDelegate?) {
    calendarPermissionDelegate = delegate
  }

  /**
   * Call this from MainActivity.onRequestPermissionsResult when READ/WRITE_CALENDAR is granted.
   * Resumes any action that was deferred pending the permission grant.
   */
  fun notifyCalendarPermissionGranted() {
    val callback = pendingPermissionCallback
    pendingPermissionCallback = null
    if (callback != null) {
      thread(name = "opencray-permission-retry", isDaemon = true) { callback() }
    }
  }

  fun sendChatMessage(text: String) {
    chatRepository.sendMessage(text)
  }

  /**
   * Reserved skill invocation entry for UI shortcuts.
   * Current implementation invokes real skills if registered, otherwise records the request in chat.
   */
  fun invokeSkill(
    skillId: String,
    args: Map<String, String> = emptyMap(),
  ) {
    // Prefer the same plan/execute pipeline as chat so quick skills are genuinely usable.
    val quickGoal =
      when (skillId) {
        "get_campus_activities" -> "请获取最近校园活动并给出摘要"
        "read_notifications" -> "请读取当前未读通知并总结"
        "create_reminder" -> "请创建提醒事项"
        "create_calendar_event" -> "请创建一个日历事项并处理冲突"
        "set_alarm" -> "请设置一个闹钟提醒"
        "search" -> "请搜索相关信息并总结"
        else -> null
      }
    if (quickGoal != null) {
      if (planGoal(quickGoal)) {
        runActions()
      }
      return
    }

    val argLabel =
      if (args.isEmpty()) {
        "{}"
      } else {
        args.entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }
      }

    val action = SystemAction(
      id = skillId,
      title = "Manual Invocation: $skillId",
      summary = "Direct invocation from UI",
      riskLevel = "unknown",
      requiresApproval = false,
      params = args,
      payload = args,
      status = "approved"
    )

    val report = actionExecutor.execute(action, "UI Manual Invocation")
    val now = System.currentTimeMillis()
    val task =
      AgentTask(
        id = "manual_${UUID.randomUUID().toString().take(10)}",
        goal = "UI Manual Invocation",
        status = if (report.success) "completed" else "failed",
        attempt = 1,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        summary = "Manual skill invocation.",
      )
    upsertPlanningCard(actionPlanningCard(task, action, status = if (report.success) "executed" else "failed", result = report.message))

    if (report.semantic == "unsupported_action") {
      chatRepository.appendMessage(ChatRole.System, "找不到 skill 或未实现: $skillId")
    } else {
      val statusText = if (report.success) "成功" else "失败"
      chatRepository.appendMessage(
        ChatRole.System,
        "Skill 调用$statusText: $skillId $argLabel\n详细信息: ${report.message}"
      )
    }
  }

  fun boot() {
    runtimeRepository.markRuntimeBooted()
    runtimeRepository.appendEvent("Runtime booted. Agent pipeline ready.")
  }

  fun connectToGateway(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
  ) {
    runtimeRepository.updateConnectionConfig(host = host, port = port, tlsEnabled = tlsEnabled)
    runtimeRepository.updateConnectionStatus("Connecting to $host:$port ...")
    runtimeRepository.appendEvent("Gateway pairing started: $host:$port")

    thread(name = "opencray-gateway-register", isDaemon = true) {
      val result =
        gatewayClient.registerDevice(
          config = currentGatewayConfig(),
          userId = "android_user",
          deviceId = deviceId,
          capabilities = supportedGatewayCapabilities(),
        )
      if (result.success) {
        gatewayRegistered = true
        runtimeRepository.updateConnectionStatus("Connected to $host:$port")
        runtimeRepository.appendEvent("Gateway registered device_id=$deviceId")
        chatRepository.appendMessage(ChatRole.System, "Agent-Core 连接成功，后续将走服务端任务分发。")
      } else {
        gatewayRegistered = false
        runtimeRepository.updateConnectionStatus("Gateway connection failed")
        runtimeRepository.appendEvent("Gateway register failed: ${result.code} ${result.message}")
        chatRepository.appendMessage(
          ChatRole.System,
          "Agent-Core 连接失败（${result.code}），将继续使用本地链路。",
        )
      }
    }
  }

  fun setCapabilityEnabled(
    capabilityId: String,
    enabled: Boolean,
  ) {
    runtimeRepository.setCapabilityEnabled(capabilityId, enabled)
    val stateLabel = if (enabled) "enabled" else "disabled"
    runtimeRepository.appendEvent("Capability $capabilityId $stateLabel.")
  }

  fun executeAction(actionId: String) {
    val current = snapshot()
    val action = current.systemActions.firstOrNull { it.id == actionId }
    if (action == null) {
      runtimeRepository.appendEvent("Action $actionId is not available.")
      return
    }

    val reviewedStatus =
      current.safetyRecords.firstOrNull { it.actionId == action.id }?.status
        ?: if (action.requiresApproval) "Awaiting approval" else "Auto-approved"

    if (reviewedStatus == "Awaiting approval") {
      val blockedRecord =
        SafetyRecord(
          id = UUID.randomUUID().toString(),
          title = "Execution blocked",
          detail = "Action ${action.title} is awaiting approval.",
          status = "Blocked",
          actionId = action.id,
        )
      runtimeRepository.replaceSnapshot(
        current.copy(
          safetyRecords = listOf(blockedRecord) + current.safetyRecords,
          recentEvents = listOf("Blocked execution for ${action.id} due to approval gate.") + current.recentEvents,
        ),
      )
      return
    }

    runActions(actionIds = setOf(actionId))
  }

  fun approvePendingActions() {
    val current = snapshot()
    val updatedSafety =
      current.safetyRecords.map { record ->
        if (record.status == "Awaiting approval") {
          record.copy(status = "Approved")
        } else {
          record
        }
      }

    val updatedActions =
      current.systemActions.map { action ->
        val record = updatedSafety.firstOrNull { it.actionId == action.id }
        if (record?.status == "Approved" && action.status == "pending_approval") {
          action.copy(status = "approved")
        } else {
          action
        }
      }

    runtimeRepository.replaceSnapshot(
      current.copy(
        safetyRecords = updatedSafety,
        systemActions = updatedActions,
        recentEvents = listOf("Pending actions approved.") + current.recentEvents,
      ),
    )

    runActions()
  }

  fun submitAgentDecision(
    taskId: String,
    requestId: String,
    eventId: String,
    decision: String,
  ) {
    if (taskId.isBlank() || requestId.isBlank() || eventId.isBlank()) {
      chatRepository.appendMessage(ChatRole.Assistant, "这个确认项缺少执行上下文，我没法继续处理。")
      return
    }
    val normalizedDecision =
      when (decision.lowercase(Locale.getDefault())) {
        "approve", "approved", "allow", "allowed" -> "approve"
        "reject", "rejected", "deny", "denied" -> "reject"
        else -> decision
      }
    chatRepository.updateEventStatus(eventId, "submitting")
    thread(name = "opencray-gateway-decision", isDaemon = true) {
      val result =
        gatewayClient.submitDecision(
          config = currentGatewayConfig(),
          taskId = taskId,
          deviceId = deviceId,
          requestId = requestId,
          decision = normalizedDecision,
          userId = "android_user",
        )
      if (!result.success) {
        chatRepository.updateEventStatus(
          eventId,
          "failed",
          "确认没有提交成功：${result.code} ${result.message}",
        )
        return@thread
      }

      val acceptedStatus = if (normalizedDecision == "approve") "approved" else "rejected"
      chatRepository.updateEventStatus(eventId, acceptedStatus)
      if (normalizedDecision == "approve") {
        streamAssistant("已确认，我继续执行这一步。")
        runGatewayDispatchLoop()
      } else {
        streamAssistant("好的，我不会执行这一步。")
      }
    }
  }

  fun planGoal(goal: String): Boolean {
    val normalizedGoal = goal.trim()
    if (normalizedGoal.isEmpty()) return false

    return planGoalInternal(plannerGoal = normalizedGoal, displayGoal = normalizedGoal)
  }

  fun planGoalWithAttachment(
    goal: String,
    fileUri: String,
    fileName: String,
  ): Boolean {
    val normalizedGoal = goal.trim().ifEmpty { "请处理这个附件" }
    val normalizedFileUri = fileUri.trim()
    if (normalizedFileUri.isEmpty()) return planGoal(normalizedGoal)
    val normalizedFileName = fileName.trim()
    val displayName = normalizedFileName.ifBlank { "已选择文件" }
    val displayGoal = "$normalizedGoal\n\n附件：$displayName"
    val plannerGoal =
      buildString {
        append(normalizedGoal)
        append("\n\n[attached_file]\n")
        append("file_uri: ").append(normalizedFileUri).append('\n')
        if (normalizedFileName.isNotBlank()) {
          append("file_name: ").append(normalizedFileName).append('\n')
        }
        append(
          "instruction: The user selected this local Android file in chat. " +
            "When uploading or submitting homework, pass file_uri and file_name to " +
            "upload_homework_attachment or submit_homework.",
        )
      }
    return planGoalInternal(plannerGoal = plannerGoal, displayGoal = displayGoal)
  }

  private fun planGoalInternal(
    plannerGoal: String,
    displayGoal: String,
  ): Boolean {
    if (plannerGoal.isBlank() || displayGoal.isBlank()) return false

    chatRepository.sendMessage(displayGoal)

    if (maybeDeleteManualPlanningCard(displayGoal)) {
      return false
    }

    if (maybeCreateManualPlanningCard(displayGoal)) {
      streamAssistant("已创建规划卡片，放到规划页顶部了。你可以在那里上移、下移或删除它。")
      return false
    }

    if (gatewayRegistered) {
      handleStreamingRunViaGateway(plannerGoal)
      return false
    }

    if (!shouldPlanLocally(plannerGoal)) {
      handleLocalChat(plannerGoal)
      return false
    }

    streamAssistant(agentCoreUnavailableMessage())
    return false
  }

  private fun maybeDeleteManualPlanningCard(message: String): Boolean {
    val normalized = message.trim()
    val lower = normalized.lowercase(Locale.getDefault())
    val asksDelete =
      (normalized.contains("卡片") || lower.contains("plan card")) &&
        listOf("删除", "移除", "删掉", "取消", "remove", "delete").any { lower.contains(it.lowercase(Locale.getDefault())) }
    if (!asksDelete) return false

    val rawTarget =
      normalized
        .replace(Regex("^(请|帮我|给我)?\\s*(删除|移除|删掉|取消)\\s*"), "")
        .replace(Regex("(这张|这个|一个|一张)?\\s*(规划)?卡片"), "")
        .trim(' ', '：', ':', '，', ',', '。')
    val current = snapshot()
    val target =
      if (rawTarget.isBlank() || listOf("最新", "最后", "刚才").any { normalized.contains(it) }) {
        current.planningCards.maxByOrNull { it.updatedAtEpochMs }
      } else {
        current.planningCards.firstOrNull { card ->
          card.title.contains(rawTarget, ignoreCase = true) ||
            card.body.contains(rawTarget, ignoreCase = true)
        }
      }
    if (target == null) {
      streamAssistant("我没有找到匹配的规划卡片。你可以换个更具体的标题，或在规划页直接点删除。")
      return true
    }
    deletePlanningCard(target.id)
    streamAssistant("已删除规划卡片：${target.title}")
    return true
  }

  private fun maybeCreateManualPlanningCard(message: String): Boolean {
    val normalized = message.trim()
    val lower = normalized.lowercase(Locale.getDefault())
    val asksForCard =
      normalized.contains("卡片") &&
        listOf("创建", "新增", "生成", "添加", "加一个", "做一个").any { normalized.contains(it) }
    if (!asksForCard && !lower.contains("create plan card")) return false

    val content = extractManualPlanningCardContent(normalized).ifBlank { normalized }
    val now = System.currentTimeMillis()
    val title =
      content
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .take(28)
        .ifBlank { "手动规划卡片" }
    upsertPlanningCard(
      PlanningCard(
        id = "manual_${UUID.randomUUID().toString().take(10)}",
        title = title,
        body = content,
        type = inferPlanningCardType(normalized),
        source = "对话生成",
        status = "planned",
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
      ),
    )
    return true
  }

  private fun extractManualPlanningCardContent(text: String): String {
    val colonContent = text.substringAfter('：', "").ifBlank { text.substringAfter(':', "") }
    if (colonContent.isNotBlank()) return colonContent.trim()
    return text
      .replace(
        Regex("^(请|帮我|给我)?\\s*(创建|新增|生成|添加|加一个|做一个)\\s*(一张|一个)?\\s*(规划)?卡片[，,。\\s]*"),
        "",
      )
      .trim()
  }

  private fun handleLocalChat(message: String) {
    thread(name = "opencray-local-chat", isDaemon = true) {
      val reply = generateLocalLlmChatReply(message) ?: localLlmUnavailableMessage()
      streamAssistant(reply)
    }
  }

  private fun generateLocalLlmChatReply(message: String): String? {
    val config = localLlmConfig() ?: return null
    val endpoint = config.chatCompletionsEndpoint()
    val messages = JSONArray()
      .put(
        JSONObject()
          .put("role", "system")
          .put(
            "content",
            "你是 OpenTHU 移动端里的对话式校园助手。"
              + "用户现在只是在和你聊天，不要输出结构化执行记录，不要提到内部规则。"
              + "自然、简短、有上下文地回应；如果用户明确提出任务，再提醒他可以直接说目标。",
          ),
      )

    localChatHistoryForLlm().forEach { item ->
      messages.put(
        JSONObject()
          .put("role", item["role"].orEmpty())
          .put("content", item["text"].orEmpty()),
      )
    }
    messages.put(JSONObject().put("role", "user").put("content", message))

    val payload =
      JSONObject()
        .put("model", config.model)
        .put("messages", messages)
        .put("temperature", 0.8)
        .put("max_tokens", 500)

    return runCatching {
      val connection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 10_000
          readTimeout = 45_000
          useCaches = false
          doOutput = true
          setRequestProperty("Accept", "application/json")
          setRequestProperty("Content-Type", "application/json; charset=utf-8")
          setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
      try {
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
          writer.write(payload.toString())
        }
        val status = connection.responseCode
        val raw =
          (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (status !in 200..299) {
          runtimeRepository.appendEvent("Local LLM chat failed: HTTP_$status $raw")
          return@runCatching null
        }
        JSONObject(raw)
          .optJSONArray("choices")
          ?.optJSONObject(0)
          ?.optJSONObject("message")
          ?.optString("content", "")
          ?.trim()
          ?.takeIf { it.isNotBlank() }
      } finally {
        connection.disconnect()
      }
    }.getOrElse { throwable ->
      runtimeRepository.appendEvent("Local LLM chat failed: ${throwable.message ?: "unknown_error"}")
      null
    }
  }

  private fun handleStreamingRunViaGateway(message: String) {
    thread(name = "opencray-gateway-stream", isDaemon = true) {
      var assistantMessageId = ""
      val assistantText = StringBuilder()

      fun ensureAssistantMessage(): String {
        if (assistantMessageId.isBlank()) {
          assistantMessageId = chatRepository.appendMessage(ChatRole.Assistant, "…")
        }
        return assistantMessageId
      }

      fun updateAssistantText(nextText: String) {
        if (nextText.isBlank()) return
        val id = ensureAssistantMessage()
        assistantText.append(nextText)
        chatRepository.updateMessage(id, assistantText.toString())
      }

      val result =
        gatewayClient.streamAgentRun(
          config = currentGatewayConfig(),
          userId = "android_user",
          deviceId = deviceId,
          message = message,
          approveSensitive = false,
          session = buildGatewaySession(),
          history = buildGatewayChatHistory(),
        ) { event ->
          when (event.type) {
            "assistant_delta" -> updateAssistantText(event.content)
            "assistant_final" -> {
              val id = ensureAssistantMessage()
              if (event.taskId.isNotBlank()) {
                serverSummaryTaskIds.remove(event.taskId)
              }
              if (event.content.isNotBlank()) {
                assistantText.clear()
                assistantText.append(event.content)
                chatRepository.updateMessage(id, event.content)
              }
              chatRepository.appendEvent(id, event.toChatEvent())
            }
            "tool_call",
            "tool_result",
            "confirmation_required",
            "permission_required",
            "error",
            -> {
              val id = ensureAssistantMessage()
              chatRepository.appendEvent(id, event.toChatEvent())
              if (event.type == "tool_call" || event.type == "tool_result") {
                streamEventPlanningCard(event)?.let { upsertPlanningCard(it) }
              }
              if (event.type == "tool_call" && event.status == "queued") {
                if (event.taskId.isNotBlank()) {
                  serverSummaryTaskIds.add(event.taskId)
                }
                runGatewayDispatchLoop()
              }
            }
          }
        }

      if (!result.success) {
        runtimeRepository.appendEvent("Gateway stream failed: ${result.code} ${result.message}")
        if (assistantMessageId.isBlank()) {
          if (shouldPlanLocally(message)) {
            streamAssistant(agentCoreUnavailableMessage())
          } else {
            streamAssistant(generateLocalLlmChatReply(message) ?: localLlmUnavailableMessage())
          }
        } else {
          chatRepository.appendEvent(
            assistantMessageId,
            AgentEvent(
              id = UUID.randomUUID().toString(),
              type = AgentEventType.Error,
              title = "事件流中断",
              content = "${result.code} ${result.message}",
              status = "failed",
            ),
          )
        }
        return@thread
      }

      runGatewayDispatchLoop()
    }
  }

  private fun handleChatTurnViaGateway(message: String) {
    thread(name = "opencray-gateway-chat", isDaemon = true) {
      val result =
        gatewayClient.chatTurn(
          config = currentGatewayConfig(),
          userId = "android_user",
          deviceId = deviceId,
          message = message,
          session = buildGatewaySession(),
          history = buildGatewayChatHistory(),
        )
      if (!result.success || result.data == null) {
        runtimeRepository.appendEvent("Gateway chat failed: ${result.code} ${result.message}")
        if (shouldPlanLocally(message)) {
          streamAssistant(agentCoreUnavailableMessage())
        } else {
          streamAssistant(generateLocalLlmChatReply(message) ?: localLlmUnavailableMessage())
        }
        return@thread
      }

      val data = result.data
      val reply = data.reply.ifBlank {
        if (data.shouldPlan) "好的，我来处理。" else generateLocalLlmChatReply(message) ?: localLlmUnavailableMessage()
      }
      streamAssistant(reply)
      if (data.shouldPlan) {
        planGoalViaGateway(message)
      }
    }
  }

  private fun shouldPlanLocally(text: String): Boolean {
    val normalized = text.trim().lowercase(Locale.getDefault())
    if (normalized.isBlank()) return false
    val conversationOnlyMarkers = listOf(
      "hello",
      "hi",
      "hey",
      "你好",
      "嗨",
      "在吗",
      "who are you",
      "what are you",
      "你是谁",
      "你是做什么的",
      "你是干嘛的",
      "how are you",
      "你好吗",
      "最近怎么样",
      "你能做什么",
      "你能帮我做什么",
      "可以帮我做什么",
      "能干什么",
      "讲个笑话",
      "聊聊",
      "随便聊",
      "谢谢",
      "thank",
    )
    if (conversationOnlyMarkers.any { normalized == it || normalized.contains(it) } && normalized.length <= 24) {
      return false
    }
    val taskMarkers = listOf(
      "帮我",
      "请帮",
      "请你",
      "麻烦",
      "安排",
      "创建",
      "设置",
      "提醒",
      "闹钟",
      "日历",
      "校历",
      "课程",
      "课表",
      "作业",
      "ddl",
      "deadline",
      "活动",
      "讲座",
      "搜索",
      "查找",
      "查询",
      "通知栏",
      "未读通知",
      "系统通知",
      "读取通知",
      "打开",
      "删除",
      "总结",
      "search",
      "schedule",
      "remind",
      "alarm",
      "calendar",
      "open ",
      "create",
      "set ",
    )
    return taskMarkers.any { normalized.contains(it) }
  }

  private fun localLlmUnavailableMessage(): String =
    if (localLlmConfig() == null) {
      "模型没有配置好。请先在设置里填写模型 API Key、模型名称和 Base URL，然后我才能进行对话。"
    } else {
      "模型服务当前不可用。请检查 Base URL、模型名称、API Key 或网络连接后再试。"
    }

  private fun agentCoreUnavailableMessage(): String =
    "Agent-Core 没有连接好，无法执行任务。请先在设置里连接 Agent-Core；如果只是普通聊天，也需要先配置可用的模型服务。"

  private fun buildGatewayChatHistory(limit: Int = 12): List<Map<String, String>> =
    chatRepository.getMessages()
      .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
      .dropLast(1)
      .takeLast(limit)
      .map { message ->
        mapOf(
          "role" to when (message.role) {
            ChatRole.User -> "user"
            ChatRole.Assistant -> "assistant"
            ChatRole.System -> "system"
          },
          "text" to message.text,
        )
      }

  private fun localChatHistoryForLlm(limit: Int = 8): List<Map<String, String>> =
    chatRepository.getMessages()
      .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
      .dropLast(1)
      .takeLast(limit)
      .map { message ->
        mapOf(
          "role" to when (message.role) {
            ChatRole.User -> "user"
            ChatRole.Assistant -> "assistant"
            ChatRole.System -> "system"
          },
          "text" to message.text.take(1000),
        )
      }

  private fun localLlmConfig(): LocalLlmConfig? {
    val pref = appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
    val apiKey = pref.getString("openai_api_key", "").orEmpty().trim()
    if (apiKey.isBlank()) return null
    val model = pref.getString("llm_model", "gpt-4.1-mini").orEmpty().trim().ifBlank { "gpt-4.1-mini" }
    val baseUrl = pref.getString("llm_base_url", "").orEmpty().trim()
    return LocalLlmConfig(apiKey = apiKey, model = model, baseUrl = baseUrl)
  }

  private fun streamAssistant(
    text: String,
    chunkSize: Int = 12,
    intervalMs: Long = 35L,
  ) {
    if (text.isBlank()) return
    val messageId = chatRepository.appendMessage(ChatRole.Assistant, "…")
    if (messageId.isBlank()) return
    val builder = StringBuilder()
    var index = 0
    while (index < text.length) {
      val next = min(text.length, index + chunkSize)
      builder.append(text.substring(index, next))
      chatRepository.updateMessage(messageId, builder.toString())
      index = next
      if (index < text.length) {
        Thread.sleep(intervalMs)
      }
    }
  }

  fun runActions(actionIds: Set<String>? = null) {
    if (actionIds == null && gatewayRegistered) {
      runGatewayDispatchLoop()
      return
    }
    runActionsLocally(actionIds = actionIds, submitGatewayResult = gatewayRegistered)
  }

  fun clearChat() {
    chatRepository.clearMessages()
    runtimeRepository.appendEvent("Chat history cleared from prototype UI.")
  }

  private fun supportedGatewayCapabilities(): List<String> =
    listOf(
      "get_current_time",
      "set_alarm",
      "get_semesters",
      "get_courses",
      "get_course_schedule",
      "get_campus_activities",
      "search",
      "get_homework_cookie",
      "crawl_course_homeworks",
      "crawl_unsubmitted_homeworks",
      "preview_homework_attachments",
      "upload_homework_attachment",
      "submit_homework",
      "create_calendar_event",
      "detect_calendar_conflicts",
      "delete_calendar_event",
      "open_url",
      "read_notifications",
      "create_reminder",
      "show_summary",
      "send_notification",
    )

  private fun currentGatewayConfig(): GatewayConfig {
    val current = snapshot()
    return GatewayConfig(
      host = current.host,
      port = current.port,
      tlsEnabled = current.tlsEnabled,
    )
  }

  private fun buildGatewaySession(): Map<String, Any?> {
    val pref = appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
    val cookie = pref.getString("webvpn_cookie", "").orEmpty().trim()
    val csrf = pref.getString("webvpn_csrf", "").orEmpty().trim()
    val homeworkCookie = pref.getString("homework_cookie", "").orEmpty().trim()
    val homeworkCsrf = pref.getString("homework_csrf", "").orEmpty().trim()
    val learnBaseUrl = pref.getString("learn_base_url", "").orEmpty().trim()
    val openAiKey = pref.getString("openai_api_key", "").orEmpty().trim()
    val model = pref.getString("llm_model", "").orEmpty().trim()
    val baseUrl = pref.getString("llm_base_url", "").orEmpty().trim()
    val userId = pref.getString("user_id", "").orEmpty().trim()
    val campusFile = pref.getString("campus_file", "").orEmpty().trim()
    val searchProvider = pref.getString("search_provider", "").orEmpty().trim()
    val searchEndpoint = pref.getString("search_endpoint", "").orEmpty().trim()
    val searchApiKey = pref.getString("search_api_key", "").orEmpty().trim()
    val searchScene = pref.getString("search_scene", "").orEmpty().trim()
    val searchTtl = pref.getString("search_ttl", "").orEmpty().trim()
    val timezone = pref.getString("timezone", "").orEmpty().trim()

    val session = linkedMapOf<String, Any?>()
    if (cookie.isNotEmpty()) {
      session["cookie"] = cookie
      session["webvpn_cookie"] = cookie
      session["info_cookie"] = cookie
    }
    if (csrf.isNotEmpty()) {
      session["csrf"] = csrf
      session["csrf_token"] = csrf
    }
    if (homeworkCookie.isNotEmpty()) {
      session["homework_cookie"] = homeworkCookie
      session["learn_cookie"] = homeworkCookie
    }
    if (homeworkCsrf.isNotEmpty()) {
      session["homework_csrf"] = homeworkCsrf
      session["learn_csrf"] = homeworkCsrf
    }
    if (learnBaseUrl.isNotEmpty()) session["learn_base_url"] = learnBaseUrl
    if (openAiKey.isNotEmpty()) session["openai_api_key"] = openAiKey
    if (model.isNotEmpty()) session["llm_model"] = model
    if (baseUrl.isNotEmpty()) session["llm_base_url"] = baseUrl
    if (userId.isNotEmpty()) session["user_id"] = userId
    if (timezone.isNotEmpty()) session["timezone"] = timezone
    if (campusFile.isNotEmpty()) {
      session["campus_file"] = campusFile
      readSmallUtf8File(campusFile)?.let { session["campus_activities_json"] = it }
    }
    if (searchProvider.isNotEmpty()) session["search_provider"] = searchProvider
    if (searchEndpoint.isNotEmpty()) session["search_endpoint"] = searchEndpoint
    if (searchApiKey.isNotEmpty()) session["search_api_key"] = searchApiKey
    if (searchScene.isNotEmpty()) session["search_scene"] = searchScene
    if (searchTtl.isNotEmpty()) session["search_ttl"] = searchTtl
    return session
  }

  private fun readSmallUtf8File(path: String, maxBytes: Long = 512L * 1024L): String? {
    val file = File(path)
    if (!file.isFile || !file.canRead() || file.length() > maxBytes) return null
    return runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
  }

  private fun planGoalViaGateway(goal: String) {
    runtimeRepository.updateConnectionStatus("Planning on Agent-Core server ...")
    thread(name = "opencray-gateway-plan", isDaemon = true) {
      val result =
        gatewayClient.planTask(
          config = currentGatewayConfig(),
          userId = "android_user",
          deviceId = deviceId,
          goal = goal,
          approveSensitive = true,
          session = buildGatewaySession(),
          history = buildGatewayChatHistory(),
        )
      if (!result.success || result.data == null) {
        gatewayRegistered = false
        runtimeRepository.updateConnectionStatus("Gateway planning failed")
        val errorMsg = "${result.code} ${result.message}"
        runtimeRepository.appendEvent("Gateway plan failed: $errorMsg")
        streamAssistant("Agent-Core 规划失败，无法执行任务。请检查 Agent-Core、模型配置或网络连接后再试。\n\n原因：$errorMsg")
        return@thread
      }
      applyGatewayPlan(goal = goal, data = result.data)
      runGatewayDispatchLoop()
    }
  }

  private fun applyGatewayPlan(
    goal: String,
    data: PlanTaskData,
  ) {
    val now = System.currentTimeMillis()
    val current = snapshot()
    val approvedActions = data.approvedSkills.map { it.toSystemAction(status = "approved") }
    val blockedActions = data.blockedSkills.map { it.toSystemAction(status = "pending_approval") }
    val allActions = (approvedActions + blockedActions).ifEmpty { current.systemActions }
    val safetyRecords =
      buildList {
        addAll(approvedActions.map { it.toSafetyRecord(status = "Approved") })
        addAll(blockedActions.map { it.toSafetyRecord(status = "Awaiting approval") })
      }
    val taskStatus =
      when (data.taskStatus) {
        "ready_for_device_execution" -> "planned"
        "approval_required" -> "planned"
        else -> data.taskStatus
      }
    val task =
      AgentTask(
        id = if (data.taskId.isBlank()) UUID.randomUUID().toString() else data.taskId,
        goal = goal,
        status = taskStatus,
        attempt = 1,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        summary = "Gateway planned ${approvedActions.size} approved, ${blockedActions.size} blocked actions.",
      )
    val planningCards =
      allActions.fold(current.planningCards) { cards, action ->
        val card = actionPlanningCard(task, action, status = action.status)
        if (card.id in current.dismissedPlanningCardIds) {
          cards
        } else {
          upsertPlanningCardList(cards, card)
        }
      }
    val memory = memoryManager.updateFromGoal(current.memoryRecords, goal)
    val audit =
      allActions.map { action ->
        AuditEntry(
          id = UUID.randomUUID().toString(),
          taskId = task.id,
          actionId = action.id,
          stage = "plan_remote",
          message = "Gateway planned action ${action.id} (${action.status}).",
          timestampEpochMs = now,
        )
      }

    runtimeRepository.replaceSnapshot(
      current.copy(
        connectionStatus = "Server plan ready",
        systemActions = allActions,
        planningCards = planningCards,
        safetyRecords = safetyRecords + current.safetyRecords,
        tasks = listOf(task) + current.tasks.filterNot { it.id == task.id }.take(9),
        memoryRecords = memory,
        auditTrail = audit + current.auditTrail.take(99),
        recentEvents = listOf("Gateway planned task ${task.id}.") + current.recentEvents,
      ),
    )
    val blockedCount = blockedActions.size
    if (blockedCount > 0) {
      streamAssistant("我已经规划好了，当前有 ${approvedActions.size} 个动作可直接执行，另有 $blockedCount 个动作需要你确认。")
    } else {
      streamAssistant("我已经规划好了，开始执行。")
    }
  }

  private fun runGatewayDispatchLoop() {
    if (dispatchLoopRunning) {
      runtimeRepository.appendEvent("Gateway dispatch loop is already running.")
      return
    }
    dispatchLoopRunning = true
    runtimeRepository.updateConnectionStatus("Polling Agent-Core queue ...")

    thread(name = "opencray-gateway-dispatch", isDaemon = true) {
      try {
        var dispatchCount = 0
        var iteration = 0
        var stop = false
        while (iteration < 20 && !stop) {
          iteration += 1
          val next = gatewayClient.pullNext(config = currentGatewayConfig(), deviceId = deviceId)
          if (!next.success) {
            runtimeRepository.appendEvent("Gateway pull failed: ${next.code} ${next.message}")
            runtimeRepository.updateConnectionStatus("Gateway pull failed")
            stop = true
            continue
          }
          if (next.code == "NO_TASK" || next.data == null) {
            stop = true
            continue
          }

          val dispatch = next.data
          dispatchCount += 1
          executeDispatchedSkill(dispatch)
        }
        runtimeRepository.updateConnectionStatus("Gateway queue idle")
        runtimeRepository.appendEvent("Gateway dispatch loop completed, executed $dispatchCount action(s).")
      } finally {
        dispatchLoopRunning = false
      }
    }
  }

  private fun executeDispatchedSkill(dispatch: DispatchSkillInvocation) {
    val now = System.currentTimeMillis()
    val before = snapshot()
    val task =
      before.tasks.firstOrNull { it.id == dispatch.taskId }
        ?: AgentTask(
          id = dispatch.taskId.ifBlank { UUID.randomUUID().toString() },
          goal = "Server dispatched task",
          status = "in_progress",
          attempt = 1,
          createdAtEpochMs = now,
          updatedAtEpochMs = now,
          summary = "Task received from Agent-Core queue.",
        )
    val action = dispatch.toSystemAction(status = "approved")
    val card = actionPlanningCard(task, action, status = "running")
    val nextPlanningCards =
      if (card.id in before.dismissedPlanningCardIds) {
        before.planningCards
      } else {
        upsertPlanningCardList(before.planningCards, card)
      }

    runtimeRepository.replaceSnapshot(
      before.copy(
        tasks = listOf(task) + before.tasks.filterNot { it.id == task.id }.take(9),
        systemActions = upsertAction(before.systemActions, action),
        planningCards = nextPlanningCards,
        recentEvents = listOf("Dispatched ${action.id} (${dispatch.requestId}).") + before.recentEvents,
      ),
    )

    val goal = snapshot().tasks.firstOrNull { it.id == task.id }?.goal ?: "Server dispatched task"

    // If this skill needs calendar permission, request it and defer execution.
    if (requiresCalendarPermission(action) && !hasCalendarPermissions()) {
      val delegate = calendarPermissionDelegate
      if (delegate != null) {
        pendingPermissionCallback = { executeDispatchedSkill(dispatch) }
        runtimeRepository.appendEvent(
          "Calendar permission required for ${action.id}. Requesting from user."
        )
        delegate.requestCalendarPermissions()
      } else {
        applyExecutionReport(
          task = task,
          action = action,
          report = ActionExecutionReport(
            success = false,
            message = "Missing calendar permission. Please grant READ_CALENDAR and WRITE_CALENDAR.",
            recoverable = false,
          ),
          submitGatewayResult = true,
        )
      }
      return
    }

    val report =
      runCatching {
        actionExecutor.execute(action, goal)
      }.getOrElse { throwable ->
        runtimeRepository.appendEvent("Execution crashed for ${action.id}: ${throwable.message ?: throwable.javaClass.simpleName}")
        ActionExecutionReport(
          success = false,
          message = "端侧执行 ${action.id} 时发生异常：${throwable.message ?: throwable.javaClass.simpleName}",
          recoverable = true,
          semantic = "device_skill_execution_exception",
          metadata =
            mapOf(
              "reason" to "device_execution_exception",
              "exception" to (throwable.message ?: throwable.javaClass.simpleName),
            ),
        )
      }
    applyExecutionReport(task = task, action = action, report = report, submitGatewayResult = true)
  }

  private fun runActionsLocally(
    actionIds: Set<String>? = null,
    submitGatewayResult: Boolean,
  ) {
    val current = snapshot()
    val latestTask = current.tasks.firstOrNull()
    if (latestTask == null) {
      runtimeRepository.appendEvent("No active task to execute.")
      return
    }

    val targetActions =
      current.systemActions.filter { action ->
        val selected = actionIds?.contains(action.id) ?: true
        val runnable = action.status == "approved" || action.status == "planned"
        selected && runnable
      }

    if (targetActions.isEmpty()) {
      runtimeRepository.appendEvent("No executable actions found.")
      return
    }

    // Partition: defer calendar actions that still need runtime permission.
    val calendarDeferred = if (!hasCalendarPermissions()) {
      targetActions.filter { requiresCalendarPermission(it) }
    } else emptyList()

    if (calendarDeferred.isNotEmpty()) {
      val deferredIds = calendarDeferred.map { it.id }.toSet()
      val delegate = calendarPermissionDelegate
      if (delegate != null) {
        pendingPermissionCallback = {
          runActionsLocally(actionIds = deferredIds, submitGatewayResult = submitGatewayResult)
        }
        runtimeRepository.appendEvent(
          "Calendar permission required for $deferredIds. Requesting from user."
        )
        delegate.requestCalendarPermissions()
      } else {
        calendarDeferred.forEach { action ->
          applyExecutionReport(
            task = latestTask,
            action = action,
            report = ActionExecutionReport(
              success = false,
              message = "Missing calendar permission. Please grant READ_CALENDAR and WRITE_CALENDAR.",
              recoverable = false,
            ),
            submitGatewayResult = submitGatewayResult,
          )
        }
      }
    }

    val executableActions = targetActions - calendarDeferred.toSet()
    if (executableActions.isEmpty()) return

    executableActions.forEach { action ->
      val report = actionExecutor.execute(action, latestTask.goal)
      applyExecutionReport(task = latestTask, action = action, report = report, submitGatewayResult = submitGatewayResult)
    }

    val after = snapshot()
    val completed = after.systemActions.none { it.status == "approved" || it.status == "planned" }
    val updatedTasks =
      after.tasks.map { task ->
        if (task.id == latestTask.id) {
          task.copy(
            status = if (completed) "completed" else "in_progress",
            updatedAtEpochMs = System.currentTimeMillis(),
            summary =
              if (completed) {
                "Task executed with ${after.systemActions.count { it.status == "executed" }} successful actions."
              } else {
                "Task still has pending or approval-gated actions."
              },
          )
        } else {
          task
        }
      }
    runtimeRepository.replaceSnapshot(
      after.copy(
        connectionStatus = if (completed) "Task completed" else "Task in progress",
        tasks = updatedTasks,
      ),
    )

    streamAssistant(
      if (completed) {
        "任务已经完成。需要的话我可以继续帮你做下一步。"
      } else {
        "这一步已经处理了一部分，剩下的我会继续跟进。"
      },
    )
  }

  private fun applyExecutionReport(
    task: AgentTask,
    action: SystemAction,
    report: ActionExecutionReport,
    submitGatewayResult: Boolean,
  ) {
    val now = System.currentTimeMillis()
    // When the executor requires a conflict strategy choice, keep the action in a special
    // intermediate state so the user can pick a strategy without the server being notified yet.
    val needsConflictResolution = report.metadata["reason"] == "conflict_strategy_required"
    val nextStatus = when {
      report.success -> "executed"
      needsConflictResolution -> "conflict_pending"
      else -> "failed"
    }
    val current = snapshot()
    val updatedActions =
      current.systemActions.map { candidate ->
        if (isSameAction(candidate, action)) {
          candidate.copy(status = nextStatus, lastResult = report.message)
        } else {
          candidate
        }
      }
    val audit =
      AuditEntry(
        id = UUID.randomUUID().toString(),
        taskId = task.id,
        actionId = action.id,
        stage = "execute",
        message = report.message,
        timestampEpochMs = now,
      )
    val pendingConflict = if (needsConflictResolution) {
      PendingConflictResolution(
        action = action,
        taskId = task.id,
        conflictMessage = report.message,
      )
    } else {
      null
    }
    val card = actionPlanningCard(task, action, status = nextStatus, result = report.message)
    val planningCards =
      if (card.id in current.dismissedPlanningCardIds) {
        current.planningCards
      } else {
        upsertPlanningCardList(current.planningCards, card)
      }

    runtimeRepository.replaceSnapshot(
      current.copy(
        systemActions = updatedActions,
        planningCards = planningCards,
        pendingConflict = pendingConflict,
        auditTrail = listOf(audit) + current.auditTrail.take(149),
        recentEvents = listOf("Action ${action.id}: ${if (report.success) "success" else if (needsConflictResolution) "conflict_pending" else "failed"}") + current.recentEvents,
      ),
    )

    // For conflict resolution, we wait for the user to pick a strategy before submitting.
    if (submitGatewayResult && !action.requestId.isNullOrBlank() && !needsConflictResolution) {
      submitGatewayResultAsync(taskId = task.id, action = action, report = report)
    }

    val userFacingMessage =
      when {
        submitGatewayResult && serverSummaryTaskIds.contains(task.id) && !needsConflictResolution ->
          ""
        needsConflictResolution ->
          "我发现这个日程和现有安排有冲突。你可以选择：跳过创建、共存，或删除冲突事项后再创建。"
        report.success && action.id.substringBefore("#") == "show_summary" ->
          report.message
        report.success ->
          "已完成：${action.title}"
        report.message.contains("permission", ignoreCase = true) ->
          "这个操作需要系统权限。请先授权后我会继续。"
        else ->
          buildString {
            append("这个操作没有成功。")
            if (report.message.isNotBlank()) {
              append("\n原因：").append(report.message)
            }
            val reason = report.metadata["reason"]?.toString().orEmpty()
            when {
              reason == "login_required" || report.semantic.contains("login", ignoreCase = true) ->
                append("\n下一步：请到设置页重新完成清华统一登录后再试。")
              reason.contains("missing", ignoreCase = true) ->
                append("\n下一步：请补充缺少的信息后重新发起。")
              report.recoverable ->
                append("\n下一步：你可以调整参数或重新登录后再试。")
              else ->
                append("\n下一步：我已保留失败记录，你可以换一种说法重新执行。")
            }
          }
      }
    if (userFacingMessage.isNotBlank()) {
      streamAssistant(userFacingMessage)
    }
  }

  /**
   * Called from the UI when the user selects a conflict resolution strategy.
   * Re-executes the pending calendar action with the chosen [strategy]
   * (one of: skip_write, coexist, delete_conflicts).
   */
  fun resolveConflict(strategy: String) {
    val current = snapshot()
    val pending = current.pendingConflict ?: return

    // Clear the pending conflict immediately so the UI reverts.
    runtimeRepository.replaceSnapshot(current.copy(pendingConflict = null))

    // Re-execute with the chosen strategy injected into params.
    val resolvedAction = pending.action.copy(
      params = pending.action.params + mapOf("conflict_decision" to strategy),
      status = "approved",
      lastResult = null,
    )
    val task = snapshot().tasks.firstOrNull { it.id == pending.taskId }
      ?: AgentTask(
        id = pending.taskId,
        goal = "Conflict resolution",
        status = "in_progress",
        attempt = 1,
        createdAtEpochMs = System.currentTimeMillis(),
        updatedAtEpochMs = System.currentTimeMillis(),
        summary = "User resolved calendar conflict: $strategy",
      )
    runtimeRepository.appendEvent("Conflict resolved by user: strategy=$strategy for action=${resolvedAction.id}")

    val report = actionExecutor.execute(resolvedAction, task.goal)
    applyExecutionReport(
      task = task,
      action = resolvedAction,
      report = report,
      submitGatewayResult = !resolvedAction.requestId.isNullOrBlank(),
    )
  }

  private fun submitGatewayResultAsync(
    taskId: String,
    action: SystemAction,
    report: ActionExecutionReport,
  ) {
    val requestId = action.requestId ?: return
    thread(name = "opencray-gateway-submit", isDaemon = true) {
      val code = mapGatewayResultCode(action, report)
      val submitData: Map<String, Any?> = buildMap {
        put("status", if (report.success) "executed" else "failed")
        put("recoverable", report.recoverable)
        put("action_id", action.id)
        put("semantic", report.semantic)
        // Include all structured data from the executor (conflicts, event_ids, exceptions, etc.)
        report.metadata.forEach { (key, value) ->
          if (value != null) {
            put(key, value)
          }
        }
        put("metadata", report.metadata)
      }
      var result =
        gatewayClient.submitResult(
          config = currentGatewayConfig(),
          taskId = taskId,
          deviceId = deviceId,
          requestId = requestId,
          skillName = action.id,
          code = code,
          message = report.message,
          data = submitData,
        )
      var attempt = 1
      while (!result.success && attempt < 3) {
        attempt += 1
        Thread.sleep(800L * attempt)
        result =
          gatewayClient.submitResult(
            config = currentGatewayConfig(),
            taskId = taskId,
            deviceId = deviceId,
            requestId = requestId,
            skillName = action.id,
            code = code,
            message = report.message,
            data = submitData,
          )
      }
      val current = snapshot()
      if (result.success) {
        val taskStatus = result.data?.taskStatus.orEmpty()
        val updatedTasks =
          if (taskStatus.isBlank()) {
            current.tasks
          } else {
            current.tasks.map { task ->
              if (task.id == taskId) {
                task.copy(status = taskStatus, updatedAtEpochMs = System.currentTimeMillis())
              } else {
                task
              }
            }
          }
        runtimeRepository.replaceSnapshot(
          current.copy(
            tasks = updatedTasks,
            recentEvents = listOf("Result submitted for ${action.id} ($requestId).") + current.recentEvents,
          ),
        )
      } else {
        runtimeRepository.replaceSnapshot(
          current.copy(
            recentEvents = listOf("Result submit failed for ${action.id}: ${result.code} ${result.message}") + current.recentEvents,
          ),
        )
      }
    }
  }

  private fun mapGatewayResultCode(
    action: SystemAction,
    report: ActionExecutionReport,
  ): String {
    if (report.success) return "OK"

    val actionId = action.id.substringBefore("#")
    val message = report.message
    // Check structured data first for a precise reason code
    val reason = report.metadata["reason"] as? String
    if (reason == "conflict_strategy_required") return "APPROVAL_REQUIRED"
    if (reason == "allow_conflict_delete_not_set") return "APPROVAL_REQUIRED"
    if (reason == "confirm_submit_required") return "APPROVAL_REQUIRED"
    if (reason == "login_required" || report.semantic == "homework_cookie_login_required") return "NOT_CONFIGURED"

    if (message.contains("confirm_delete=true", ignoreCase = true) ||
      message.contains("confirm_submit=true", ignoreCase = true)
    ) {
      return "APPROVAL_REQUIRED"
    }
    if (actionId == "create_calendar_event" &&
      (message.contains("skip_write / coexist / delete_conflicts", ignoreCase = true) ||
        message.contains("请选择策略", ignoreCase = true))
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
      message.contains("requires explicit confirmation", ignoreCase = true) ||
      message.contains("需要明确授权", ignoreCase = true) ||
      message.contains("缺少", ignoreCase = true)
    ) {
      return "INVALID_PARAM"
    }
    if (message.contains("permission", ignoreCase = true) ||
      message.contains("权限", ignoreCase = true)
    ) {
      return "ACTION_NOT_ALLOWED"
    }
    return "SKILL_EXECUTION_FAILED"
  }

  private fun upsertAction(
    existing: List<SystemAction>,
    incoming: SystemAction,
  ): List<SystemAction> {
    val index =
      existing.indexOfFirst { candidate ->
        isSameAction(candidate, incoming)
      }
    return if (index >= 0) {
      existing.mapIndexed { idx, item -> if (idx == index) incoming else item }
    } else {
      listOf(incoming) + existing
    }
  }

  private fun upsertPlanningCard(card: PlanningCard) {
    val current = snapshot()
    if (card.id in current.dismissedPlanningCardIds) return
    runtimeRepository.replaceSnapshot(
      current.copy(
        planningCards = upsertPlanningCardList(current.planningCards, card),
        recentEvents = listOf("Planning card updated: ${card.title}") + current.recentEvents,
      ),
    )
  }

  private fun upsertPlanningCardList(
    existing: List<PlanningCard>,
    incoming: PlanningCard,
  ): List<PlanningCard> {
    val index = existing.indexOfFirst { it.id == incoming.id }
    return if (index >= 0) {
      existing.mapIndexed { idx, item ->
        if (idx == index) {
          incoming.copy(createdAtEpochMs = item.createdAtEpochMs)
        } else {
          item
        }
      }
    } else {
      listOf(incoming) + existing
    }
  }

  private fun actionPlanningCard(
    task: AgentTask,
    action: SystemAction,
    status: String = action.status,
    result: String? = null,
  ): PlanningCard {
    val now = System.currentTimeMillis()
    val skillName = action.id.substringBefore("#")
    val title = planningCardTitle(skillName, action.title)
    val body =
      buildString {
        append(action.summary.ifBlank { action.explain.ifBlank { action.title } })
        if (!result.isNullOrBlank()) {
          append("\n结果：")
          append(result.take(220))
        }
      }
    return PlanningCard(
      id = planningCardId(task.id, action.requestId, skillName),
      title = title,
      body = body,
      type = inferPlanningCardType(skillName + " " + action.title + " " + action.summary),
      source = "Skill 调用",
      status = status,
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
      actionId = action.id,
      taskId = task.id,
      metadata = mapOf("skill" to skillName),
    )
  }

  private fun streamEventPlanningCard(event: AgentStreamEvent): PlanningCard? {
    val skillName = event.skillName.ifBlank { return null }
    val now = System.currentTimeMillis()
    val title = planningCardTitle(skillName, event.title)
    return PlanningCard(
      id = planningCardId(event.taskId, event.requestId, skillName),
      title = title,
      body = event.content.ifBlank { "正在调用 $skillName。" }.take(360),
      type = inferPlanningCardType(skillName + " " + event.title + " " + event.content),
      source = "Agent-Core",
      status = if (event.type == "tool_result") event.status.ifBlank { "completed" } else event.status.ifBlank { "running" },
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
      actionId = skillName,
      taskId = event.taskId.ifBlank { null },
      metadata = mapOf("skill" to skillName),
    )
  }

  private fun planningCardId(
    taskId: String,
    requestId: String?,
    skillName: String,
  ): String {
    val taskPart = taskId.ifBlank { "local" }
    val requestPart = requestId.orEmpty().ifBlank { skillName }
    return "skill_${taskPart}_${requestPart}".replace(Regex("[^A-Za-z0-9_\\-]"), "_")
  }

  private fun planningCardTitle(
    skillName: String,
    fallback: String,
  ): String {
    val label =
      when (skillName) {
        "set_alarm" -> "闹钟提醒"
        "create_reminder" -> "待办提醒"
        "get_course_schedule" -> "课表拉取"
        "get_courses" -> "课程列表"
        "get_semesters" -> "学期信息"
        "get_academic_calendar" -> "校历信息"
        "crawl_course_homeworks" -> "作业列表"
        "crawl_unsubmitted_homeworks" -> "未交作业"
        "create_calendar_event" -> "日历事项"
        "read_notifications" -> "未读通知"
        "get_campus_activities" -> "校园活动"
        "search" -> "搜索结果"
        "show_summary" -> "结果总结"
        else -> fallback.ifBlank { skillName }
      }
    return label.take(32)
  }

  private fun inferPlanningCardType(text: String): String {
    val normalized = text.lowercase(Locale.getDefault())
    return when {
      normalized.contains("create_reminder") || normalized.contains("todo") || text.contains("待办") -> "todo"
      normalized.contains("alarm") || text.contains("闹钟") || text.contains("提醒") -> "alarm"
      normalized.contains("course") || normalized.contains("semester") || text.contains("课表") || text.contains("课程") -> "course"
      normalized.contains("homework") || text.contains("作业") -> "homework"
      normalized.contains("calendar") || text.contains("日历") || text.contains("校历") -> "calendar"
      normalized.contains("notification") || text.contains("通知") || text.contains("未读") -> "notification"
      normalized.contains("search") || text.contains("搜索") -> "search"
      else -> "plan"
    }
  }

  private fun isSameAction(
    left: SystemAction,
    right: SystemAction,
  ): Boolean {
    val leftReq = left.requestId
    val rightReq = right.requestId
    return if (!leftReq.isNullOrBlank() && !rightReq.isNullOrBlank()) {
      leftReq == rightReq
    } else {
      left.id == right.id
    }
  }

  private fun requiresCalendarPermission(action: SystemAction): Boolean =
    action.id.substringBefore("#") in setOf(
      "create_calendar_event",
      "detect_calendar_conflicts",
      "delete_calendar_event",
    )

  private fun hasCalendarPermissions(): Boolean {
    val read = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) ==
      PackageManager.PERMISSION_GRANTED
    val write = ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) ==
      PackageManager.PERMISSION_GRANTED
    return read && write
  }

  fun executeSkillInvocationFromPython(invocationJson: String): String {
    return pythonSkillBridgeExecutor.executeSkillInvocationJson(invocationJson)
  }

  fun processPythonBridgeFiles(
    requestFilePath: String,
    responseFilePath: String,
  ): Boolean {
    val requestFile = File(requestFilePath)
    if (!requestFile.exists() || !requestFile.isFile) return false

    val rawRequest = requestFile.readText(Charsets.UTF_8).trim()
    if (rawRequest.isEmpty()) return false

    val envelope = JSONObject(rawRequest)
    val invocation = envelope.optJSONObject("invocation") ?: return false
    val response = pythonSkillBridgeExecutor.executeSkillInvocation(invocation)

    val responseFile = File(responseFilePath)
    responseFile.parentFile?.mkdirs()
    responseFile.writeText(response.toString(), Charsets.UTF_8)
    return true
  }
}

private fun PlannedSkillInvocation.toSystemAction(status: String): SystemAction {
  val paramMap = args.entries.associate { it.key to (it.value?.toString() ?: "") }
  val title = if (description.isNotBlank()) description else skillName
  return SystemAction(
    id = skillName,
    requestId = requestId.ifBlank { null },
    title = title,
    summary = "Gateway planned skill: $skillName",
    riskLevel = riskLevel,
    requiresApproval = requiresApproval,
    params = paramMap,
    confidence = 86,
    explain = "Generated by Agent-Core server planning.",
    status = status,
    payload = args,
  )
}

private fun DispatchSkillInvocation.toSystemAction(status: String): SystemAction {
  val paramMap = args.entries.associate { it.key to (it.value?.toString() ?: "") }
  val title = if (description.isNotBlank()) description else skillName
  return SystemAction(
    id = skillName,
    requestId = requestId.ifBlank { null },
    title = title,
    summary = "Gateway dispatched skill: $skillName",
    riskLevel = riskLevel,
    requiresApproval = requiresApproval,
    params = paramMap,
    confidence = 90,
    explain = "Pulled from Agent-Core queue.",
    status = status,
    payload = args,
  )
}

private fun SystemAction.toSafetyRecord(status: String): SafetyRecord =
  SafetyRecord(
    id = UUID.randomUUID().toString(),
    title = "Safety review: $id",
    detail = "Risk=$riskLevel, requiresApproval=$requiresApproval, decision=$status",
    status = status,
    actionId = id,
  )

private fun AgentStreamEvent.toChatEvent(): AgentEvent =
  AgentEvent(
    id = eventId.ifBlank { UUID.randomUUID().toString() },
    type =
      when (type) {
        "assistant_delta" -> AgentEventType.AssistantDelta
        "assistant_final" -> AgentEventType.AssistantFinal
        "tool_call" -> AgentEventType.ToolCall
        "tool_result" -> AgentEventType.ToolResult
        "confirmation_required" -> AgentEventType.ConfirmationRequired
        "permission_required" -> AgentEventType.PermissionRequired
        "error" -> AgentEventType.Error
        else -> AgentEventType.Unknown
      },
    title = title,
    content = content,
    taskId = taskId,
    requestId = requestId,
    skillName = skillName,
    status = status,
    options =
      options.map {
        AgentEventOption(
          label = it["label"].orEmpty(),
          value = it["value"].orEmpty(),
        )
      },
  )
