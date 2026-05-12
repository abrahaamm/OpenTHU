package ai.opencray.app.data.repository

import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.feature.chat.AgentEvent
import java.util.UUID

class FakeChatRepository : ChatRepository {
  private var messages =
    listOf(
      ChatMessage(
        id = UUID.randomUUID().toString(),
        role = ChatRole.Assistant,
        text = "你好，我是 OpenTHU。你可以像聊天一样和我说话，也可以直接让我处理提醒、日历、校园活动、搜索或系统通知。",
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

  override fun appendEvent(
    messageId: String,
    event: AgentEvent,
  ) {
    if (messageId.isBlank()) return
    messages =
      messages.map { message ->
        if (message.id == messageId) {
          message.copy(events = message.events + event)
        } else {
          message
        }
      }
  }

  override fun updateEventStatus(
    eventId: String,
    status: String,
    content: String?,
  ) {
    if (eventId.isBlank()) return
    messages =
      messages.map { message ->
        val updatedEvents =
          message.events.map { event ->
            if (event.id == eventId) {
              event.copy(
                status = status,
                content = content ?: event.content,
              )
            } else {
              event
            }
          }
        if (updatedEvents == message.events) {
          message
        } else {
          message.copy(events = updatedEvents)
        }
      }
  }

  override fun clearMessages() {
    messages =
      listOf(
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = ChatRole.Assistant,
          text = "新对话开始了。你可以随便聊，也可以直接说要我完成什么。",
        ),
      )
  }
}
