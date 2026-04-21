package ai.opencray.app.data.repository

import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import java.util.UUID

class FakeChatRepository : ChatRepository {
  private var messages =
    listOf(
      ChatMessage(
        id = UUID.randomUUID().toString(),
        role = ChatRole.System,
        text = "OpenTHU scaffold online. Runtime and features can plug in here.",
      ),
      ChatMessage(
        id = UUID.randomUUID().toString(),
        role = ChatRole.Assistant,
        text = "可以输入校园目标，我会自动规划任务并执行审查。",
      ),
    )

  override fun getMessages(): List<ChatMessage> = messages

  override fun sendMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return

    appendMessage(ChatRole.User, trimmed)
    appendMessage(
      ChatRole.Assistant,
      "收到目标：$trimmed\n我会先规划动作，再走审查与执行链路。",
    )
  }

  override fun appendMessage(
    role: ChatRole,
    text: String,
  ) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return

    messages =
      messages +
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = role,
          text = trimmed,
        )
  }

  override fun clearMessages() {
    messages =
      listOf(
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = ChatRole.System,
          text = "Chat history cleared. Ready for the next prototype flow.",
        ),
      )
  }
}
