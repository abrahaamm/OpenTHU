package ai.opencray.app.feature.chat

data class ChatMessage(
  val id: String,
  val role: ChatRole,
  val text: String,
)

enum class ChatRole {
  User,
  Assistant,
  System,
}
