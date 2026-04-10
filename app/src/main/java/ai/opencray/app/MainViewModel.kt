package ai.opencray.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.CommonApp
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.runtime.OpenCrayRuntime

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val runtime: OpenCrayRuntime =
    (app as OpenCrayApplication).appContainer.runtime

  private var selectedDestination: AppDestination = AppDestination.Context
  private var composerText: String = ""
  private var hostText: String = runtime.snapshot().host
  private var portText: String = runtime.snapshot().port.toString()
  private var tlsEnabled: Boolean = runtime.snapshot().tlsEnabled

  private fun buildUiState(): MainUiState =
    MainUiState(
      currentDestination = selectedDestination,
      draft = composerText,
      host = hostText,
      port = portText,
      tlsEnabled = tlsEnabled,
      snapshot = runtime.snapshot(),
      commonApps = runtime.snapshot().commonApps,
      contextSignals = runtime.snapshot().contextSignals,
      systemActions = runtime.snapshot().systemActions,
      safetyRecords = runtime.snapshot().safetyRecords,
      chatMessages = runtime.chatMessages(),
    )

  init {
    runtime.boot()
  }

  fun getUiState(): MainUiState = buildUiState()

  fun selectDestination(destination: AppDestination) {
    selectedDestination = destination
  }

  fun updateDraft(value: String) {
    composerText = value
  }

  fun updateHost(value: String) {
    hostText = value
  }

  fun updatePort(value: String) {
    portText = value
  }

  fun updateTlsEnabled(enabled: Boolean) {
    tlsEnabled = enabled
  }

  fun connectToGateway() {
    val host = hostText.trim().ifEmpty { "127.0.0.1" }
    val port = portText.toIntOrNull() ?: 18789
    runtime.connectToGateway(host = host, port = port, tlsEnabled = tlsEnabled)
    selectedDestination = AppDestination.Actions
  }

  fun toggleCapability(
    capabilityId: String,
    enabled: Boolean,
  ) {
    runtime.setCapabilityEnabled(capabilityId = capabilityId, enabled = enabled)
  }

  fun sendDraft() {
    val text = composerText.trim()
    if (text.isEmpty()) return
    composerText = ""
    runtime.sendChatMessage(text)
    selectedDestination = AppDestination.Actions
  }

  fun clearChat() {
    runtime.clearChat()
  }

  fun executeAction(actionId: String) {
    runtime.executeAction(actionId)
    selectedDestination = AppDestination.Actions
  }

  fun approvePendingActions() {
    runtime.approvePendingActions()
    selectedDestination = AppDestination.Safety
  }

  fun updateCommonApps(apps: List<CommonApp>) {
    runtime.updateCommonApps(apps)
  }

  fun noteAppLaunch(
    appLabel: String,
    succeeded: Boolean,
  ) {
    runtime.noteAppLaunch(appLabel = appLabel, succeeded = succeeded)
    selectedDestination = AppDestination.Actions
  }
}

data class MainUiState(
  val currentDestination: AppDestination,
  val draft: String,
  val host: String,
  val port: String,
  val tlsEnabled: Boolean,
  val snapshot: RuntimeSnapshot,
  val commonApps: List<CommonApp>,
  val contextSignals: List<ContextSignal>,
  val systemActions: List<SystemAction>,
  val safetyRecords: List<SafetyRecord>,
  val chatMessages: List<ChatMessage>,
)
