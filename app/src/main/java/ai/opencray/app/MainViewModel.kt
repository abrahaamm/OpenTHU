package ai.opencray.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.AgentTask
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.AuditEntry
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.PendingConflictResolution
import ai.opencray.app.domain.model.PlanningCard
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.runtime.CalendarPermissionDelegate
import ai.opencray.app.runtime.OpenCrayRuntime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val runtime: OpenCrayRuntime =
    (app as OpenCrayApplication).appContainer.runtime
  private val settingsPref = app.getSharedPreferences("openthu_settings", Context.MODE_PRIVATE)

  private var selectedDestination: AppDestination = AppDestination.Chat
  private var selectedConversationId: String = "conv_default"
  private val conversations = linkedMapOf<String, ConversationThread>()
  private var settingsState: SettingsUiState = loadSettings()
  private var hostText: String = settingsState.host
  private var portText: String = settingsState.port
  private var tlsEnabled: Boolean = settingsState.tlsEnabled
  private val dateFormatter = DateTimeFormatter.ofPattern("M月d日")

  private fun conversationSummaries(): List<ConversationSummary> {
    return conversations.values
      .filter { thread -> thread.messages.any { it.role == ChatRole.User } }
      .sortedByDescending { it.updatedAtEpochMs }
      .map { thread ->
        val firstUserMessage =
          thread.messages
            .firstOrNull { it.role == ChatRole.User }
            ?.text
            ?.trim()
            ?.take(24)
            ?.ifBlank { "暂未发送用户消息" }
            ?: "暂未发送用户消息"

        ConversationSummary(
          id = thread.id,
          title = formatConversationDate(thread.updatedAtEpochMs),
          subtitle = firstUserMessage,
          updatedAtEpochMs = thread.updatedAtEpochMs,
          selected = thread.id == selectedConversationId,
        )
      }
  }

  private fun formatConversationDate(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val yearsAgo = today.year - date.year
    return when (date) {
      today -> "今天"
      today.minusDays(1) -> "昨天"
      else ->
        when {
          date.year == today.year -> date.format(dateFormatter)
          yearsAgo == 1 -> "去年"
          yearsAgo == 2 -> "前年"
          yearsAgo > 2 -> "${yearsAgo}年前"
          else -> date.format(dateFormatter)
        }
    }
  }

  private fun upsertCurrentConversation(messages: List<ChatMessage>) {
    val existing = conversations[selectedConversationId]
    val titleSeed = messages.firstOrNull { it.role == ChatRole.User }?.text?.trim()?.take(24) ?: "新对话"
    val existingHasUserMessage = existing?.messages?.any { it.role == ChatRole.User } == true
    val now = System.currentTimeMillis()
    conversations[selectedConversationId] =
      ConversationThread(
        id = selectedConversationId,
        title = if (existingHasUserMessage) existing?.title ?: titleSeed else titleSeed,
        messages = messages,
        updatedAtEpochMs = now,
      )
  }

  private fun buildUiState(): MainUiState {
    selectedConversationId = runtime.activeConversationId()
    val runtimeMessages = runtime.chatMessages()
    if (runtimeMessages.isNotEmpty() && conversations[selectedConversationId]?.messages != runtimeMessages) {
      upsertCurrentConversation(runtimeMessages)
    }
    val snapshot = runtime.snapshot()
    val refreshedSettings = settingsState.copy(host = hostText, port = portText, tlsEnabled = tlsEnabled)
    settingsState = refreshedSettings
    return MainUiState(
      currentDestination = selectedDestination,
      host = hostText,
      port = portText,
      tlsEnabled = tlsEnabled,
      settings = refreshedSettings,
      snapshot = snapshot,
      contextSignals = snapshot.contextSignals,
      systemActions = snapshot.systemActions,
      planningCards = snapshot.planningCards,
      safetyRecords = snapshot.safetyRecords,
      tasks = snapshot.tasks,
      memoryRecords = snapshot.memoryRecords,
      auditTrail = snapshot.auditTrail,
      chatMessages = runtimeMessages,
      conversationSummaries = conversationSummaries(),
      selectedConversationId = selectedConversationId,
      pendingConflict = snapshot.pendingConflict,
    )
  }

  init {
    runtime.boot()
    runtime.connectToGateway(
      host = hostText.ifBlank { "10.0.2.2" },
      port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 18789,
      tlsEnabled = tlsEnabled,
    )
    selectedConversationId = runtime.activeConversationId()
    val bootMessages = runtime.chatMessages()
    conversations[selectedConversationId] =
      ConversationThread(
        id = selectedConversationId,
        title = "默认会话",
        messages = bootMessages,
        updatedAtEpochMs = System.currentTimeMillis(),
      )
  }

  fun getUiState(): MainUiState = buildUiState()

  fun setAppInForeground(foreground: Boolean) {
    runtime.setAppInForeground(foreground)
  }

  fun selectDestination(destination: AppDestination) {
    runtime.markUserActivity()
    selectedDestination = destination
  }

  fun sendChatMessage(
    text: String,
    attachedFileUri: String? = null,
    attachedFileName: String? = null,
  ) {
    val normalized = text.trim()
    val normalizedUri = attachedFileUri.orEmpty().trim()
    if (normalized.isEmpty() && normalizedUri.isEmpty()) return
    val planned =
      if (normalizedUri.isNotEmpty()) {
        runtime.planGoalWithAttachment(
          goal = normalized,
          fileUri = normalizedUri,
          fileName = attachedFileName.orEmpty().trim(),
        )
      } else {
        runtime.planGoal(normalized)
      }
    if (planned) {
      runtime.runActions()
    }
    upsertCurrentConversation(runtime.chatMessages())
    selectedDestination = AppDestination.Chat
  }

  fun regenerateLastResponse() {
    val lastUserText =
      runtime.chatMessages()
        .lastOrNull { it.role == ChatRole.User }
        ?.text
        ?.trim()
        .orEmpty()
    if (lastUserText.isBlank()) return
    sendChatMessage(lastUserText)
  }

  fun invokeSkill(
    skillId: String,
    args: Map<String, String> = emptyMap(),
  ) {
    runtime.invokeSkill(skillId = skillId, args = args)
    upsertCurrentConversation(runtime.chatMessages())
    selectedDestination = AppDestination.Chat
  }

  fun updateHost(value: String) {
    hostText = value
    settingsState = settingsState.copy(host = value)
  }

  fun updatePort(value: String) {
    portText = value
    settingsState = settingsState.copy(port = value)
  }

  fun updateTlsEnabled(enabled: Boolean) {
    tlsEnabled = enabled
    settingsState = settingsState.copy(tlsEnabled = enabled)
    runtime.updateGatewayTls(enabled)
  }

  fun applyConnectionDraft(reconnectIfRegistered: Boolean = true): Boolean {
    val host = hostText.trim()
    val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
    if (host.isBlank()) return false
    hostText = host
    portText = port.toString()
    settingsState = settingsState.copy(host = host, port = portText, tlsEnabled = tlsEnabled)
    runtime.applyGatewayConfig(
      host = host,
      port = port,
      tlsEnabled = tlsEnabled,
      reconnectIfRegistered = reconnectIfRegistered,
    )
    return true
  }

  fun connectToGateway() {
    persistCurrentSettings()
    if (applyConnectionDraft(reconnectIfRegistered = false)) {
      runtime.reconnectGateway()
    }
    selectedDestination = AppDestination.Planning
  }

  fun reconnectGateway() {
    runtime.reconnectGateway()
  }

  fun updateConfiguredModel(model: String) {
    runtime.updateConfiguredModel(model)
  }

  fun configuredModel(): String = runtime.configuredModel()

  fun updateSettings(next: SettingsUiState) {
    settingsState = next
    hostText = next.host
    portText = next.port
    tlsEnabled = next.tlsEnabled
    runtime.updateGatewayTls(next.tlsEnabled)
    persistSettings(next)
    runtime.updateConfiguredModel(next.llmModel.ifBlank { "moonshot-v1-8k" })
  }

  fun persistCurrentSettings(): Boolean = persistSettings(settingsState)

  fun mergeLearnLoginResult(
    learnBaseUrl: String,
    homeworkCookie: String,
    homeworkCsrf: String,
    webvpnCookie: String,
  ) {
    val merged =
      settingsState.copy(
        learnBaseUrl = learnBaseUrl.ifBlank { settingsState.learnBaseUrl },
        homeworkCookie = homeworkCookie.ifBlank { settingsState.homeworkCookie },
        homeworkCsrf = homeworkCsrf.ifBlank { settingsState.homeworkCsrf },
        webvpnCookie = webvpnCookie.ifBlank { settingsState.webvpnCookie },
      )
    updateSettings(merged)
  }

  fun settingsWarnings(): List<String> = settingsWarnings(settingsState)

  fun toggleCapability(
    capabilityId: String,
    enabled: Boolean,
  ) {
    runtime.setCapabilityEnabled(capabilityId = capabilityId, enabled = enabled)
  }

  fun runAgentPlan() {
    runtime.runActions()
    upsertCurrentConversation(runtime.chatMessages())
    selectedDestination = AppDestination.Planning
  }

  fun clearChat() {
    runtime.clearChat()
    upsertCurrentConversation(runtime.chatMessages())
  }

  fun executeAction(actionId: String) {
    runtime.executeAction(actionId)
    selectedDestination = AppDestination.Planning
  }

  fun deletePlanningCard(cardId: String) {
    runtime.deletePlanningCard(cardId)
    selectedDestination = AppDestination.Planning
  }

  fun movePlanningCard(
    cardId: String,
    offset: Int,
  ) {
    runtime.movePlanningCard(cardId, offset)
    selectedDestination = AppDestination.Planning
  }

  fun addPreference(preference: String): Boolean =
    runtime.addManualPreference(preference)

  fun deletePreference(index: Int): Boolean =
    runtime.deleteLongPreference(index)

  fun updatePreference(
    index: Int,
    preference: String,
  ): Boolean =
    runtime.updateLongPreference(index, preference)

  fun clearAllMemory(): Boolean =
    runtime.clearAllMemory()

  fun snoozeAction(actionId: String): Boolean {
    val ok = runtime.snoozeAction(actionId)
    selectedDestination = AppDestination.Planning
    return ok
  }

  fun ignoreAction(actionId: String): Boolean {
    val ok = runtime.ignoreAction(actionId)
    selectedDestination = AppDestination.Planning
    return ok
  }

  fun approvePendingActions() {
    runtime.approvePendingActions()
    selectedDestination = AppDestination.Planning
  }

  fun submitAgentDecision(
    taskId: String,
    requestId: String,
    eventId: String,
    decision: String,
  ) {
    runtime.submitAgentDecision(
      taskId = taskId,
      requestId = requestId,
      eventId = eventId,
      decision = decision,
    )
    selectedDestination = AppDestination.Chat
  }

  fun setCalendarPermissionDelegate(delegate: CalendarPermissionDelegate?) {
    runtime.setCalendarPermissionDelegate(delegate)
  }

  fun notifyCalendarPermissionGranted() {
    runtime.notifyCalendarPermissionGranted()
  }

  fun resolveConflict(strategy: String) {
    runtime.resolveConflict(strategy)
  }

  fun createConversation() {
    val now = System.currentTimeMillis()
    val id = "conv_${UUID.randomUUID().toString().take(8)}"
    val systemIntro =
      ChatMessage(
        id = "sys_${UUID.randomUUID().toString().take(8)}",
        role = ChatRole.Assistant,
        text = "New chat started. You can chat directly, or use natural language to delegate tasks.",
      )
    conversations[id] =
      ConversationThread(
        id = id,
        title = "新会话",
        messages = listOf(systemIntro),
        updatedAtEpochMs = now,
      )
    runtime.createConversation(id, listOf(systemIntro))
    selectedConversationId = id
    selectedDestination = AppDestination.Chat
  }

  fun selectConversation(conversationId: String) {
    if (conversations.containsKey(conversationId) && runtime.selectConversation(conversationId)) {
      selectedConversationId = conversationId
      selectedDestination = AppDestination.Chat
    }
  }

  fun deleteConversation(conversationId: String) {
    conversations.remove(conversationId)
    if (runtime.deleteConversation(conversationId)) {
      selectedConversationId = runtime.activeConversationId()
      selectedDestination = AppDestination.Chat
    }
  }

  private fun loadSettings(): SettingsUiState {
    val current = runtime.snapshot()
    val followSystemTimezone = settingsPref.getBoolean("timezone_follow_system", !settingsPref.contains("timezone"))
    val timezone =
      if (followSystemTimezone) {
        systemTimezoneId()
      } else {
        settingsPref.getString("timezone", "UTC").orEmpty().ifBlank { systemTimezoneId() }
      }
    val host = settingsPref.getString("host", current.host).orEmpty().trim().ifBlank { current.host.ifBlank { "10.0.2.2" } }
    val port =
      (
        if (settingsPref.contains("port")) {
          settingsPref.getInt("port", current.port)
        } else {
          current.port
        }
      ).takeIf { it in 1..65535 } ?: 18789
    val tls = settingsPref.getBoolean("tls_enabled", current.tlsEnabled)
    return SettingsUiState(
      host = host,
      port = port.toString(),
      tlsEnabled = tls,
      llmModel = settingsPref.getString("llm_model", runtime.configuredModel()).orEmpty().ifBlank { "moonshot-v1-8k" },
      llmBaseUrl = settingsPref.getString("llm_base_url", "").orEmpty(),
      openAiKey = settingsPref.getString("openai_key", "").orEmpty(),
      userId = settingsPref.getString("user_id", "android_user").orEmpty().ifBlank { "android_user" },
      webvpnCookie = settingsPref.getString("webvpn_cookie", "").orEmpty(),
      webvpnCsrf = settingsPref.getString("webvpn_csrf", "").orEmpty(),
      learnBaseUrl = settingsPref.getString("learn_base_url", "https://learn.tsinghua.edu.cn").orEmpty().ifBlank { "https://learn.tsinghua.edu.cn" },
      homeworkCookie = settingsPref.getString("homework_cookie", "").orEmpty(),
      homeworkCsrf = settingsPref.getString("homework_csrf", "").orEmpty(),
      campusFile = settingsPref.getString("campus_file", "").orEmpty(),
      searchProvider = settingsPref.getString("search_provider", "duckduckgo").orEmpty().ifBlank { "duckduckgo" },
      searchEndpoint = settingsPref.getString("search_endpoint", "https://lite.duckduckgo.com/lite/").orEmpty().ifBlank { "https://lite.duckduckgo.com/lite/" },
      searchApiKey = settingsPref.getString("search_api_key", "").orEmpty(),
      searchScene = settingsPref.getString("search_scene", "hybrid").orEmpty().ifBlank { "hybrid" },
      searchTtl = settingsPref.getString("search_ttl", "3600").orEmpty().ifBlank { "3600" },
      showPlanningDetails = settingsPref.getBoolean("show_planning_details", false),
      memoryFile = settingsPref.getString("memory_file", "agent/langgraph/memory_store.json").orEmpty(),
      memoryLongTtl = settingsPref.getString("memory_long_ttl", "365").orEmpty().ifBlank { "365" },
      memoryMidTtl = settingsPref.getString("memory_mid_ttl", "30").orEmpty().ifBlank { "30" },
      memoryShortTtl = settingsPref.getString("memory_short_ttl", "7").orEmpty().ifBlank { "7" },
      memoryHalfLife = settingsPref.getString("memory_half_life", "30").orEmpty().ifBlank { "30" },
      adbBin = settingsPref.getString("adb_bin", "adb").orEmpty().ifBlank { "adb" },
      adbSerial = settingsPref.getString("adb_serial", "").orEmpty(),
      timezoneFollowSystem = followSystemTimezone,
      timezone = timezone,
    )
  }

  private fun persistSettings(settings: SettingsUiState): Boolean {
    val host = settings.host.trim()
    val port = settings.port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    if (host.isBlank() || port == null) return false
    val timezone = settings.timezone.trim().ifBlank { systemTimezoneId() }
    settingsPref.edit()
      .putString("host", host)
      .putInt("port", port)
      .putBoolean("tls_enabled", settings.tlsEnabled)
      .putString("llm_model", settings.llmModel.trim().ifBlank { "moonshot-v1-8k" })
      .putString("llm_base_url", settings.llmBaseUrl.trim())
      .putString("openai_key", settings.openAiKey.trim())
      .putString("user_id", settings.userId.trim().ifBlank { "android_user" })
      .putString("webvpn_cookie", settings.webvpnCookie.trim())
      .putString("webvpn_csrf", settings.webvpnCsrf.trim())
      .putString("learn_base_url", settings.learnBaseUrl.trim().ifBlank { "https://learn.tsinghua.edu.cn" })
      .putString("homework_cookie", settings.homeworkCookie.trim())
      .putString("homework_csrf", settings.homeworkCsrf.trim())
      .putString("campus_file", settings.campusFile.trim())
      .putString("search_provider", settings.searchProvider.trim().ifBlank { "duckduckgo" })
      .putString("search_endpoint", normalizeSearchEndpoint(settings.searchEndpoint))
      .putString("search_api_key", settings.searchApiKey.trim())
      .putString("search_scene", settings.searchScene.trim().lowercase().ifBlank { "hybrid" })
      .putString("search_ttl", settings.searchTtl.trim())
      .putBoolean("show_planning_details", settings.showPlanningDetails)
      .putString("memory_file", settings.memoryFile.trim())
      .putString("memory_long_ttl", settings.memoryLongTtl.trim())
      .putString("memory_mid_ttl", settings.memoryMidTtl.trim())
      .putString("memory_short_ttl", settings.memoryShortTtl.trim())
      .putString("memory_half_life", settings.memoryHalfLife.trim())
      .putString("adb_bin", settings.adbBin.trim())
      .putString("adb_serial", settings.adbSerial.trim())
      .putBoolean("timezone_follow_system", settings.timezoneFollowSystem)
      .putString("timezone", timezone)
      .apply()
    hostText = host
    portText = port.toString()
    tlsEnabled = settings.tlsEnabled
    settingsState = settings.copy(host = host, port = portText, timezone = timezone)
    return true
  }

  private fun normalizeSearchEndpoint(endpoint: String): String {
    val value = endpoint.trim()
    if (value.isBlank()) return "https://lite.duckduckgo.com/lite/"
    return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
  }

  private fun settingsWarnings(settings: SettingsUiState): List<String> {
    val warnings = mutableListOf<String>()
    if (settings.webvpnCookie.trim().isEmpty()) warnings += "WebVPN Cookie 为空，课表和校内资讯可能不可用。"
    if (settings.homeworkCookie.trim().isEmpty()) warnings += "网络学堂 Cookie 为空，作业 skill 需要先登录。"
    if (settings.searchProvider.trim().equals("brave", ignoreCase = true) && settings.searchApiKey.trim().isEmpty()) {
      warnings += "Brave 搜索需要 API Key。"
    }
    if (settings.port.trim().toIntOrNull()?.takeIf { it in 1..65535 } == null) warnings += "服务端口无效。"
    return warnings
  }

  private fun systemTimezoneId(): String = ZoneId.systemDefault().id
}

data class MainUiState(
  val currentDestination: AppDestination,
  val host: String,
  val port: String,
  val tlsEnabled: Boolean,
  val settings: SettingsUiState,
  val snapshot: RuntimeSnapshot,
  val contextSignals: List<ContextSignal>,
  val systemActions: List<SystemAction>,
  val planningCards: List<PlanningCard>,
  val safetyRecords: List<SafetyRecord>,
  val tasks: List<AgentTask>,
  val memoryRecords: List<MemoryRecord>,
  val auditTrail: List<AuditEntry>,
  val chatMessages: List<ChatMessage>,
  val conversationSummaries: List<ConversationSummary>,
  val selectedConversationId: String,
  val pendingConflict: PendingConflictResolution? = null,
)

data class SettingsUiState(
  val host: String,
  val port: String,
  val tlsEnabled: Boolean,
  val llmModel: String,
  val llmBaseUrl: String,
  val openAiKey: String,
  val userId: String,
  val webvpnCookie: String,
  val webvpnCsrf: String,
  val learnBaseUrl: String,
  val homeworkCookie: String,
  val homeworkCsrf: String,
  val campusFile: String,
  val searchProvider: String,
  val searchEndpoint: String,
  val searchApiKey: String,
  val searchScene: String,
  val searchTtl: String,
  val showPlanningDetails: Boolean,
  val memoryFile: String,
  val memoryLongTtl: String,
  val memoryMidTtl: String,
  val memoryShortTtl: String,
  val memoryHalfLife: String,
  val adbBin: String,
  val adbSerial: String,
  val timezoneFollowSystem: Boolean,
  val timezone: String,
)

data class ConversationSummary(
  val id: String,
  val title: String,
  val subtitle: String,
  val updatedAtEpochMs: Long,
  val selected: Boolean,
)

data class ConversationThread(
  val id: String,
  val title: String,
  val messages: List<ChatMessage>,
  val updatedAtEpochMs: Long,
)
