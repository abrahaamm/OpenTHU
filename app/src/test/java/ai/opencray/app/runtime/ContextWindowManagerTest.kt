package ai.opencray.app.runtime

import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowManagerTest {
  @Test
  fun profileForUsesSelectedModelWindow() {
    val small = ContextWindowManager.profileFor("moonshot-v1-8k")
    val large = ContextWindowManager.profileFor("gpt-4.1-mini")

    assertEquals("moonshot_8k", small.name)
    assertEquals("openai_1m", large.name)
    assertTrue(large.maxHistoryTokens > small.maxHistoryTokens)
    assertTrue(large.memoryEntryLimit > small.memoryEntryLimit)
  }

  @Test
  fun compactChatMessagesFitsSmallModelBudget() {
    val profile = ContextWindowManager.profileFor("moonshot-v1-8k")
    val messages =
      (1..36).flatMap { index ->
        listOf(
          message(role = ChatRole.User, text = "用户消息 $index " + "清华校园活动".repeat(160)),
          message(role = ChatRole.Assistant, text = "助手回复 $index " + "候选活动和时间地点".repeat(160)),
        )
      }

    val compact =
      ContextWindowManager.compactChatMessages(
        messages = messages,
        profile = profile,
        currentUserText = "继续说最近的活动",
        fixedPromptText = "system prompt",
      )

    val tokenCount = compact.sumOf { ContextWindowManager.estimateTokens(it.text) + 6 }
    assertTrue(compact.size <= profile.maxHistoryMessages)
    assertTrue(tokenCount <= profile.maxHistoryTokens)
    assertTrue(compact.last().text.contains("36"))
    assertTrue(compact.any { it.text.endsWith("…") })
  }

  private fun message(
    role: ChatRole,
    text: String,
  ): ChatMessage =
    ChatMessage(
      id = "${role.name}_${text.hashCode()}",
      role = role,
      text = text,
    )
}
