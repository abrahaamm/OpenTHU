package ai.opencray.app.data.repository

import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.feature.chat.AgentEvent

interface ChatRepository {
  fun getMessages(): List<ChatMessage>

  fun sendMessage(text: String)

  fun appendMessage(
    role: ChatRole,
    text: String,
  ): String

  fun updateMessage(
    messageId: String,
    text: String,
  )

  fun appendEvent(
    messageId: String,
    event: AgentEvent,
  )

  fun clearMessages()
}
