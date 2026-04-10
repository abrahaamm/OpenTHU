package ai.opencray.app.runtime

import android.content.Context
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.data.repository.ChatRepository
import ai.opencray.app.data.repository.RuntimeRepository
import ai.opencray.app.domain.model.CommonApp
import ai.opencray.app.feature.chat.ChatMessage

class OpenCrayRuntime(
  private val appContext: Context,
  private val runtimeRepository: RuntimeRepository,
  private val chatRepository: ChatRepository,
) {
  fun snapshot(): RuntimeSnapshot = runtimeRepository.getSnapshot()

  fun chatMessages(): List<ChatMessage> = chatRepository.getMessages()

  fun boot() {
    runtimeRepository.markRuntimeBooted()
  }

  fun connectToGateway(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
  ) {
    runtimeRepository.updateConnectionConfig(host = host, port = port, tlsEnabled = tlsEnabled)
    val tlsLabel = if (tlsEnabled) "with TLS" else "without TLS"
    runtimeRepository.updateConnectionStatus("Connected to $host:$port $tlsLabel")
    runtimeRepository.appendEvent("Gateway paired to $host:$port ($tlsLabel).")
  }

  fun setCapabilityEnabled(
    capabilityId: String,
    enabled: Boolean,
  ) {
    runtimeRepository.setCapabilityEnabled(capabilityId, enabled)
    val stateLabel = if (enabled) "enabled" else "disabled"
    runtimeRepository.appendEvent("Capability $capabilityId $stateLabel.")
  }

  fun executeAction(actionId: String) {
    runtimeRepository.markActionExecuted(actionId)
    runtimeRepository.updateConnectionStatus("Executed action: $actionId")
    runtimeRepository.appendEvent("System action executed: $actionId")
  }

  fun approvePendingActions() {
    runtimeRepository.approvePendingSafety()
    runtimeRepository.appendEvent("Pending safety approvals accepted.")
  }

  fun updateCommonApps(apps: List<CommonApp>) {
    runtimeRepository.updateCommonApps(apps)
  }

  fun noteAppLaunch(
    appLabel: String,
    succeeded: Boolean,
  ) {
    val result = if (succeeded) "opened" else "not installed or not launchable"
    runtimeRepository.updateConnectionStatus("App action: $appLabel $result")
    runtimeRepository.appendEvent("Common app launch requested: $appLabel -> $result")
  }

  fun sendChatMessage(text: String) {
    runtimeRepository.updateConnectionStatus("Runtime active on ${appContext.packageName}")
    runtimeRepository.appendEvent("Chat message sent: ${text.take(32)}")
    chatRepository.sendMessage(text)
  }

  fun clearChat() {
    chatRepository.clearMessages()
    runtimeRepository.appendEvent("Chat history cleared from prototype UI.")
  }
}
