package ai.opencray.app.runtime

import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import kotlin.math.max
import kotlin.math.min

internal data class ContextWindowProfile(
  val name: String,
  val contextTokens: Int,
  val outputReserveTokens: Int,
  val systemReserveTokens: Int,
  val memoryReserveTokens: Int,
  val maxHistoryTokens: Int,
  val minHistoryTokens: Int,
  val maxHistoryMessages: Int,
  val perMessageTokenLimit: Int,
  val anchorMessageTokenLimit: Int,
  val memoryEntryLimit: Int,
  val memoryValueCharLimit: Int,
  val memorySummaryCharLimit: Int,
  val localMaxOutputTokens: Int,
) {
  fun toSessionMap(): Map<String, Any> =
    mapOf(
      "name" to name,
      "context_tokens" to contextTokens,
      "output_reserve_tokens" to outputReserveTokens,
      "system_reserve_tokens" to systemReserveTokens,
      "memory_reserve_tokens" to memoryReserveTokens,
      "max_history_tokens" to maxHistoryTokens,
      "max_history_messages" to maxHistoryMessages,
      "per_message_token_limit" to perMessageTokenLimit,
      "memory_entry_limit" to memoryEntryLimit,
    )
}

internal data class CompactContextMessage(
  val role: String,
  val text: String,
)

internal object ContextWindowManager {
  private const val MESSAGE_OVERHEAD_TOKENS = 6

  fun profileFor(model: String): ContextWindowProfile {
    val normalized = model.trim().lowercase()
    return when {
      normalized.contains("gpt-4.1") ->
        ContextWindowProfile(
          name = "openai_1m",
          contextTokens = 1_000_000,
          outputReserveTokens = 8_192,
          systemReserveTokens = 2_048,
          memoryReserveTokens = 8_192,
          maxHistoryTokens = 180_000,
          minHistoryTokens = 8_000,
          maxHistoryMessages = 160,
          perMessageTokenLimit = 8_000,
          anchorMessageTokenLimit = 1_200,
          memoryEntryLimit = 16,
          memoryValueCharLimit = 480,
          memorySummaryCharLimit = 2_400,
          localMaxOutputTokens = 1_024,
        )
      normalized.contains("gpt-4o-mini") || normalized.contains("gpt-4o") ->
        ContextWindowProfile(
          name = "openai_128k",
          contextTokens = 128_000,
          outputReserveTokens = 4_096,
          systemReserveTokens = 2_048,
          memoryReserveTokens = 4_096,
          maxHistoryTokens = 80_000,
          minHistoryTokens = 6_000,
          maxHistoryMessages = 96,
          perMessageTokenLimit = 4_000,
          anchorMessageTokenLimit = 900,
          memoryEntryLimit = 12,
          memoryValueCharLimit = 360,
          memorySummaryCharLimit = 1_800,
          localMaxOutputTokens = 1_024,
        )
      normalized.contains("moonshot-v1-128k") || normalized.contains("moonshot-1-128k") ->
        ContextWindowProfile(
          name = "moonshot_128k",
          contextTokens = 131_072,
          outputReserveTokens = 4_096,
          systemReserveTokens = 2_048,
          memoryReserveTokens = 4_096,
          maxHistoryTokens = 82_000,
          minHistoryTokens = 6_000,
          maxHistoryMessages = 96,
          perMessageTokenLimit = 4_000,
          anchorMessageTokenLimit = 900,
          memoryEntryLimit = 12,
          memoryValueCharLimit = 360,
          memorySummaryCharLimit = 1_800,
          localMaxOutputTokens = 1_024,
        )
      normalized.contains("moonshot-v1-32k") || normalized.contains("moonshot-1-32k") ->
        ContextWindowProfile(
          name = "moonshot_32k",
          contextTokens = 32_768,
          outputReserveTokens = 2_048,
          systemReserveTokens = 1_200,
          memoryReserveTokens = 1_800,
          maxHistoryTokens = 22_000,
          minHistoryTokens = 3_000,
          maxHistoryMessages = 48,
          perMessageTokenLimit = 2_000,
          anchorMessageTokenLimit = 700,
          memoryEntryLimit = 8,
          memoryValueCharLimit = 260,
          memorySummaryCharLimit = 1_200,
          localMaxOutputTokens = 800,
        )
      normalized.contains("moonshot-v1-8k") || normalized.contains("moonshot-1-8k") ->
        ContextWindowProfile(
          name = "moonshot_8k",
          contextTokens = 8_192,
          outputReserveTokens = 1_024,
          systemReserveTokens = 800,
          memoryReserveTokens = 900,
          maxHistoryTokens = 4_200,
          minHistoryTokens = 900,
          maxHistoryMessages = 18,
          perMessageTokenLimit = 900,
          anchorMessageTokenLimit = 360,
          memoryEntryLimit = 4,
          memoryValueCharLimit = 180,
          memorySummaryCharLimit = 700,
          localMaxOutputTokens = 500,
        )
      normalized.contains("deepseek") ->
        ContextWindowProfile(
          name = "deepseek_64k",
          contextTokens = 64_000,
          outputReserveTokens = 4_096,
          systemReserveTokens = 1_800,
          memoryReserveTokens = 3_000,
          maxHistoryTokens = 42_000,
          minHistoryTokens = 4_000,
          maxHistoryMessages = 72,
          perMessageTokenLimit = 3_000,
          anchorMessageTokenLimit = 800,
          memoryEntryLimit = 10,
          memoryValueCharLimit = 320,
          memorySummaryCharLimit = 1_500,
          localMaxOutputTokens = 1_024,
        )
      else ->
        ContextWindowProfile(
          name = "default_32k",
          contextTokens = 32_768,
          outputReserveTokens = 2_048,
          systemReserveTokens = 1_200,
          memoryReserveTokens = 1_800,
          maxHistoryTokens = 20_000,
          minHistoryTokens = 3_000,
          maxHistoryMessages = 48,
          perMessageTokenLimit = 2_000,
          anchorMessageTokenLimit = 700,
          memoryEntryLimit = 8,
          memoryValueCharLimit = 260,
          memorySummaryCharLimit = 1_200,
          localMaxOutputTokens = 800,
        )
    }
  }

  fun compactChatMessages(
    messages: List<ChatMessage>,
    profile: ContextWindowProfile,
    currentUserText: String,
    fixedPromptText: String = "",
    keepAnchors: Boolean = true,
  ): List<CompactContextMessage> {
    val eligible =
      messages
        .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
        .filter { it.text.isNotBlank() }
    if (eligible.isEmpty()) return emptyList()

    val fixedTokens = estimateTokens(currentUserText) + estimateTokens(fixedPromptText)
    val rawHistoryBudget =
      profile.contextTokens - profile.outputReserveTokens - profile.systemReserveTokens -
        profile.memoryReserveTokens - fixedTokens
    val historyBudget =
      if (rawHistoryBudget <= 0) {
        0
      } else {
        rawHistoryBudget
          .coerceAtLeast(profile.minHistoryTokens)
          .coerceAtMost(profile.maxHistoryTokens)
      }

    val selected = mutableListOf<Pair<ChatMessage, CompactContextMessage>>()
    val usedIds = mutableSetOf<String>()
    var remaining = historyBudget

    for (message in eligible.asReversed()) {
      if (selected.size >= profile.maxHistoryMessages) break
      val textBudget = min(profile.perMessageTokenLimit, remaining - MESSAGE_OVERHEAD_TOKENS)
      if (textBudget < 48) break
      val trimmed = trimTextToTokenBudget(message.text, textBudget)
      if (trimmed.isBlank()) continue
      val cost = estimateTokens(trimmed) + MESSAGE_OVERHEAD_TOKENS
      selected += message to CompactContextMessage(role = roleName(message.role), text = trimmed)
      usedIds += message.id
      remaining -= cost
    }

    if (keepAnchors && remaining > 128) {
      val anchors = eligible.take(2).filterNot { it.id in usedIds }
      val anchorBudget = if (anchors.isEmpty()) 0 else min(profile.anchorMessageTokenLimit, remaining / anchors.size)
      val compactAnchors =
        anchors.mapNotNull { message ->
          val trimmed = trimTextToTokenBudget(message.text, anchorBudget - MESSAGE_OVERHEAD_TOKENS)
          if (trimmed.isBlank()) {
            null
          } else {
            message to CompactContextMessage(role = roleName(message.role), text = trimmed)
          }
        }
      selected += compactAnchors
    }

    return selected
      .distinctBy { it.first.id }
      .sortedBy { eligible.indexOf(it.first) }
      .map { it.second }
  }

  fun estimateTokens(text: String): Int {
    if (text.isBlank()) return 0
    var tokens = 0
    var latinRun = 0

    fun flushLatinRun() {
      if (latinRun > 0) {
        tokens += max(1, (latinRun + 3) / 4)
        latinRun = 0
      }
    }

    for (char in text) {
      when {
        char.isWhitespace() -> flushLatinRun()
        isCjk(char) -> {
          flushLatinRun()
          tokens += 1
        }
        char.isLetterOrDigit() -> latinRun += 1
        else -> {
          flushLatinRun()
          tokens += 1
        }
      }
    }
    flushLatinRun()
    return tokens
  }

  fun trimTextToTokenBudget(
    text: String,
    tokenBudget: Int,
  ): String {
    if (tokenBudget <= 0 || text.isBlank()) return ""
    val normalized = text.trim()
    if (estimateTokens(normalized) <= tokenBudget) return normalized

    var low = 0
    var high = normalized.length
    while (low < high) {
      val mid = (low + high + 1) / 2
      if (estimateTokens(normalized.take(mid)) <= tokenBudget - 1) {
        low = mid
      } else {
        high = mid - 1
      }
    }
    return normalized.take(low).trimEnd() + "…"
  }

  private fun roleName(role: ChatRole): String =
    when (role) {
      ChatRole.User -> "user"
      ChatRole.Assistant -> "assistant"
      ChatRole.System -> "system"
    }

  private fun isCjk(char: Char): Boolean =
    char in '\u4e00'..'\u9fff' ||
      char in '\u3400'..'\u4dbf' ||
      char in '\u3040'..'\u30ff' ||
      char in '\uac00'..'\ud7af'
}
