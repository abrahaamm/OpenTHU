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
import ai.opencray.app.domain.model.MemoryRecord
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
import ai.opencray.app.safety.PolicyEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import kotlin.concurrent.thread
import java.util.Collections
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
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

private data class PendingGatewayResult(
  val taskId: String,
  val requestId: String,
  val skillName: String,
  val code: String,
  val message: String,
  val dataJson: String,
  val enqueuedAtEpochMs: Long,
  val attempts: Int = 0,
)

class OpenCrayRuntime(
  appContext: Context,
  private val runtimeRepository: RuntimeRepository,
  private val chatRepository: ChatRepository,
) {
  private val appContext: Context = appContext.applicationContext
  private val memoryManager = MemoryManager()
  private val policyEngine = PolicyEngine()
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
  @Volatile private var gatewayHeartbeatRunning = false
  @Volatile private var pendingGatewayResultFlushRunning = false
  @Volatile private var appInForeground = true
  @Volatile private var lastUserActivityEpochMs = System.currentTimeMillis()
  @Volatile private var calendarPermissionDelegate: CalendarPermissionDelegate? = null
  private val serverSummaryTaskIds = Collections.synchronizedSet(mutableSetOf<String>())
  private val pendingGatewayResults = Collections.synchronizedList(mutableListOf<PendingGatewayResult>())
  /** Stored callback invoked by [notifyCalendarPermissionGranted] to retry a deferred action. */
  @Volatile private var pendingPermissionCallback: (() -> Unit)? = null

  init {
    pendingGatewayResults.addAll(loadPendingGatewayResultQueue())
  }

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

  fun addManualPreference(preference: String): Boolean {
    val normalized = preference.trim()
    if (normalized.isEmpty()) return false
    val current = snapshot()
    runtimeRepository.replaceSnapshot(
      current.copy(
        memoryRecords = memoryManager.addManualPreference(current.memoryRecords, normalized),
        recentEvents = listOf("Manual preference saved.") + current.recentEvents,
      ),
    )
    return true
  }

  fun deleteLongPreference(index: Int): Boolean {
    val current = snapshot()
    val (memoryRecords, removed) = memoryManager.removeLongPreferenceAt(current.memoryRecords, index)
    if (removed == null) return false
    runtimeRepository.replaceSnapshot(
      current.copy(
        memoryRecords = memoryRecords,
        recentEvents = listOf("Preference deleted: ${removed.value.take(32)}") + current.recentEvents,
      ),
    )
    return true
  }

  fun updateLongPreference(
    index: Int,
    preference: String,
  ): Boolean {
    val current = snapshot()
    val (memoryRecords, updated) = memoryManager.updateLongPreferenceAt(current.memoryRecords, index, preference)
    if (updated == null) return false
    runtimeRepository.replaceSnapshot(
      current.copy(
        memoryRecords = memoryRecords,
        recentEvents = listOf("Preference updated: ${updated.value.take(32)}") + current.recentEvents,
      ),
    )
    return true
  }

  fun clearAllMemory(): Boolean {
    val current = snapshot()
    if (current.memoryRecords.isEmpty()) return false
    runtimeRepository.replaceSnapshot(
      current.copy(
        memoryRecords = emptyList(),
        recentEvents = listOf("All runtime memory cleared.") + current.recentEvents,
      ),
    )
    return true
  }

  fun snoozeAction(actionId: String): Boolean =
    markActionFeedback(actionId = actionId, status = "snoozed", feedback = "snooze", result = "已标记为稍后处理。")

  fun ignoreAction(actionId: String): Boolean =
    markActionFeedback(actionId = actionId, status = "ignored", feedback = "ignore", result = "已忽略该建议。")

  private fun markActionFeedback(
    actionId: String,
    status: String,
    feedback: String,
    result: String,
  ): Boolean {
    val current = snapshot()
    val action = current.systemActions.firstOrNull { it.id == actionId } ?: return false
    val now = System.currentTimeMillis()
    val taskId = current.tasks.firstOrNull()?.id ?: "local_feedback"
    val updatedAction = action.copy(status = status, lastResult = result)
    val updatedActions =
      current.systemActions.map { candidate ->
        if (candidate.id == action.id) updatedAction else candidate
      }
    val updatedCards =
      current.planningCards.map { card ->
        if (card.actionId == action.id) {
          card.copy(
            status = status,
            updatedAtEpochMs = now,
          )
        } else {
          card
        }
      }
    val audit =
      AuditEntry(
        id = UUID.randomUUID().toString(),
        taskId = taskId,
        actionId = action.id,
        stage = "feedback",
        message = result,
        timestampEpochMs = now,
      )

    runtimeRepository.replaceSnapshot(
      current.copy(
        systemActions = updatedActions,
        planningCards = updatedCards,
        memoryRecords = memoryManager.recordActionFeedback(current.memoryRecords, action, feedback),
        auditTrail = listOf(audit) + current.auditTrail.take(149),
        recentEvents = listOf("Action ${action.id} marked $status.") + current.recentEvents,
      ),
    )
    return true
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

  fun setAppInForeground(foreground: Boolean) {
    appInForeground = foreground
    if (foreground) markUserActivity()
  }

  fun markUserActivity() {
    lastUserActivityEpochMs = System.currentTimeMillis()
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
    markUserActivity()
    // Prefer the same plan/execute pipeline as chat so quick skills are genuinely usable.
    val quickGoal =
      when (skillId) {
        "get_assignments" -> "请获取当前课程作业和 DDL"
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
    runtimeRepository.updateConnectionStatus("连接到服务器...")
    runtimeRepository.appendEvent("Gateway pairing started: $host:$port")

    thread(name = "opencray-gateway-register", isDaemon = true) {
      var lastResultCode = "NETWORK_ERROR"
      var lastMessage = ""
      for (attempt in 1..3) {
        runtimeRepository.updateConnectionStatus("连接到服务器... 第 $attempt/3 次")
        val result =
          gatewayClient.registerDevice(
            config = currentGatewayConfig(),
            userId = "android_user",
            deviceId = deviceId,
            capabilities = supportedGatewayCapabilities(),
          )
        if (result.success) {
          gatewayRegistered = true
          runtimeRepository.updateConnectionStatus("已连接服务器")
          runtimeRepository.appendEvent("Gateway registered device_id=$deviceId")
          chatRepository.appendMessage(ChatRole.System, "Agent-Core 连接成功，后续将走服务端任务分发。")
          startGatewayHeartbeatLoop()
          flushPendingGatewayResultsAsync("gateway_registered")
          return@thread
        }
        lastResultCode = result.code
        lastMessage = result.message
        runtimeRepository.appendEvent("Gateway register attempt $attempt failed: ${result.code} ${result.message}")
        Thread.sleep(700L * attempt)
      }

      gatewayRegistered = false
      runtimeRepository.updateConnectionStatus("服务器未连接")
      runtimeRepository.appendEvent("Gateway register failed after retries: $lastResultCode $lastMessage")
      chatRepository.appendMessage(
        ChatRole.System,
        "Agent-Core 连接失败（$lastResultCode），将继续使用本地链路。点击顶部连接状态可重试。",
      )
    }
  }

  fun applyGatewayConfig(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
    reconnectIfRegistered: Boolean = false,
  ) {
    val normalizedHost = host.trim().ifBlank { "10.0.2.2" }
    val normalizedPort = port.takeIf { it in 1..65535 } ?: 18789
    runtimeRepository.updateConnectionConfig(
      host = normalizedHost,
      port = normalizedPort,
      tlsEnabled = tlsEnabled,
    )
    if (reconnectIfRegistered && gatewayRegistered) {
      reconnectGateway()
    }
  }

  private fun startGatewayHeartbeatLoop() {
    if (gatewayHeartbeatRunning) return
    gatewayHeartbeatRunning = true
    thread(name = "opencray-gateway-heartbeat", isDaemon = true) {
      try {
        while (gatewayRegistered) {
          Thread.sleep(gatewayHeartbeatIntervalMs())
          val result = gatewayClient.healthCheck(currentGatewayConfig())
          if (result.success) {
            runtimeRepository.updateConnectionStatus("已连接服务器")
            flushPendingGatewayResultsAsync("heartbeat")
          } else {
            gatewayRegistered = false
            gatewayHeartbeatRunning = false
            runtimeRepository.updateConnectionStatus("连接到服务器...")
            runtimeRepository.appendEvent("Gateway heartbeat failed: ${result.code} ${result.message}")
            connectToGateway(
              host = snapshot().host,
              port = snapshot().port,
              tlsEnabled = snapshot().tlsEnabled,
            )
            return@thread
          }
        }
      } finally {
        gatewayHeartbeatRunning = false
      }
    }
  }

  private fun gatewayHeartbeatIntervalMs(): Long {
    if (!appInForeground) return GATEWAY_HEARTBEAT_BACKGROUND_MS
    val activeRecently = System.currentTimeMillis() - lastUserActivityEpochMs <= GATEWAY_ACTIVE_WINDOW_MS
    return if (activeRecently) GATEWAY_HEARTBEAT_ACTIVE_MS else GATEWAY_HEARTBEAT_IDLE_MS
  }

  fun reconnectGateway() {
    markUserActivity()
    val current = snapshot()
    connectToGateway(
      host = current.host.ifBlank { "10.0.2.2" },
      port = current.port.takeIf { it > 0 } ?: 18789,
      tlsEnabled = current.tlsEnabled,
    )
  }

  fun updateGatewayTls(enabled: Boolean) {
    val current = snapshot()
    runtimeRepository.updateConnectionConfig(
      host = current.host.ifBlank { "10.0.2.2" },
      port = current.port.takeIf { it > 0 } ?: 18789,
      tlsEnabled = enabled,
    )
    if (gatewayRegistered) {
      reconnectGateway()
    }
  }

  fun updateConfiguredModel(model: String) {
    val normalized = model.trim().ifBlank { "gpt-4.1-mini" }
    appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
      .edit()
      .putString("llm_model", normalized)
      .apply()
  }

  fun configuredModel(): String =
    appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
      .getString("llm_model", "gpt-4.1-mini")
      .orEmpty()
      .ifBlank { "gpt-4.1-mini" }

  fun setCapabilityEnabled(
    capabilityId: String,
    enabled: Boolean,
  ) {
    runtimeRepository.setCapabilityEnabled(capabilityId, enabled)
    val stateLabel = if (enabled) "enabled" else "disabled"
    runtimeRepository.appendEvent("Capability $capabilityId $stateLabel.")
  }

  fun executeAction(actionId: String) {
    markUserActivity()
    val current = snapshot()
    val action = current.systemActions.firstOrNull { it.id == actionId }
    if (action == null) {
      runtimeRepository.appendEvent("Action $actionId is not available.")
      return
    }

    if (isNotificationReadAction(action) && !notificationContextEnabled()) {
      runtimeRepository.appendEvent("Notification context is disabled; skipped ${action.id}.")
      return
    }

    val reviewedStatus =
      if (action.status == "approved") {
        "Approved"
      } else if (!safetyGuardEnabled()) {
        "Approved"
      } else {
        current.safetyRecords.firstOrNull { it.actionId == action.id }?.status
          ?: if (action.requiresApproval) "Awaiting approval" else "Auto-approved"
      }

    if (reviewedStatus == "Awaiting approval") {
      val blockedRecord =
        SafetyRecord(
          id = UUID.randomUUID().toString(),
          title = "Policy Review: ${action.title}",
          detail = "Action ${action.title} is awaiting approval.",
          status = "Awaiting approval",
          actionId = action.id,
        )
      runtimeRepository.replaceSnapshot(
        current.copy(
          systemActions =
            current.systemActions.map { candidate ->
              if (candidate.id == action.id) candidate.copy(status = "pending_approval") else candidate
            },
          safetyRecords = listOf(blockedRecord) + current.safetyRecords,
          recentEvents = listOf("Execution paused for approval: ${action.id}.") + current.recentEvents,
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
    markUserActivity()
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

    markUserActivity()
    chatRepository.sendMessage(displayGoal)
    recordGoalMemory(displayGoal)

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

  private fun recordGoalMemory(goal: String) {
    val current = snapshot()
    val updated = memoryManager.updateFromGoal(current.memoryRecords, goal)
    if (updated == current.memoryRecords) return
    runtimeRepository.replaceSnapshot(
      current.copy(
        memoryRecords = updated,
        recentEvents = listOf("Short-term memory updated.") + current.recentEvents,
      ),
    )
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
        body =
          buildString {
            append("下一步：").append(content.take(220))
            append("\n计划依据：对话中手动添加的计划项。")
          },
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
    val profile = ContextWindowManager.profileFor(config.model)
    val memoryText = buildMemoryPromptText(profile)
    val systemContent =
      "你是 OpenTHU 移动端里的对话式校园助手。" +
        "用户现在只是在和你聊天，不要输出结构化执行记录，不要提到内部规则。" +
        "自然、简短、有上下文地回应；如果用户明确提出任务，再提醒他可以直接说目标。" +
        memoryText
    val messages = JSONArray()
      .put(
        JSONObject()
          .put("role", "system")
          .put("content", systemContent),
      )

    localChatHistoryForLlm(
      profile = profile,
      currentMessage = message,
      fixedPromptText = systemContent,
    ).forEach { item ->
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
        .put("max_tokens", profile.localMaxOutputTokens)

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
          history = buildGatewayChatHistory(currentMessage = message),
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
              if ((event.type == "confirmation_required" || event.type == "permission_required") && !safetyGuardEnabled()) {
                submitAgentDecision(
                  taskId = event.taskId,
                  requestId = event.requestId,
                  eventId = event.eventId,
                  decision = "approve",
                )
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
        val streamEndedWhileWaitingForDevice =
          assistantMessageId.isNotBlank() &&
            serverSummaryTaskIds.isNotEmpty() &&
            result.code == "NETWORK_ERROR"
        runtimeRepository.appendEvent(
          if (streamEndedWhileWaitingForDevice) {
            "Gateway stream ended while waiting for device execution; device result will be retried through the result queue."
          } else {
            "Gateway stream failed: ${result.code} ${result.message}"
          },
        )
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
          history = buildGatewayChatHistory(currentMessage = message),
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

  private fun buildGatewayChatHistory(currentMessage: String): List<Map<String, String>> {
    val profile = ContextWindowManager.profileFor(selectedLlmModel())
    val messages =
      chatRepository.getMessages()
        .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
        .dropLast(1)
    return ContextWindowManager
      .compactChatMessages(
        messages = messages,
        profile = profile,
        currentUserText = currentMessage,
      )
      .map { message ->
        mapOf(
          "role" to message.role,
          "text" to message.text,
        )
      }
  }

  private fun localChatHistoryForLlm(
    profile: ContextWindowProfile,
    currentMessage: String,
    fixedPromptText: String,
  ): List<Map<String, String>> {
    val messages =
      chatRepository.getMessages()
        .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
        .dropLast(1)
    return ContextWindowManager
      .compactChatMessages(
        messages = messages,
        profile = profile,
        currentUserText = currentMessage,
        fixedPromptText = fixedPromptText,
      )
      .map { message ->
        mapOf(
          "role" to message.role,
          "text" to message.text,
        )
      }
  }

  private fun selectedLlmModel(): String {
    val pref = appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
    return pref.getString("llm_model", "gpt-4.1-mini").orEmpty().trim().ifBlank { "gpt-4.1-mini" }
  }

  private fun localLlmConfig(): LocalLlmConfig? {
    val pref = appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
    val apiKey = pref.getString("openai_api_key", "").orEmpty().trim()
    if (apiKey.isBlank()) return null
    val model = selectedLlmModel()
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
    markUserActivity()
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

  private fun supportedGatewayCapabilities(): List<String> {
    val base =
      listOf(
      "get_current_time",
      "set_alarm",
      "get_semesters",
      "get_courses",
      "get_course_schedule",
      "get_assignments",
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
    return if (notificationContextEnabled()) base else base.filterNot { it == "read_notifications" }
  }

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
    val model = selectedLlmModel()
    val profile = ContextWindowManager.profileFor(model)
    val baseUrl = pref.getString("llm_base_url", "").orEmpty().trim()
    val userId = pref.getString("user_id", "").orEmpty().trim()
    val campusFile = pref.getString("campus_file", "").orEmpty().trim()
    val searchProvider = pref.getString("search_provider", "").orEmpty().trim()
    val searchEndpoint = pref.getString("search_endpoint", "").orEmpty().trim()
    val searchApiKey = pref.getString("search_api_key", "").orEmpty().trim()
    val searchScene = pref.getString("search_scene", "").orEmpty().trim()
    val searchTtl = pref.getString("search_ttl", "").orEmpty().trim()
    val timezoneFollowSystem = pref.getBoolean("timezone_follow_system", !pref.contains("timezone"))
    val timezone =
      if (timezoneFollowSystem) {
        ZoneId.systemDefault().id
      } else {
        pref.getString("timezone", "").orEmpty().trim().ifBlank { ZoneId.systemDefault().id }
      }
    val memoryContext = buildGatewayMemoryContext(profile)

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
    session["context_window"] = profile.toSessionMap()
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
    if (memoryContext.isNotEmpty()) {
      session["memory_context"] = memoryContext
      val excludeKeywords = memoryContext["exclude_keywords"]
      if (excludeKeywords is List<*> && excludeKeywords.isNotEmpty()) {
        session["search_exclude_keywords"] = excludeKeywords
      }
    }
    return session
  }

  private data class MemoryPolicy(
    val longTtlDays: Int,
    val midTtlDays: Int,
    val shortTtlDays: Int,
    val halfLifeDays: Int,
  )

  private data class RankedMemoryRecord(
    val record: MemoryRecord,
    val effectiveWeight: Int,
    val score: Double,
    val ageDays: Double,
  )

  private fun buildGatewayMemoryContext(profile: ContextWindowProfile): Map<String, Any?> {
    val entries = rankedMemoryEntries(profile.memoryEntryLimit)
    if (entries.isEmpty()) return emptyMap()
    val policy = memoryPolicy()
    val valueLimit = profile.memoryValueCharLimit
    val excludeKeywords = memorySearchExcludeKeywords(entries)
    return mapOf(
      "summary" to memorySummaryText(entries, valueLimit = (valueLimit / 2).coerceAtLeast(120)).take(profile.memorySummaryCharLimit),
      "policy" to mapOf(
        "long_ttl_days" to policy.longTtlDays,
        "mid_ttl_days" to policy.midTtlDays,
        "short_ttl_days" to policy.shortTtlDays,
        "half_life_days" to policy.halfLifeDays,
      ),
      "exclude_keywords" to excludeKeywords,
      "hard_constraints" to mapOf(
        "exclude_keywords" to excludeKeywords,
        "source" to "long_memory",
      ),
      "entries" to entries.map { record ->
        mapOf(
          "scope" to record.record.scope,
          "key" to record.record.key,
          "value" to record.record.value.take(valueLimit),
          "weight" to record.record.weight,
          "effective_weight" to record.effectiveWeight,
          "age_days" to ((record.ageDays * 100.0).roundToInt() / 100.0),
          "updated_at_epoch_ms" to record.record.updatedAtEpochMs,
        )
      },
    )
  }

  private fun memorySearchExcludeKeywords(entries: List<RankedMemoryRecord>): List<String> {
    val values =
      entries
        .map { it.record }
        .filter { record ->
          record.scope.equals("long", ignoreCase = true) &&
            (record.key.contains("preference", ignoreCase = true) ||
              record.key.contains("negative", ignoreCase = true))
        }
        .joinToString(" ") { it.value.lowercase(Locale.getDefault()) }

    if (values.isBlank()) return emptyList()

    val keywords = mutableSetOf<String>()
    fun addIfPresent(
      marker: String,
      vararg excludes: String,
    ) {
      if (values.contains(marker.lowercase(Locale.getDefault()))) {
        excludes.forEach { keywords.add(it) }
      }
    }

    addIfPresent("足球", "足球", "足球比赛", "世界杯")
    addIfPresent("世界杯", "世界杯")
    addIfPresent("football", "football", "soccer", "world cup")
    addIfPresent("篮球", "篮球", "篮球比赛")
    addIfPresent("演唱会", "演唱会")
    addIfPresent("讲座", "讲座")

    return keywords.toList()
  }

  private fun buildMemoryPromptText(profile: ContextWindowProfile): String {
    val entries = rankedMemoryEntries(profile.memoryEntryLimit.coerceAtMost(8))
    if (entries.isEmpty()) return ""
    val summary =
      memorySummaryText(
        records = entries,
        valueLimit = (profile.memoryValueCharLimit / 2).coerceAtLeast(100),
      ).take(profile.memorySummaryCharLimit)
    return "\n可参考用户记忆和反馈，但不要逐字暴露这些内部记录：\n$summary"
  }

  private fun rankedMemoryEntries(limit: Int): List<RankedMemoryRecord> {
    val now = System.currentTimeMillis()
    val policy = memoryPolicy()
    return snapshot().memoryRecords
      .mapNotNull { record -> rankMemoryRecord(record, policy, now) }
      .sortedWith(
        compareByDescending<RankedMemoryRecord> { it.score }
          .thenByDescending { it.effectiveWeight }
          .thenByDescending { it.record.updatedAtEpochMs },
      )
      .take(limit)
  }

  private fun rankMemoryRecord(
    record: MemoryRecord,
    policy: MemoryPolicy,
    nowEpochMs: Long,
  ): RankedMemoryRecord? {
    if (record.value.isBlank()) return null
    val ageDays =
      ((nowEpochMs - record.updatedAtEpochMs).coerceAtLeast(0L)).toDouble() / MILLIS_PER_DAY
    val ttlDays = memoryTtlDays(record.scope, policy)
    if (ttlDays > 0 && ageDays > ttlDays) return null
    val decayedWeight =
      if (policy.halfLifeDays > 0) {
        record.weight.toDouble() * 0.5.pow(ageDays / policy.halfLifeDays.toDouble())
      } else {
        record.weight.toDouble()
      }
    val score = decayedWeight + memoryScopeRank(record.scope) * 20.0
    return RankedMemoryRecord(
      record = record,
      effectiveWeight = decayedWeight.roundToInt().coerceAtLeast(1),
      score = score,
      ageDays = ageDays,
    )
  }

  private fun memoryPolicy(): MemoryPolicy {
    val pref = appContext.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)
    return MemoryPolicy(
      longTtlDays = readMemoryDays(pref.getString("memory_long_ttl", "365"), default = 365),
      midTtlDays = readMemoryDays(pref.getString("memory_mid_ttl", "30"), default = 30),
      shortTtlDays = readMemoryDays(pref.getString("memory_short_ttl", "7"), default = 7),
      halfLifeDays = readMemoryDays(pref.getString("memory_half_life", "30"), default = 30),
    )
  }

  private fun readMemoryDays(
    value: String?,
    default: Int,
  ): Int =
    value
      ?.trim()
      ?.toIntOrNull()
      ?.takeIf { it >= 0 }
      ?: default

  private fun memoryTtlDays(
    scope: String,
    policy: MemoryPolicy,
  ): Int =
    when (scope.lowercase(Locale.getDefault())) {
      "long" -> policy.longTtlDays
      "mid" -> policy.midTtlDays
      "short" -> policy.shortTtlDays
      else -> policy.shortTtlDays
    }

  private fun memoryScopeRank(scope: String): Int =
    when (scope.lowercase(Locale.getDefault())) {
      "long" -> 3
      "mid" -> 2
      "short" -> 1
      else -> 0
    }

  private fun memorySummaryText(
    records: List<RankedMemoryRecord>,
    valueLimit: Int,
  ): String =
    records.joinToString("\n") { ranked ->
      val record = ranked.record
      "- [${record.scope}/${record.key}/w${ranked.effectiveWeight}] ${record.value.take(valueLimit)}"
    }

  private companion object {
    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val GATEWAY_ACTIVE_WINDOW_MS = 2L * 60L * 1000L
    private const val GATEWAY_HEARTBEAT_ACTIVE_MS = 15L * 1000L
    private const val GATEWAY_HEARTBEAT_IDLE_MS = 30L * 1000L
    private const val GATEWAY_HEARTBEAT_BACKGROUND_MS = 120L * 1000L
    private const val PENDING_GATEWAY_RESULT_PREFS = "openthu_pending_gateway_results"
    private const val KEY_PENDING_GATEWAY_RESULTS = "pending_results"
    private const val MAX_PENDING_GATEWAY_RESULTS = 50
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
          history = buildGatewayChatHistory(currentMessage = goal),
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
        memoryRecords = current.memoryRecords,
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
    flushPendingGatewayResultsAsync("dispatch_loop")

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

    if (isNotificationReadAction(action) && !notificationContextEnabled()) {
      val report =
        ActionExecutionReport(
          success = false,
          message = "通知上下文已关闭，已跳过读取系统通知。",
          recoverable = false,
          semantic = "notification_context_disabled",
          metadata = mapOf("reason" to "notification_context_disabled"),
        )
      applyExecutionReport(task = task, action = action, report = report, submitGatewayResult = true)
      return
    }

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

    val executableTargetActions =
      if (notificationContextEnabled()) {
        targetActions
      } else {
        val blocked = targetActions.filter { isNotificationReadAction(it) }
        if (blocked.isNotEmpty()) {
          runtimeRepository.appendEvent("Notification context is disabled; skipped ${blocked.joinToString { it.id }}.")
        }
        targetActions.filterNot { isNotificationReadAction(it) }
      }

    if (executableTargetActions.isEmpty()) {
      runtimeRepository.appendEvent("No executable actions found after capability filtering.")
      return
    }

    val approvalGated =
      if (safetyGuardEnabled()) {
        executableTargetActions.filter { action ->
          action.status != "approved" && policyEngine.review(action).status == "Awaiting approval"
        }
      } else {
        emptyList()
      }
    if (approvalGated.isNotEmpty()) {
      val gatedIds = approvalGated.map { it.id }.toSet()
      val now = System.currentTimeMillis()
      val safetyRecords =
        approvalGated.map { action ->
          val decision = policyEngine.review(action)
          SafetyRecord(
            id = UUID.randomUUID().toString(),
            title = "Policy Review: ${action.title}",
            detail = decision.reason,
            status = decision.status,
            actionId = action.id,
            timestampEpochMs = now,
          )
        }
      val nextActions =
        current.systemActions.map { action ->
          if (action.id in gatedIds) action.copy(status = "pending_approval") else action
        }
      val nextCards =
        approvalGated.fold(current.planningCards) { cards, action ->
          val card = actionPlanningCard(latestTask, action, status = "pending_approval")
          if (card.id in current.dismissedPlanningCardIds) cards else upsertPlanningCardList(cards, card)
        }
      runtimeRepository.replaceSnapshot(
        current.copy(
          systemActions = nextActions,
          planningCards = nextCards,
          safetyRecords = safetyRecords + current.safetyRecords,
          recentEvents = listOf("Execution paused for approval: ${gatedIds.joinToString()}.") + current.recentEvents,
        ),
      )
      if (approvalGated.size == targetActions.size) {
        streamAssistant("这些动作需要你确认后才能执行。我已经把它们放到待确认状态。")
        return
      }
    }

    val approvedTargetActions = executableTargetActions - approvalGated.toSet()

    // Partition: defer calendar actions that still need runtime permission.
    val calendarDeferred = if (!hasCalendarPermissions()) {
      approvedTargetActions.filter { requiresCalendarPermission(it) }
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

    val executableActions = approvedTargetActions - calendarDeferred.toSet()
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
    enqueuePendingGatewayResult(
      PendingGatewayResult(
        taskId = taskId,
        requestId = requestId,
        skillName = action.id,
        code = code,
        message = report.message,
        dataJson = mapToJsonObject(submitData).toString(),
        enqueuedAtEpochMs = System.currentTimeMillis(),
      ),
    )
    flushPendingGatewayResultsAsync("execution_report")
  }

  private fun enqueuePendingGatewayResult(pending: PendingGatewayResult) {
    synchronized(pendingGatewayResults) {
      pendingGatewayResults.removeAll { it.taskId == pending.taskId && it.requestId == pending.requestId }
      pendingGatewayResults.add(pending)
      while (pendingGatewayResults.size > MAX_PENDING_GATEWAY_RESULTS) {
        pendingGatewayResults.removeAt(0)
      }
      savePendingGatewayResultQueueLocked()
    }
    runtimeRepository.appendEvent("Gateway result queued for ${pending.skillName} (${pending.requestId}).")
  }

  private fun flushPendingGatewayResultsAsync(reason: String) {
    if (pendingGatewayResultFlushRunning) return
    pendingGatewayResultFlushRunning = true
    thread(name = "opencray-gateway-result-flush", isDaemon = true) {
      try {
        flushPendingGatewayResults(reason)
      } finally {
        pendingGatewayResultFlushRunning = false
      }
    }
  }

  private fun flushPendingGatewayResults(reason: String) {
    var submittedCount = 0
    while (true) {
      val pending = synchronized(pendingGatewayResults) { pendingGatewayResults.firstOrNull() } ?: break
      val data = runCatching { jsonObjectToMap(JSONObject(pending.dataJson)) }.getOrElse { emptyMap() }
      val result =
        gatewayClient.submitResult(
          config = currentGatewayConfig(),
          taskId = pending.taskId,
          deviceId = deviceId,
          requestId = pending.requestId,
          skillName = pending.skillName,
          code = pending.code,
          message = pending.message,
          data = data,
        )

      if (result.success) {
        submittedCount += 1
        synchronized(pendingGatewayResults) {
          pendingGatewayResults.removeAll { it.taskId == pending.taskId && it.requestId == pending.requestId }
          savePendingGatewayResultQueueLocked()
        }
        val taskStatus = result.data?.taskStatus.orEmpty()
        val current = snapshot()
        val updatedTasks =
          if (taskStatus.isBlank()) {
            current.tasks
          } else {
            current.tasks.map { task ->
              if (task.id == pending.taskId) {
                task.copy(status = taskStatus, updatedAtEpochMs = System.currentTimeMillis())
              } else {
                task
              }
            }
          }
        runtimeRepository.replaceSnapshot(
          current.copy(
            tasks = updatedTasks,
            recentEvents = listOf("Queued result submitted for ${pending.skillName} (${pending.requestId}).") + current.recentEvents,
          ),
        )
      } else {
        synchronized(pendingGatewayResults) {
          val index = pendingGatewayResults.indexOfFirst { it.taskId == pending.taskId && it.requestId == pending.requestId }
          if (index >= 0) {
            pendingGatewayResults[index] = pending.copy(attempts = pending.attempts + 1)
            savePendingGatewayResultQueueLocked()
          }
        }
        if (result.code == "NETWORK_ERROR") {
          gatewayRegistered = false
          runtimeRepository.updateConnectionStatus("Gateway result submit failed")
          val current = snapshot()
          connectToGateway(host = current.host, port = current.port, tlsEnabled = current.tlsEnabled)
        }
        runtimeRepository.appendEvent(
          "Queued result submit failed for ${pending.skillName}: ${result.code} ${result.message}; kept for retry.",
        )
        break
      }
    }
    if (submittedCount > 0) {
      runtimeRepository.appendEvent("Gateway result queue flushed ($submittedCount) by $reason.")
    }
  }

  private fun loadPendingGatewayResultQueue(): List<PendingGatewayResult> {
    val raw = appContext
      .getSharedPreferences(PENDING_GATEWAY_RESULT_PREFS, Context.MODE_PRIVATE)
      .getString(KEY_PENDING_GATEWAY_RESULTS, "")
      .orEmpty()
    if (raw.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val item = arr.optJSONObject(i) ?: continue
          val pending = pendingGatewayResultFromJson(item) ?: continue
          add(pending)
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun savePendingGatewayResultQueueLocked() {
    val arr = JSONArray()
    pendingGatewayResults.forEach { pending -> arr.put(pendingGatewayResultToJson(pending)) }
    appContext
      .getSharedPreferences(PENDING_GATEWAY_RESULT_PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_PENDING_GATEWAY_RESULTS, arr.toString())
      .apply()
  }

  private fun pendingGatewayResultToJson(pending: PendingGatewayResult): JSONObject =
    JSONObject()
      .put("task_id", pending.taskId)
      .put("request_id", pending.requestId)
      .put("skill_name", pending.skillName)
      .put("code", pending.code)
      .put("message", pending.message)
      .put("data", JSONObject(pending.dataJson))
      .put("enqueued_at_epoch_ms", pending.enqueuedAtEpochMs)
      .put("attempts", pending.attempts)

  private fun pendingGatewayResultFromJson(item: JSONObject): PendingGatewayResult? {
    val taskId = item.optString("task_id").trim()
    val requestId = item.optString("request_id").trim()
    val skillName = item.optString("skill_name").trim()
    if (taskId.isBlank() || requestId.isBlank() || skillName.isBlank()) return null
    return PendingGatewayResult(
      taskId = taskId,
      requestId = requestId,
      skillName = skillName,
      code = item.optString("code", "SKILL_EXECUTION_FAILED"),
      message = item.optString("message", ""),
      dataJson = (item.optJSONObject("data") ?: JSONObject()).toString(),
      enqueuedAtEpochMs = item.optLong("enqueued_at_epoch_ms", System.currentTimeMillis()),
      attempts = item.optInt("attempts", 0),
    )
  }

  private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
    val obj = JSONObject()
    map.forEach { (key, value) -> obj.put(key, toJsonValue(value)) }
    return obj
  }

  private fun toJsonValue(value: Any?): Any =
    when (value) {
      null -> JSONObject.NULL
      is JSONObject -> value
      is JSONArray -> value
      is Map<*, *> -> {
        val obj = JSONObject()
        value.forEach { (key, child) ->
          if (key != null) obj.put(key.toString(), toJsonValue(child))
        }
        obj
      }
      is Iterable<*> -> JSONArray(value.map { toJsonValue(it) })
      is Array<*> -> JSONArray(value.map { toJsonValue(it) })
      is Number, is Boolean, is String -> value
      else -> value.toString()
    }

  private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> =
    buildMap {
      val keys = obj.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        put(key, jsonToValue(obj.opt(key)))
      }
    }

  private fun jsonArrayToList(arr: JSONArray): List<Any?> =
    buildList {
      for (i in 0 until arr.length()) {
        add(jsonToValue(arr.opt(i)))
      }
    }

  private fun jsonToValue(value: Any?): Any? =
    when (value) {
      null, JSONObject.NULL -> null
      is JSONObject -> jsonObjectToMap(value)
      is JSONArray -> jsonArrayToList(value)
      else -> value
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
    if (reason == "login_required" || report.semantic == "homework_cookie_login_required") return "NOT_CONFIGURED"

    if (message.contains("confirm_delete=true", ignoreCase = true)) {
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

  private fun actionPlanningBody(
    task: AgentTask,
    action: SystemAction,
    skillName: String,
    result: String?,
  ): String =
    buildString {
      append("目标：").append(task.goal.take(160))
      append("\n下一步：").append(actionNextStep(skillName, action))

      val details = actionPlanDetails(action)
      if (details.isNotBlank()) {
        append("\n计划要点：").append(details)
      }

      val reference = actionReference(action)
      if (reference.isNotBlank()) {
        append("\n计划依据：").append(reference.take(240))
      }

      if (!result.isNullOrBlank()) {
        append("\n进展备注：").append(result.take(180))
      }
    }

  private fun actionNextStep(
    skillName: String,
    action: SystemAction,
  ): String {
    val target = actionValue(action, "title", "label", "name", "query", "keyword").ifBlank { action.title }
    val time = actionValue(action, "time", "due_time", "start_time", "start", "date", "deadline")
    return when (skillName) {
      "set_alarm" ->
        if (time.isNotBlank()) {
          "确认 $time 的提醒内容「${target.take(36)}」，然后设置闹钟。"
        } else {
          "补齐提醒时间与提醒内容，再设置闹钟。"
        }
      "create_reminder" ->
        if (time.isNotBlank()) {
          "把「${target.take(36)}」整理成待办，并按 $time 跟进。"
        } else {
          "明确截止时间、提醒频率和待办标题，再创建提醒。"
        }
      "create_calendar_event" ->
        if (time.isNotBlank()) {
          "核对标题、时间、地点与冲突情况，确认后加入日历。"
        } else {
          "补齐时间和地点，确认没有冲突后加入日历。"
        }
      "get_course_schedule" -> "拉取课表后，挑出需要提醒、复习或加入日历的课程节点。"
      "get_courses" -> "整理课程列表，筛出本次目标真正相关的课程。"
      "get_semesters" -> "确认学期范围，再继续查询课表、课程或作业。"
      "get_academic_calendar" -> "读取校历节点，把考试、假期和关键截止时间拆成后续计划。"
      "get_assignments" -> "拉取课程 DDL 与作业列表，优先处理未提交或临近截止项。"
      "crawl_course_homeworks",
      "crawl_unsubmitted_homeworks" -> "核对作业标题、课程和截止时间，优先处理未提交或临近截止项。"
      "read_notifications" -> "读取通知后，只保留需要行动、提醒或加入日历的信息。"
      "get_campus_activities" -> "查看活动时间、地点和报名要求，决定是否加入日历或稍后提醒。"
      "search" -> "先确认搜索范围和关键词，再把有用结果整理成待办或日程。"
      "show_summary" -> "阅读总结，决定是否继续拆成提醒、日历或待办。"
      else -> "确认「${target.take(36)}」的目标、时间和范围，必要时补充参数后再执行。"
    }
  }

  private fun actionPlanDetails(action: SystemAction): String {
    val target = actionValue(action, "title", "label", "name", "query", "keyword")
    val time = actionValue(action, "time", "due_time", "start_time", "start", "date", "deadline")
    val location = actionValue(action, "location", "place", "venue")
    val course = actionValue(action, "course", "course_name", "course_id")
    val confirmation = if (action.requiresApproval) "执行前需要你确认" else "可自动执行，仍可先检查参数"
    return listOf(
      target.takeIf { it.isNotBlank() }?.let { "对象：${it.take(48)}" },
      course.takeIf { it.isNotBlank() }?.let { "课程：${it.take(48)}" },
      time.takeIf { it.isNotBlank() }?.let { "时间：${it.take(48)}" },
      location.takeIf { it.isNotBlank() }?.let { "地点：${it.take(48)}" },
      "确认：$confirmation",
    ).filterNotNull().joinToString("；")
  }

  private fun actionReference(action: SystemAction): String {
    val summary = action.summary.trim()
    val explain = action.explain.trim()
    val genericPrefixes = listOf("Gateway planned skill:", "Gateway dispatched skill:", "Direct invocation from UI")
    return listOf(summary, explain)
      .filter { value ->
        value.isNotBlank() &&
          value != action.title &&
          genericPrefixes.none { prefix -> value.startsWith(prefix) }
      }
      .distinct()
      .joinToString(" ")
  }

  private fun actionValue(
    action: SystemAction,
    vararg keys: String,
  ): String {
    for (key in keys) {
      val fromParams = action.params[key]?.trim().orEmpty()
      if (fromParams.isNotBlank()) return fromParams
      val fromPayload = action.payload?.get(key)?.toString()?.trim().orEmpty()
      if (fromPayload.isNotBlank() && fromPayload != "null") return fromPayload
    }
    return ""
  }

  private fun actionPlanningMetadata(
    task: AgentTask,
    action: SystemAction,
    skillName: String,
  ): Map<String, String> {
    val metadata = mutableMapOf(
      "skill" to skillName,
      "goal" to task.goal.take(160),
      "confirmation" to if (action.requiresApproval) "需要确认" else "可自动执行",
      "confidence" to "${action.confidence}%",
      "risk" to action.riskLevel,
      "recommendation" to recommendationTier(action),
    )
    val target = actionValue(action, "title", "label", "name", "query", "keyword")
    val time = actionValue(action, "time", "due_time", "start_time", "start", "date", "deadline")
    if (target.isNotBlank()) metadata["target"] = target.take(80)
    if (time.isNotBlank()) metadata["time"] = time.take(80)
    return metadata
  }

  private fun recommendationTier(action: SystemAction): String =
    when {
      action.status == "ignored" -> "已忽略"
      action.status == "snoozed" -> "稍后处理"
      action.requiresApproval || action.riskLevel.equals("high", ignoreCase = true) -> "强提醒"
      action.confidence >= 80 -> "强推荐"
      action.confidence >= 60 -> "中推荐"
      else -> "弱推荐"
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
    return PlanningCard(
      id = planningCardId(task.id, action.requestId, skillName),
      title = title,
      body = actionPlanningBody(task, action, skillName, result),
      type = inferPlanningCardType(skillName + " " + action.title + " " + action.summary),
      source = "Skill 调用",
      status = status,
      createdAtEpochMs = now,
      updatedAtEpochMs = now,
      actionId = action.id,
      taskId = task.id,
      metadata = actionPlanningMetadata(task, action, skillName),
    )
  }

  private fun streamEventPlanningCard(event: AgentStreamEvent): PlanningCard? {
    val skillName = event.skillName.ifBlank { return null }
    val now = System.currentTimeMillis()
    val title = planningCardTitle(skillName, event.title)
    val nextStep =
      if (event.type == "tool_result") {
        "查看返回内容，决定是否继续拆成提醒、日历或待办。"
      } else {
        "等待 ${planningCardTitle(skillName, event.title)} 返回结果，再确认后续安排。"
      }
    val body =
      buildString {
        append("下一步：").append(nextStep)
        val content = event.content.trim()
        if (content.isNotBlank()) {
          append("\n计划依据：").append(content.take(240))
        }
      }
    return PlanningCard(
      id = planningCardId(event.taskId, event.requestId, skillName),
      title = title,
      body = body,
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
        "get_assignments" -> "课程 DDL"
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

  private fun isNotificationReadAction(action: SystemAction): Boolean =
    action.id.substringBefore("#") == "read_notifications"

  private fun notificationContextEnabled(): Boolean =
    capabilityEnabled("notification_context")

  private fun safetyGuardEnabled(): Boolean =
    capabilityEnabled("safety_guard")

  private fun capabilityEnabled(capabilityId: String): Boolean =
    snapshot().capabilities.firstOrNull { it.id == capabilityId }?.enabled != false

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
