package ai.opencray.app.data.repository

import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole

interface ChatRepository {
  fun getMessages(): List<ChatMessage>

  fun sendMessage(text: String)

  fun appendMessage(
    role: ChatRole,
    text: String,
  )

  fun clearMessages()
}
