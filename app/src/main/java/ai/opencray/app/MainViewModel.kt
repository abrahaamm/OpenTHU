package ai.opencray.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.AgentTask
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.AuditEntry
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.PendingConflictResolution
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

  private var selectedDestination: AppDestination = AppDestination.Chat
  private var selectedConversationId: String = "conv_default"
  private val conversations = linkedMapOf<String, ConversationThread>()
  private var hostText: String = runtime.snapshot().host
  private var portText: String = runtime.snapshot().port.toString()
  private var tlsEnabled: Boolean = runtime.snapshot().tlsEnabled
  private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

  private fun conversationSummaries(): List<ConversationSummary> {
    return conversations.values
      .sortedByDescending { it.updatedAtEpochMs }
      .map { thread ->
        val lastUserMessage =
          thread.messages
            .asReversed()
            .firstOrNull { it.role == ChatRole.User }
            ?.text
            ?.trim()
            ?.take(40)
            ?.ifBlank { "暂未发送用户消息" }
            ?: "暂未发送用户消息"

        ConversationSummary(
          id = thread.id,
          title = formatConversationDate(thread.updatedAtEpochMs),
          subtitle = lastUserMessage,
          updatedAtEpochMs = thread.updatedAtEpochMs,
          selected = thread.id == selectedConversationId,
        )
      }
  }

  private fun formatConversationDate(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
      today -> "今天"
      today.minusDays(1) -> "昨天"
      else -> date.format(dateFormatter)
    }
  }

  private fun upsertCurrentConversation(messages: List<ChatMessage>) {
    val existing = conversations[selectedConversationId]
    val titleSeed = messages.firstOrNull { it.role == ChatRole.User }?.text?.take(18) ?: "新对话"
    val now = System.currentTimeMillis()
    conversations[selectedConversationId] =
      ConversationThread(
        id = selectedConversationId,
        title = existing?.title ?: titleSeed,
        messages = messages,
        updatedAtEpochMs = now,
      )
  }

  private fun buildUiState(): MainUiState {
    val runtimeMessages = runtime.chatMessages()
    if (runtimeMessages.isNotEmpty() && conversations[selectedConversationId]?.messages != runtimeMessages) {
      upsertCurrentConversation(runtimeMessages)
    }
    val snapshot = runtime.snapshot()
    return MainUiState(
      currentDestination = selectedDestination,
      host = hostText,
      port = portText,
      tlsEnabled = tlsEnabled,
      snapshot = snapshot,
      contextSignals = snapshot.contextSignals,
      systemActions = snapshot.systemActions,
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

  fun selectDestination(destination: AppDestination) {
    selectedDestination = destination
  }

  fun sendChatMessage(text: String) {
    val normalized = text.trim()
    if (normalized.isEmpty()) return
    val planned = runtime.planGoal(normalized)
    if (planned) {
      runtime.runActions()
    }
    upsertCurrentConversation(runtime.chatMessages())
    selectedDestination = AppDestination.Chat
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
  }

  fun updatePort(value: String) {
    portText = value
  }

  fun updateTlsEnabled(enabled: Boolean) {
    tlsEnabled = enabled
  }

  fun connectToGateway() {
    val host = hostText.trim().ifEmpty { "10.0.2.2" }
    val port = portText.toIntOrNull() ?: 18789
    runtime.connectToGateway(host = host, port = port, tlsEnabled = tlsEnabled)
    selectedDestination = AppDestination.Planning
  }

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

  fun approvePendingActions() {
    runtime.approvePendingActions()
    selectedDestination = AppDestination.Planning
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
        role = ChatRole.System,
        text = "新会话已创建。你可以直接输入目标开始规划。",
      )
    conversations[id] =
      ConversationThread(
        id = id,
        title = "新会话",
        messages = listOf(systemIntro),
        updatedAtEpochMs = now,
      )
    selectedConversationId = id
    selectedDestination = AppDestination.Chat
  }

  fun selectConversation(conversationId: String) {
    if (conversations.containsKey(conversationId)) {
      selectedConversationId = conversationId
      selectedDestination = AppDestination.Chat
    }
  }
}

data class MainUiState(
  val currentDestination: AppDestination,
  val host: String,
  val port: String,
  val tlsEnabled: Boolean,
  val snapshot: RuntimeSnapshot,
  val contextSignals: List<ContextSignal>,
  val systemActions: List<SystemAction>,
  val safetyRecords: List<SafetyRecord>,
  val tasks: List<AgentTask>,
  val memoryRecords: List<MemoryRecord>,
  val auditTrail: List<AuditEntry>,
  val chatMessages: List<ChatMessage>,
  val conversationSummaries: List<ConversationSummary>,
  val selectedConversationId: String,
  val pendingConflict: PendingConflictResolution? = null,
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
