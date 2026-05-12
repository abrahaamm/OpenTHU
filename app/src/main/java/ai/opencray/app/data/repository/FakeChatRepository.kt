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
        text = "OpenTHU runtime 已启动，Agent 框架与后续 skills 可以从这里接入。",
      ),
      ChatMessage(
        id = UUID.randomUUID().toString(),
        role = ChatRole.Assistant,
        text = "你可以直接输入目标，我会先记录到对话历史；后续接入大模型和 skills 后，这里会展示真实执行过程。",
      ),
    )

  override fun getMessages(): List<ChatMessage> = messages

  override fun sendMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return

    appendMessage(ChatRole.User, trimmed)
  }

  override fun appendMessage(
    role: ChatRole,
    text: String,
  ): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""

    val messageId = UUID.randomUUID().toString()
    messages =
      messages +
        ChatMessage(
          id = messageId,
          role = role,
          text = trimmed,
        )
    return messageId
  }

  override fun updateMessage(
    messageId: String,
    text: String,
  ) {
    if (messageId.isBlank()) return
    messages =
      messages.map { message ->
        if (message.id == messageId) {
          message.copy(text = text)
        } else {
          message
        }
      }
  }

  override fun clearMessages() {
    messages =
      listOf(
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = ChatRole.System,
          text = "对话历史已清空，可以开始新的任务。",
        ),
      )
  }
}
