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
          text = "OpenCray scaffold online. Runtime and features can plug in here.",
        ),
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = ChatRole.Assistant,
          text = "现在可以继续接入真实网关、设备能力和协议层。",
        ),
      )

  override fun getMessages(): List<ChatMessage> = messages

  override fun sendMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return

    messages =
      messages +
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = ChatRole.User,
          text = trimmed,
        )

    messages =
      messages +
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = ChatRole.Assistant,
          text = "收到：$trimmed\n\n这里之后可以替换成真正的 OpenCray agent/gateway 响应链路。",
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
