package ai.opencray.app.feature.chat

data class ChatMessage(
  val id: String,
  val role: ChatRole,
  val text: String,
  val events: List<AgentEvent> = emptyList(),
)

enum class ChatRole {
  User,
  Assistant,
  System,
}

data class AgentEvent(
  val id: String,
  val type: AgentEventType,
  val title: String = "",
  val content: String = "",
  val taskId: String = "",
  val requestId: String = "",
  val skillName: String = "",
  val status: String = "",
  val options: List<AgentEventOption> = emptyList(),
)

data class AgentEventOption(
  val label: String,
  val value: String,
)

enum class AgentEventType {
  AssistantDelta,
  AssistantFinal,
  ToolCall,
  ToolResult,
  ConfirmationRequired,
  PermissionRequired,
  Error,
  Unknown,
}
