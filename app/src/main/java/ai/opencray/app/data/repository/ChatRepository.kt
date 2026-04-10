package ai.opencray.app.data.repository

import ai.opencray.app.feature.chat.ChatMessage

interface ChatRepository {
  fun getMessages(): List<ChatMessage>

  fun sendMessage(text: String)

  fun clearMessages()
}
