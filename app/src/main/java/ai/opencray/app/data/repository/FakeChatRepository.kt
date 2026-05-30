package ai.opencray.app.data.repository

import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.feature.chat.AgentEvent
import java.util.UUID

class FakeChatRepository : ChatRepository {
  private var activeConversationId = DEFAULT_CONVERSATION_ID
  private var conversations =
    linkedMapOf(
      DEFAULT_CONVERSATION_ID to
        listOf(
          ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.Assistant,
            text = "你好，我是 OpenTHU。你可以像聊天一样和我说话，也可以直接让我处理提醒、日历、校园活动、搜索或系统通知。",
          ),
        ),
    )

  @Synchronized
  override fun getActiveConversationId(): String = activeConversationId

  @Synchronized
  override fun getMessages(): List<ChatMessage> = conversations[activeConversationId].orEmpty()

  @Synchronized
  override fun getMessages(conversationId: String): List<ChatMessage> = conversations[conversationId].orEmpty()

  @Synchronized
  override fun createConversation(
    conversationId: String,
    initialMessages: List<ChatMessage>,
  ) {
    if (conversationId.isBlank()) return
    conversations[conversationId] = initialMessages
    activeConversationId = conversationId
  }

  @Synchronized
  override fun selectConversation(conversationId: String): Boolean {
    if (!conversations.containsKey(conversationId)) return false
    activeConversationId = conversationId
    return true
  }

  @Synchronized
  override fun deleteConversation(conversationId: String): Boolean {
    if (!conversations.containsKey(conversationId)) return false
    conversations.remove(conversationId)
    if (conversations.isEmpty()) {
      conversations[DEFAULT_CONVERSATION_ID] =
        listOf(
          ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.Assistant,
            text = "新对话开始了。你可以随便聊，也可以直接说要我完成什么。",
          ),
        )
      activeConversationId = DEFAULT_CONVERSATION_ID
      return true
    }
    if (activeConversationId == conversationId) {
      activeConversationId = conversations.keys.last()
    }
    return true
  }

  @Synchronized
  override fun sendMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return

    appendMessage(ChatRole.User, trimmed)
  }

  @Synchronized
  override fun appendMessage(
    role: ChatRole,
    text: String,
  ): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""

    val messageId = UUID.randomUUID().toString()
    val messages = conversations[activeConversationId].orEmpty()
    conversations[activeConversationId] =
      messages +
        ChatMessage(
          id = messageId,
          role = role,
          text = trimmed,
        )
    return messageId
  }

  @Synchronized
  override fun updateMessage(
    messageId: String,
    text: String,
  ) {
    if (messageId.isBlank()) return
    conversations = mapConversationMessages { message ->
        if (message.id == messageId) {
          message.copy(text = text)
        } else {
          message
        }
      }
  }

  @Synchronized
  override fun appendEvent(
    messageId: String,
    event: AgentEvent,
  ) {
    if (messageId.isBlank()) return
    conversations = mapConversationMessages { message ->
        if (message.id == messageId) {
          message.copy(events = message.events + event)
        } else {
          message
        }
      }
  }

  @Synchronized
  override fun updateEventStatus(
    eventId: String,
    status: String,
    content: String?,
  ) {
    if (eventId.isBlank()) return
    conversations = mapConversationMessages { message ->
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

  @Synchronized
  override fun clearMessages() {
    conversations[activeConversationId] =
      listOf(
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = ChatRole.Assistant,
          text = "新对话开始了。你可以随便聊，也可以直接说要我完成什么。",
        ),
      )
  }

  private fun mapConversationMessages(transform: (ChatMessage) -> ChatMessage): LinkedHashMap<String, List<ChatMessage>> {
    val updated = linkedMapOf<String, List<ChatMessage>>()
    conversations.forEach { (conversationId, messages) ->
      updated[conversationId] = messages.map(transform)
    }
    return updated
  }

  companion object {
    const val DEFAULT_CONVERSATION_ID = "conv_default"
  }
}
