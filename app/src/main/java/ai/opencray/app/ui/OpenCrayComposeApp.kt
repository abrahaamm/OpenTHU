package ai.opencray.app.ui

import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencray.app.MainUiState
import ai.opencray.app.MainViewModel
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.PlanningCard
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
import ai.opencray.app.feature.chat.AgentEvent
import ai.opencray.app.feature.chat.AgentEventOption
import ai.opencray.app.feature.chat.AgentEventType
import ai.opencray.app.feature.chat.ChatMessage
import ai.opencray.app.feature.chat.ChatRole
import ai.opencray.app.ui.drawer.OpenCrayDrawerMenu
import ai.opencray.app.ui.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OpenCrayComposeApp(
  viewModel: MainViewModel,
  selectedFileName: String,
  onAttachClick: () -> Unit,
  onClearAttachment: () -> Unit,
  onSendMessage: (String) -> Unit,
  onLaunchLearnCookieLogin: (String) -> Unit,
) {
  var state by remember { mutableStateOf(viewModel.getUiState()) }
  val scope = rememberCoroutineScope()
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

  fun refresh() {
    state = viewModel.getUiState()
  }

  LaunchedEffect(Unit) {
    while (true) {
      refresh()
      delay(150L)
    }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    gesturesEnabled = true,
    scrimColor = Color(0x33000000),
    drawerContent = {
      OpenCrayDrawerMenu(
        state = state,
        onDestinationSelected = { destination ->
          viewModel.selectDestination(destination)
          refresh()
          scope.launch { drawerState.close() }
        },
        onCreateConversation = {
          viewModel.createConversation()
          refresh()
          scope.launch { drawerState.close() }
        },
        onConversationSelected = { conversationId ->
          viewModel.selectConversation(conversationId)
          refresh()
          scope.launch { drawerState.close() }
        },
        onDeleteConversation = { conversationId ->
          viewModel.deleteConversation(conversationId)
          refresh()
        },
      )
    },
  ) {
    OpenCrayMainSurface(
      state = state,
      selectedFileName = selectedFileName,
      onMenuClick = { scope.launch { drawerState.open() } },
      onSend = { text ->
        onSendMessage(text)
        onClearAttachment()
        refresh()
      },
      onAttachClick = onAttachClick,
      onClearAttachment = onClearAttachment,
      onRetry = { prompt ->
        viewModel.sendChatMessage(prompt)
        refresh()
      },
      onMovePlanningCard = { cardId, offset ->
        viewModel.movePlanningCard(cardId, offset)
        refresh()
      },
      onDeletePlanningCard = { cardId ->
        viewModel.deletePlanningCard(cardId)
        refresh()
      },
      onRunAgentPlan = {
        viewModel.runAgentPlan()
        refresh()
      },
      onExecuteAction = { actionId ->
        viewModel.executeAction(actionId)
        refresh()
      },
      onSnoozeAction = { actionId ->
        viewModel.snoozeAction(actionId)
        refresh()
      },
      onIgnoreAction = { actionId ->
        viewModel.ignoreAction(actionId)
        refresh()
      },
      onApproveActions = {
        viewModel.approvePendingActions()
        refresh()
      },
      onResolveConflict = { strategy ->
        viewModel.resolveConflict(strategy)
        refresh()
      },
      onSettingsChange = { settings ->
        viewModel.updateSettings(settings)
        refresh()
      },
      onSaveSettings = {
        val saved = viewModel.persistCurrentSettings()
        refresh()
        saved
      },
      onTestSettings = {
        viewModel.settingsWarnings()
      },
      onConnectGateway = {
        viewModel.connectToGateway()
        refresh()
      },
      onLaunchLearnCookieLogin = onLaunchLearnCookieLogin,
      onToggleCapability = { capabilityId, enabled ->
        viewModel.toggleCapability(capabilityId, enabled)
        refresh()
      },
      onAddPreference = { preference ->
        val saved = viewModel.addPreference(preference)
        refresh()
        saved
      },
      onUpdatePreference = { index, preference ->
        val saved = viewModel.updatePreference(index, preference)
        refresh()
        saved
      },
      onDeletePreference = { index ->
        val deleted = viewModel.deletePreference(index)
        refresh()
        deleted
      },
      onClearAllMemory = {
        val cleared = viewModel.clearAllMemory()
        refresh()
        cleared
      },
      onReconnectGateway = {
        viewModel.reconnectGateway()
        refresh()
      },
      onSubmitAgentDecision = { event, decision ->
        viewModel.submitAgentDecision(
          taskId = event.taskId,
          requestId = event.requestId,
          eventId = event.id,
          decision = decision,
        )
        refresh()
      },
    )
  }
}

@Composable
private fun OpenCrayMainSurface(
  state: MainUiState,
  selectedFileName: String,
  onMenuClick: () -> Unit,
  onSend: (String) -> Unit,
  onAttachClick: () -> Unit,
  onClearAttachment: () -> Unit,
  onRetry: (String) -> Unit,
  onMovePlanningCard: (String, Int) -> Unit,
  onDeletePlanningCard: (String) -> Unit,
  onRunAgentPlan: () -> Unit,
  onExecuteAction: (String) -> Unit,
  onSnoozeAction: (String) -> Unit,
  onIgnoreAction: (String) -> Unit,
  onApproveActions: () -> Unit,
  onResolveConflict: (String) -> Unit,
  onSettingsChange: (ai.opencray.app.SettingsUiState) -> Unit,
  onSaveSettings: () -> Boolean,
  onTestSettings: () -> List<String>,
  onConnectGateway: () -> Unit,
  onLaunchLearnCookieLogin: (String) -> Unit,
  onToggleCapability: (String, Boolean) -> Unit,
  onAddPreference: (String) -> Boolean,
  onUpdatePreference: (Int, String) -> Boolean,
  onDeletePreference: (Int) -> Boolean,
  onClearAllMemory: () -> Boolean,
  onReconnectGateway: () -> Unit,
  onSubmitAgentDecision: (AgentEvent, String) -> Unit,
) {
  var hadNetworkFallback by remember { mutableStateOf(false) }
  var showConnectedCapsule by remember { mutableStateOf(false) }
  val hasNetworkFallbackSignal = hasNetworkFallbackSignal(state)
  val showNetworkFallback = hasNetworkFallbackSignal && recentNetworkFailureCount(state) >= 3
  val connected = isConnectedGatewayStatus(state.snapshot.connectionStatus)
  LaunchedEffect(showNetworkFallback, connected) {
    if (showNetworkFallback) {
      hadNetworkFallback = true
      showConnectedCapsule = false
    } else if (connected && hadNetworkFallback) {
      showConnectedCapsule = true
      hadNetworkFallback = false
      delay(1800L)
      showConnectedCapsule = false
    }
  }
  val connectionCapsuleState =
    when {
      showNetworkFallback -> ConnectionCapsuleState.Disconnected
      showConnectedCapsule -> ConnectionCapsuleState.Connected
      else -> null
    }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(OpenCrayUi.Background),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 14.dp)
          .imePadding(),
    ) {
      TopOpenCrayBar(
        connectionCapsuleState = connectionCapsuleState,
        onMenuClick = onMenuClick,
        onNetworkFallbackClick = onReconnectGateway,
      )
      Spacer(Modifier.height(12.dp))
      when (state.currentDestination) {
        AppDestination.Chat ->
          ChatScreen(
            messages = state.chatMessages,
            selectedFileName = selectedFileName,
            onSend = onSend,
            onAttachClick = onAttachClick,
            onClearAttachment = onClearAttachment,
            onRetry = onRetry,
            onSubmitAgentDecision = onSubmitAgentDecision,
            showPlanningDetails = state.settings.showPlanningDetails,
            modifier = Modifier.weight(1f),
          )
        AppDestination.Planning ->
          PlanningScreen(
            state = state,
            onMoveCard = onMovePlanningCard,
            onDeleteCard = onDeletePlanningCard,
            onRunAgentPlan = onRunAgentPlan,
            onExecuteAction = onExecuteAction,
            onSnoozeAction = onSnoozeAction,
            onIgnoreAction = onIgnoreAction,
            onApproveActions = onApproveActions,
            onResolveConflict = onResolveConflict,
            modifier = Modifier.weight(1f),
          )
        AppDestination.Settings ->
          Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val capabilities = state.snapshot.capabilities.associateBy { it.id }
            SettingsScreen(
              settings = state.settings,
              memoryRecords = state.memoryRecords,
              recentEvents = state.snapshot.recentEvents,
              safetyRecords = state.safetyRecords,
              notificationContextEnabled = capabilities["notification_context"]?.enabled == true,
              safetyGuardEnabled = capabilities["safety_guard"]?.enabled == true,
              onSettingsChange = onSettingsChange,
              onSaveSettings = onSaveSettings,
              onTestSettings = onTestSettings,
              onConnectGateway = onConnectGateway,
              onLaunchLearnCookieLogin = onLaunchLearnCookieLogin,
              onToggleCapability = onToggleCapability,
              onAddPreference = onAddPreference,
              onUpdatePreference = onUpdatePreference,
              onDeletePreference = onDeletePreference,
              onClearAllMemory = onClearAllMemory,
            )
          }
      }
    }

  }
}

@Composable
private fun TopOpenCrayBar(
  connectionCapsuleState: ConnectionCapsuleState?,
  onMenuClick: () -> Unit,
  onNetworkFallbackClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SliderLikeIconButton(onClick = onMenuClick)
    Spacer(Modifier.width(10.dp))
    if (connectionCapsuleState != null) {
      ConnectionStatusCapsule(state = connectionCapsuleState, onClick = onNetworkFallbackClick)
      Spacer(Modifier.width(10.dp))
    }
    Spacer(Modifier.weight(1f))
    Text(
      text = "OpenTHU",
      color = OpenCrayUi.MenuBrandPurple,
      fontSize = 34.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Cursive,
      style = TextStyle(shadow = Shadow(Color(0x227A35D8), Offset(0f, 3f), 8f)),
    )
  }
}

private enum class ConnectionCapsuleState {
  Disconnected,
  Connected,
}

@Composable
private fun SliderLikeIconButton(onClick: () -> Unit) {
  Box(
    modifier =
      Modifier
        .size(52.dp)
        .sliderLikeSurface()
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Outlined.Menu,
      contentDescription = "打开菜单",
      tint = OpenCrayUi.BrandPurple,
      modifier = Modifier.size(29.dp),
    )
  }
}

@Composable
private fun ConnectionStatusCapsule(
  state: ConnectionCapsuleState,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val connected = state == ConnectionCapsuleState.Connected
  Box(
    modifier =
      modifier
        .connectionStatusSurface(connected = connected)
        .clip(RoundedCornerShape(999.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp),
  ) {
    Text(
      text = if (connected) "已连接" else "网络未连接",
      color = if (connected) OpenCrayUi.Success.copy(alpha = 0.92f) else OpenCrayUi.Danger.copy(alpha = 0.92f),
      fontSize = 12.sp,
      lineHeight = 15.sp,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun ChatScreen(
  messages: List<ChatMessage>,
  selectedFileName: String,
  onSend: (String) -> Unit,
  onAttachClick: () -> Unit,
  onClearAttachment: () -> Unit,
  onRetry: (String) -> Unit,
  onSubmitAgentDecision: (AgentEvent, String) -> Unit,
  showPlanningDetails: Boolean,
  modifier: Modifier = Modifier,
) {
  var input by remember { mutableStateOf("") }
  val hiddenAssistantIds = remember { mutableStateMapOf<String, Boolean>() }
  val visibleMessages = messages.filterNot { hiddenAssistantIds[it.id] == true || shouldHideNetworkFallbackMessage(it) }
  val latestAssistantMessageId = visibleMessages.lastOrNull { it.role == ChatRole.Assistant }?.id
  val hasPendingDecision =
    visibleMessages.any { message ->
      message.events.any { event ->
        (event.type == AgentEventType.ConfirmationRequired || event.type == AgentEventType.PermissionRequired) &&
          isDecisionPending(event.status)
      }
    }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val showScrollToBottom by remember {
    derivedStateOf {
      val total = listState.layoutInfo.totalItemsCount
      if (total <= 0) {
        false
      } else {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        lastVisible < total - 1
      }
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        items(visibleMessages, key = { it.id }) { message ->
          val originalIndex = messages.indexOfFirst { it.id == message.id }
          val retryPrompt =
            messages
              .take(originalIndex.coerceAtLeast(0))
              .lastOrNull { it.role == ChatRole.User }
              ?.text
              .orEmpty()
          ChatMessageItem(
            message = message,
            retryPrompt = retryPrompt,
            showAssistantFooter = message.id == latestAssistantMessageId && !hasPendingDecision,
            showPlanningDetails = showPlanningDetails,
            onRetry = {
              if (message.role == ChatRole.Assistant && retryPrompt.isNotBlank()) {
                hiddenAssistantIds[message.id] = true
                onRetry(retryPrompt)
              }
            },
            onSubmitAgentDecision = onSubmitAgentDecision,
          )
        }
      }

      if (showScrollToBottom) {
        ScrollToBottomButton(
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = 10.dp),
          onClick = {
            scope.launch {
              val lastIndex = (visibleMessages.size - 1).coerceAtLeast(0)
              listState.animateScrollToItem(lastIndex)
            }
          },
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    ChatComposer(
      text = input,
      selectedFileName = selectedFileName,
      onTextChange = { input = it },
      onAttachClick = onAttachClick,
      onClearAttachment = onClearAttachment,
      onSend = {
        val trimmed = input.trim()
        if (trimmed.isNotBlank()) {
          onSend(trimmed)
          input = ""
        }
      },
    )
  }
}

private fun shouldHideNetworkFallbackMessage(message: ChatMessage): Boolean {
  if (message.role != ChatRole.System) return false
  val text = message.text
  return text.contains("Agent-Core", ignoreCase = true) &&
    (
      text.contains("NETWORK_ERROR", ignoreCase = true) ||
        text.contains("连接成功") ||
        text.contains("连接失败") ||
        text.contains("lastResultCode") ||
        text.contains("杩炴帴鎴愬姛") ||
        text.contains("杩炴帴澶辫触")
    )
}

private fun isConnectedGatewayStatus(status: String): Boolean =
  status.contains("已连接") ||
    status.contains("Connected", ignoreCase = true) ||
    status.contains("宸茶繛")

private fun hasNetworkFallbackSignal(state: MainUiState): Boolean {
  val status = state.snapshot.connectionStatus
  if (isConnectedGatewayStatus(status)) return false
  val statusLooksDisconnected =
    status.contains("未连接") ||
      status.contains("failed", ignoreCase = true) ||
      status.contains("NETWORK_ERROR", ignoreCase = true) ||
      status.contains("鏈嶅姟鍣ㄦ湭")
  val recentNetworkFailure =
    state.snapshot.recentEvents.any { event ->
      event.contains("NETWORK_ERROR", ignoreCase = true) ||
        event.contains("Gateway register failed", ignoreCase = true) ||
        event.contains("Gateway plan failed", ignoreCase = true)
    }
  return statusLooksDisconnected || recentNetworkFailure
}

private fun recentNetworkFailureCount(state: MainUiState): Int {
  val status = state.snapshot.connectionStatus
  val statusFailure =
    status.contains("NETWORK_ERROR", ignoreCase = true) ||
      status.contains("failed", ignoreCase = true) ||
      status.contains("连接失败") ||
      status.contains("未连接") ||
      status.contains("鏈嶅姟鍣ㄦ湭")
  val eventFailures =
    state.snapshot.recentEvents.count { event ->
      event.contains("NETWORK_ERROR", ignoreCase = true) ||
        event.contains("Gateway register failed", ignoreCase = true) ||
        event.contains("Gateway plan failed", ignoreCase = true) ||
        event.contains("Gateway dispatch failed", ignoreCase = true) ||
        event.contains("Gateway result failed", ignoreCase = true) ||
        event.contains("timeout", ignoreCase = true) ||
        event.contains("unexpected end of stream", ignoreCase = true)
    }
  return eventFailures + if (statusFailure) 1 else 0
}

@Composable
private fun ChatMessageItem(
  message: ChatMessage,
  retryPrompt: String,
  showAssistantFooter: Boolean,
  showPlanningDetails: Boolean,
  onRetry: () -> Unit,
  onSubmitAgentDecision: (AgentEvent, String) -> Unit,
) {
  when (message.role) {
    ChatRole.User -> {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth(0.78f)
              .clip(RoundedCornerShape(14.dp))
              .background(OpenCrayUi.UserBubble)
              .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
          MarkdownText(text = message.text, color = OpenCrayUi.Ink, fontSize = 15, lineHeight = 21)
        }
      }
    }
    ChatRole.Assistant,
    ChatRole.System,
    -> {
      Column(Modifier.fillMaxWidth()) {
        val toolEvents =
          if (showPlanningDetails) {
            message.events.filter { it.type == AgentEventType.ToolCall || it.type == AgentEventType.ToolResult }
          } else {
            emptyList()
          }
        if (toolEvents.isNotEmpty()) {
          ToolProcessBlock(events = toolEvents)
          if (displayableAssistantText(message.text).isNotBlank()) {
            Spacer(Modifier.height(8.dp))
          }
        }
        val confirmationEvents =
          message.events.filter { it.type == AgentEventType.ConfirmationRequired || it.type == AgentEventType.PermissionRequired }
        if (confirmationEvents.isNotEmpty()) {
          ConfirmationRequestBlock(
            events = confirmationEvents,
            onSubmitAgentDecision = onSubmitAgentDecision,
          )
          if (displayableAssistantText(message.text).isNotBlank()) {
            Spacer(Modifier.height(8.dp))
          }
        }
        val displayText = displayableAssistantText(message.text)
        if (displayText.isNotBlank()) {
          MarkdownText(
            text = displayText,
            color = OpenCrayUi.Ink,
            fontSize = 15,
            lineHeight = 23,
          )
        }
        val hasAssistantFinal = message.events.any { it.type == AgentEventType.AssistantFinal }
        if (message.role == ChatRole.Assistant && displayText.isBlank() && !hasAssistantFinal && confirmationEvents.isEmpty()) {
          ThinkingDots()
        }
        val isCompleteAssistantMessage =
          message.role == ChatRole.Assistant &&
            showAssistantFooter &&
            displayText.isNotBlank() &&
            (message.events.isEmpty() || hasAssistantFinal)
        if (isCompleteAssistantMessage) {
          Spacer(Modifier.height(9.dp))
          AssistantActionStrip(
            answer = message.text,
            retryEnabled = retryPrompt.isNotBlank(),
            onRetry = onRetry,
          )
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Text(
              text = "OpenTHU",
              modifier = Modifier.padding(top = 2.dp, start = 2.dp),
              color = OpenCrayUi.MenuBrandPurple.copy(alpha = 0.72f),
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Cursive,
              maxLines = 1,
              overflow = TextOverflow.Clip,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ToolProcessBlock(events: List<AgentEvent>) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    events.forEach { event ->
      Text(
        text = toolProcessText(event),
        color = OpenCrayUi.Muted.copy(alpha = 0.82f),
        fontSize = 12.sp,
        lineHeight = 17.sp,
      )
    }
  }
}

@Composable
private fun ConfirmationRequestBlock(
  events: List<AgentEvent>,
  onSubmitAgentDecision: (AgentEvent, String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    events.forEach { event ->
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .confirmationRequestSurface()
            .padding(horizontal = 12.dp, vertical = 10.dp),
      ) {
        Text(
          text = confirmationTitle(event),
          color = OpenCrayUi.BrandPurple,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          lineHeight = 18.sp,
        )
        val detail = event.content.ifBlank { event.title }.trim()
        if (detail.isNotBlank()) {
          Spacer(Modifier.height(5.dp))
          Text(
            text = detail,
            color = OpenCrayUi.Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
          )
        }
        Spacer(Modifier.height(9.dp))
        val pending = isDecisionPending(event.status)
        if (pending) {
          Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            decisionOptions(event).forEach { option ->
              val primary = isApproveDecision(option.value)
              ConfirmationDecisionButton(
                text = option.label.ifBlank { if (primary) "确认" else "拒绝" },
                primary = primary,
                onClick = { onSubmitAgentDecision(event, option.value.ifBlank { if (primary) "approve" else "reject" }) },
              )
            }
          }
        } else {
          Text(
            text = confirmationStatusText(event.status),
            color = if (event.status.equals("approved", ignoreCase = true)) OpenCrayUi.Success else OpenCrayUi.Danger,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

@Composable
private fun ConfirmationDecisionButton(
  text: String,
  primary: Boolean,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .height(34.dp)
        .confirmationButtonSurface(primary = primary)
        .clip(RoundedCornerShape(999.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (primary) OpenCrayUi.Ink else OpenCrayUi.Danger,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

private fun confirmationTitle(event: AgentEvent): String {
  val name = event.skillName.ifBlank { event.title }.ifBlank { "高危动作" }
  return when (event.type) {
    AgentEventType.PermissionRequired -> "需要权限确认：$name"
    else -> "执行前需要确认：$name"
  }
}

private fun decisionOptions(event: AgentEvent): List<AgentEventOption> {
  val options = event.options.filter { it.value.isNotBlank() || it.label.isNotBlank() }
  if (options.isNotEmpty()) return options
  return listOf(
    AgentEventOption(label = "确认", value = "approve"),
    AgentEventOption(label = "拒绝", value = "reject"),
  )
}

private fun isApproveDecision(value: String): Boolean {
  val normalized = value.lowercase()
  return normalized in setOf("approve", "approved", "allow", "allowed", "confirm", "yes", "true")
}

private fun isDecisionPending(status: String): Boolean {
  val normalized = status.lowercase()
  return normalized.isBlank() ||
    normalized in setOf("pending", "queued", "required", "awaiting_confirmation", "confirmation_required")
}

private fun confirmationStatusText(status: String): String =
  when (status.lowercase()) {
    "submitting" -> "正在提交确认..."
    "approved" -> "已确认"
    "rejected" -> "已拒绝"
    "failed" -> "确认提交失败"
    else -> status.ifBlank { "等待确认" }
  }

private fun toolProcessText(event: AgentEvent): String {
  val name = event.skillName.ifBlank { event.title }.ifBlank { "未知工具" }
  val status = event.status.ifBlank { "running" }
  val content = event.content.replace(Regex("\\s+"), " ").take(96)
  val suffix = if (content.isBlank()) "" else " · $content"
  return when (event.type) {
    AgentEventType.ToolCall -> "调用工具：$name · $status$suffix"
    AgentEventType.ToolResult -> "工具结果：$name · $status$suffix"
    else -> "$name · $status$suffix"
  }
}

private fun displayableAssistantText(text: String): String =
  text
    .replace("鈥?", "")
    .replace("�", "")
    .replace("我先理解你的意思。", "")
    .replace("我先理解你的意思", "")
    .replace("…", "")
    .trim()

@Composable
private fun ThinkingDots() {
  val transition = rememberInfiniteTransition(label = "thinkingDots")
  val phase by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1200, easing = LinearEasing)),
    label = "thinkingDotsPhase",
  )
  Row(
    modifier = Modifier.padding(start = 2.dp, top = 3.dp, bottom = 3.dp),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(3) { index ->
      val progress = dotPulseProgress(phase, index)
      val size = 5.5f + progress * 4.5f
      val alpha = 0.42f + progress * 0.5f
      Box(
        modifier =
          Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(OpenCrayUi.MenuBrandPurple.copy(alpha = alpha)),
      )
    }
  }
}

private fun dotPulseProgress(
  phase: Float,
  index: Int,
): Float {
  val shifted = (phase - index * 0.18f + 1f) % 1f
  val wave =
    when {
      shifted < 0.5f -> shifted * 2f
      else -> (1f - shifted) * 2f
    }
  return wave.coerceIn(0f, 1f)
}

@Composable
private fun MarkdownText(
  text: String,
  color: Color,
  fontSize: Int,
  lineHeight: Int,
) {
  val uriHandler = LocalUriHandler.current
  val annotated = remember(text) { markdownRichText(text) }
  ClickableText(
    text = annotated,
    style =
      TextStyle(
        color = color,
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
      ),
    onClick = { offset ->
      annotated
        .getStringAnnotations(tag = MarkdownUrlTag, start = offset, end = offset)
        .firstOrNull()
        ?.let { annotation ->
          runCatching { uriHandler.openUri(annotation.item) }
        }
    },
  )
}

private const val MarkdownUrlTag = "URL"

private fun markdownRichText(text: String): AnnotatedString =
  buildAnnotatedString {
    val linkRegex = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    var index = 0
    linkRegex.findAll(text).forEach { match ->
      val start = match.range.first
      if (start > index) {
        appendMarkdownBold(text.substring(index, start))
      }
      val label = match.groupValues[1]
      val url = normalizeMarkdownUrl(match.groupValues[2])
      pushStringAnnotation(tag = MarkdownUrlTag, annotation = url)
      withStyle(
        SpanStyle(
          color = OpenCrayUi.MenuBrandPurple,
          fontWeight = FontWeight.SemiBold,
          textDecoration = TextDecoration.Underline,
        ),
      ) {
        appendMarkdownBold(label)
      }
      pop()
      index = match.range.last + 1
    }
    if (index < text.length) {
      appendMarkdownBold(text.substring(index))
    }
  }

private fun normalizeMarkdownUrl(raw: String): String {
  val value = raw.trim()
  return when {
    value.startsWith("http://", ignoreCase = true) -> value
    value.startsWith("https://", ignoreCase = true) -> value
    value.startsWith("mailto:", ignoreCase = true) -> value
    else -> "https://$value"
  }
}

private fun AnnotatedString.Builder.appendMarkdownBold(text: String) {
  var index = 0
  while (index < text.length) {
    val start = text.indexOf("**", index)
    val singleStart = text.indexOf("*", index)
    val markerStart =
      when {
        singleStart < 0 -> -1
        start < 0 -> singleStart
        else -> minOf(start, singleStart)
      }
    if (markerStart < 0) {
      append(text.substring(index))
      break
    }
    append(text.substring(index, markerStart))
    val delimiterLength = boldDelimiterLength(text, markerStart)
    val contentStart = markerStart + delimiterLength
    if (contentStart >= text.length || text[contentStart].isWhitespace()) {
      append(text[markerStart])
      index = markerStart + 1
      continue
    }
    val end = findBoldEnd(text, contentStart, delimiterLength)
    if (end < 0) {
      append(text.substring(markerStart))
      break
    }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
      append(text.substring(contentStart, end))
    }
    index = end + delimiterLength
  }
}

private fun boldDelimiterLength(text: String, start: Int): Int {
  var count = 0
  while (start + count < text.length && text[start + count] == '*') {
    count += 1
  }
  return when {
    count >= 4 -> 4
    count >= 2 -> 2
    else -> 1
  }
}

private fun findBoldEnd(
  text: String,
  start: Int,
  delimiterLength: Int,
): Int {
  val delimiter = "*".repeat(delimiterLength)
  var index = text.indexOf(delimiter, start)
  while (index >= 0) {
    val previous = text.getOrNull(index - 1)
    if (previous != null && !previous.isWhitespace()) {
      return index
    }
    index = text.indexOf(delimiter, index + delimiterLength)
  }
  return -1
}

@Composable
private fun AssistantActionStrip(
  answer: String,
  retryEnabled: Boolean,
  onRetry: () -> Unit,
) {
  val clipboard = LocalClipboardManager.current
  var feedback by remember(answer) { mutableStateOf<String?>(null) }
  Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
    LineIconButton(onClick = { clipboard.setText(AnnotatedString(answer)) }) {
      Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", modifier = Modifier.size(16.dp))
    }
    LineIconButton(selected = feedback == "up", onClick = { feedback = if (feedback == "up") null else "up" }) {
      Icon(Icons.Outlined.ThumbUp, contentDescription = "点赞", modifier = Modifier.size(16.dp))
    }
    LineIconButton(selected = feedback == "down", onClick = { feedback = if (feedback == "down") null else "down" }) {
      Icon(Icons.Outlined.ThumbDown, contentDescription = "踩", modifier = Modifier.size(16.dp))
    }
    LineIconButton(enabled = retryEnabled, onClick = onRetry) {
      Icon(Icons.Outlined.Refresh, contentDescription = "重来", modifier = Modifier.size(17.dp))
    }
  }
}

@Composable
private fun LineIconButton(
  selected: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .size(29.dp)
        .then(if (selected) Modifier.selectedIconGlow() else Modifier)
        .clip(RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    androidx.compose.runtime.CompositionLocalProvider(
      androidx.compose.material3.LocalContentColor provides
        when {
          !enabled -> OpenCrayUi.Muted.copy(alpha = 0.38f)
          selected -> OpenCrayUi.Yellow
          else -> OpenCrayUi.Muted
        },
    ) {
      content()
    }
  }
}

@Composable
private fun ScrollToBottomButton(
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      modifier
        .size(42.dp)
        .scrollToBottomSurface()
        .clip(RoundedCornerShape(999.dp))
        .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Outlined.KeyboardArrowDown,
      contentDescription = "滑到底部",
      tint = OpenCrayUi.BrandPurple,
      modifier = Modifier.size(25.dp),
    )
  }
}

@Composable
private fun ChatComposer(
  text: String,
  selectedFileName: String,
  onTextChange: (String) -> Unit,
  onAttachClick: () -> Unit,
  onClearAttachment: () -> Unit,
  onSend: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .composerSurface()
        .padding(10.dp),
  ) {
    if (selectedFileName.isNotBlank()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = selectedFileName,
          color = OpenCrayUi.BrandPurple,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = "移除",
          color = OpenCrayUi.Muted,
          fontSize = 12.sp,
          modifier = Modifier.clickable(onClick = onClearAttachment).padding(6.dp),
        )
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onAttachClick),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Outlined.AttachFile, contentDescription = "附件", tint = OpenCrayUi.Muted)
      }
      BasicTextField(
        value = text,
        onValueChange = onTextChange,
        textStyle = TextStyle(color = OpenCrayUi.Ink, fontSize = 16.sp),
        modifier = Modifier.weight(1f).height(48.dp).padding(horizontal = 8.dp),
        decorationBox = { inner ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            if (text.isBlank()) {
              Text("Message OpenTHU...", color = OpenCrayUi.Muted.copy(alpha = 0.72f), fontSize = 16.sp)
            }
            inner()
          }
        },
      )
      Box(
        modifier = Modifier.size(48.dp).sliderLikeSurface().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSend),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Outlined.Send, contentDescription = "发送", tint = OpenCrayUi.BrandPurple, modifier = Modifier.size(25.dp))
      }
    }
  }
}

@Composable
private fun PlanningScreen(
  state: MainUiState,
  onMoveCard: (String, Int) -> Unit,
  onDeleteCard: (String) -> Unit,
  onRunAgentPlan: () -> Unit,
  onExecuteAction: (String) -> Unit,
  onSnoozeAction: (String) -> Unit,
  onIgnoreAction: (String) -> Unit,
  onApproveActions: () -> Unit,
  onResolveConflict: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showMemoryPanel by remember { mutableStateOf(false) }

  LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { PlanningOverview(state = state, onMemoryClick = { showMemoryPanel = !showMemoryPanel }) }
    if (showMemoryPanel) {
      item { MemoryInspectorSection(memoryRecords = state.memoryRecords) }
    }
    item {
      Column {
        SectionTitle("规划卡片")
        Spacer(Modifier.height(10.dp))
        PlanningCardsSection(
          cards = state.planningCards,
          onMoveCard = onMoveCard,
          onDeleteCard = onDeleteCard,
        )
      }
    }
  }
}

@Composable
private fun PlanningOverview(
  state: MainUiState,
  onMemoryClick: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .planningStatsSurface()
        .padding(14.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MetricTile("任务", state.tasks.size.toString(), Modifier.weight(1f))
      MetricTile("动作", state.systemActions.size.toString(), Modifier.weight(1f))
      MetricTile("提醒", state.safetyRecords.size.toString(), Modifier.weight(1f))
      MetricTile("记忆", state.memoryRecords.size.toString(), Modifier.weight(1f), onClick = onMemoryClick)
    }
  }
}

@Composable
private fun MetricTile(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  val tileModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
  Column(
    modifier =
      tileModifier
        .metricTileSurface()
        .padding(10.dp),
  ) {
    Text(label, color = OpenCrayUi.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(value, color = OpenCrayUi.BrandPurple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun MemoryInspectorSection(memoryRecords: List<MemoryRecord>) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .planningCardSurface()
        .padding(14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      SectionTitle("系统记忆")
      Spacer(Modifier.weight(1f))
      Text(
        text = "${memoryRecords.size} 条",
        color = OpenCrayUi.BrandPurple,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
    }
    Spacer(Modifier.height(10.dp))
    if (memoryRecords.isEmpty()) {
      Text(
        text = "暂无记忆。发送一条消息后，系统会立即写入短期记忆。",
        color = OpenCrayUi.Muted,
        fontSize = 13.sp,
        lineHeight = 19.sp,
      )
      return@Column
    }

    MemoryScopeBlock(
      title = "短期记忆",
      description = "最近用户目标，用于当前几轮对话和任务规划。",
      records = memoryRecords.filter { it.scope.equals("short", ignoreCase = true) },
    )
    Spacer(Modifier.height(10.dp))
    MemoryScopeBlock(
      title = "中期记忆",
      description = "近期校园焦点、动作反馈和可复用上下文。",
      records = memoryRecords.filter { it.scope.equals("mid", ignoreCase = true) },
    )
    Spacer(Modifier.height(10.dp))
    MemoryScopeBlock(
      title = "长期偏好",
      description = "用户明确保存或系统识别出的稳定偏好。",
      records = memoryRecords.filter { it.scope.equals("long", ignoreCase = true) },
    )
  }
}

@Composable
private fun MemoryScopeBlock(
  title: String,
  description: String,
  records: List<MemoryRecord>,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .metricTileSurface()
        .padding(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, color = OpenCrayUi.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(8.dp))
      Text("${records.size}", color = OpenCrayUi.BrandPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(3.dp))
    Text(description, color = OpenCrayUi.Muted, fontSize = 11.sp, lineHeight = 16.sp)
    Spacer(Modifier.height(8.dp))
    if (records.isEmpty()) {
      Text("暂无", color = OpenCrayUi.Muted, fontSize = 12.sp)
    } else {
      records
        .sortedByDescending { it.updatedAtEpochMs }
        .take(8)
        .forEach { record ->
          MemoryRecordRow(record)
          Spacer(Modifier.height(7.dp))
        }
    }
  }
}

@Composable
private fun MemoryRecordRow(record: MemoryRecord) {
  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = record.key,
        color = OpenCrayUi.BrandPurple,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "w${record.weight} · ${formatMemoryAge(record.updatedAtEpochMs)}",
        color = OpenCrayUi.Muted,
        fontSize = 11.sp,
      )
    }
    Spacer(Modifier.height(3.dp))
    Text(
      text = record.value,
      color = OpenCrayUi.Ink,
      fontSize = 12.sp,
      lineHeight = 17.sp,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun formatMemoryAge(updatedAtEpochMs: Long): String {
  val elapsedMs = (System.currentTimeMillis() - updatedAtEpochMs).coerceAtLeast(0L)
  val minute = 60_000L
  val hour = 60L * minute
  val day = 24L * hour
  return when {
    elapsedMs < minute -> "刚刚"
    elapsedMs < hour -> "${elapsedMs / minute} 分钟前"
    elapsedMs < day -> "${elapsedMs / hour} 小时前"
    else -> "${elapsedMs / day} 天前"
  }
}

@Composable
private fun PlanningFocus(state: MainUiState) {
  PlanningSection(title = "当前焦点") {
    Text(
      text =
        when {
          state.tasks.isNotEmpty() -> "优先关注：${state.tasks.first().goal.take(42)}"
          state.systemActions.isNotEmpty() -> "优先关注：${state.systemActions.first().title}"
          state.contextSignals.isNotEmpty() -> "优先关注：${state.contextSignals.first().title}"
          else -> "当前还没有明确焦点，可以先回到对话页描述你的目标。"
        },
      color = OpenCrayUi.Ink,
      fontSize = 14.sp,
      lineHeight = 20.sp,
    )
  }
}

@Composable
private fun PlanningCardsSection(
  cards: List<PlanningCard>,
  onMoveCard: (String, Int) -> Unit,
  onDeleteCard: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (cards.isEmpty()) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .planningCardSurface()
            .padding(14.dp),
      ) {
        Text(
          text = "还没有规划卡片。调用闹钟、待办、课表等能力，或在对话中说“创建一个卡片：……”即可生成。",
          color = OpenCrayUi.Muted,
          fontSize = 13.sp,
          lineHeight = 19.sp,
        )
      }
    } else {
      cards.forEachIndexed { index, card ->
        PlanningCardItem(
          card = card,
          canMoveUp = index > 0,
          canMoveDown = index < cards.lastIndex,
          onMoveUp = { onMoveCard(card.id, -1) },
          onMoveDown = { onMoveCard(card.id, 1) },
          onDelete = { onDeleteCard(card.id) },
        )
      }
    }
  }
}

@Composable
private fun PlanningContextSection(state: MainUiState) {
  PlanningSection(title = "情境与线索") {
    Text(
      text =
        buildString {
          append("校园资讯\n")
          append("• 教务通知：课程 DDL 与考试安排会在这里汇总。\n")
          append("• 校园活动：讲座、社团与志愿活动会按兴趣整理。\n")
          append("• 课程与日程：当前共有 ${state.tasks.size} 个任务，${state.memoryRecords.size} 条记忆。\n")
          append("• 待处理动作：${state.systemActions.count { it.status != "executed" }} 个")
        },
      color = OpenCrayUi.Ink,
      fontSize = 14.sp,
      lineHeight = 20.sp,
    )
    Spacer(Modifier.height(12.dp))
    Text("规划上下文", color = OpenCrayUi.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
      text =
        buildString {
          append("规划说明\n")
          append("• 新收到的通知和网页内容会先进入结构化解析。\n")
          append("• 涉及日历或跨应用操作时，会先解释原因再等待确认。\n")
          append("• 执行结果、失败记录和回滚入口会同步到这里。")
          if (state.contextSignals.isNotEmpty()) {
            append("\n\n最近上下文\n")
            append(
              state.contextSignals
                .take(5)
                .joinToString("\n\n") { signal -> "${signal.title}\n${signal.detail}\n来源：${signal.source}" },
            )
          }
        },
      color = OpenCrayUi.Muted,
      fontSize = 13.sp,
      lineHeight = 18.sp,
    )
  }
}

@Composable
private fun PlanningMiniSection(
  title: String,
  body: String,
  secondaryBody: String,
  modifier: Modifier = Modifier,
) {
  PlanningSection(title = title, modifier = modifier) {
    Text(body, color = OpenCrayUi.Muted, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(12.dp))
    Text(secondaryBody, color = OpenCrayUi.Ink, fontSize = 13.sp, lineHeight = 18.sp)
  }
}

@Composable
private fun PlanningFlowSection() {
  PlanningSection(title = "执行流程") {
    Text("执行流程详情", color = OpenCrayUi.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
      text =
        buildString {
          append("1. 收集上下文和用户目标。\n")
          append("2. 生成候选动作并完成安全审查。\n")
          append("3. 高风险动作等待用户确认，低风险动作可自动执行。\n")
          append("4. 结果回写到对话、规划和记忆模块。")
        },
      color = OpenCrayUi.Muted,
      fontSize = 13.sp,
      lineHeight = 18.sp,
    )
  }
}

@Composable
private fun ActionsPanel(
  state: MainUiState,
  focusedAction: SystemAction?,
  onRunAgentPlan: () -> Unit,
  onExecuteAction: (String) -> Unit,
  onSnoozeAction: (String) -> Unit,
  onIgnoreAction: (String) -> Unit,
  onApproveActions: () -> Unit,
  onResolveConflict: (String) -> Unit,
) {
  PlanningSection(title = "候选动作与执行") {
    val conflict = state.pendingConflict
    Text(
      text =
        if (conflict != null) {
          "当前有一个需要你立即决定的日历冲突。"
        } else {
          when {
            state.systemActions.isNotEmpty() -> "当前共有 ${state.systemActions.size} 个候选动作，优先查看第一个建议并决定是否执行。"
            else -> "当前还没有候选动作。先在对话页描述目标，规划页会在这里生成建议。"
          }
        },
      color = OpenCrayUi.Ink,
      fontSize = 14.sp,
      lineHeight = 20.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text(
      text =
        if (conflict != null) {
          buildString {
            append("检测到日历冲突，请选择处理策略。\n\n")
            append(conflict.conflictMessage)
            append("\n\n• 跳过创建：保留现有事项。\n")
            append("• 共存：忽略冲突，直接写入。\n")
            append("• 删除冲突事项：清理后再写入。")
          }
        } else {
          state.systemActions.joinToString(separator = "\n\n") { action ->
            val approval = if (action.requiresApproval) "需要确认" else "可自动执行"
            val result = action.lastResult ?: "尚未执行"
            val priority = recommendationTier(action)
            "${action.title}\n${action.summary}\n推荐：$priority · 风险：${action.riskLevel} · $approval · 状态：${action.status}\n原因：${action.explain}\n结果：$result"
          }.ifBlank { "当前还没有可执行动作。先在对话页描述你的目标，我们会在这里生成规划建议。" }
        },
      color = OpenCrayUi.Ink,
      fontSize = 13.sp,
      lineHeight = 18.sp,
    )
    Spacer(Modifier.height(12.dp))
    PlanningWideButton(text = "执行已确认动作", primary = true, enabled = true, onClick = onRunAgentPlan)
    Spacer(Modifier.height(10.dp))
    if (conflict != null) {
      PlanningWideButton(text = "跳过创建", primary = true, enabled = true) { onResolveConflict("skip_write") }
      Spacer(Modifier.height(10.dp))
      PlanningWideButton(text = "共存", enabled = true) { onResolveConflict("coexist") }
      Spacer(Modifier.height(10.dp))
      PlanningWideButton(text = "删除冲突事项", enabled = true) { onResolveConflict("delete_conflicts") }
    } else {
      PlanningWideButton(
        text = focusedAction?.title ?: "暂无可执行建议",
        primary = true,
        enabled = focusedAction != null,
      ) {
        focusedAction?.let { onExecuteAction(it.id) }
      }
      Spacer(Modifier.height(10.dp))
      PlanningWideButton(text = "稍后处理", enabled = focusedAction != null) {
        focusedAction?.let { onSnoozeAction(it.id) }
      }
      Spacer(Modifier.height(10.dp))
      PlanningWideButton(text = "忽略建议", enabled = focusedAction != null) {
        focusedAction?.let { onIgnoreAction(it.id) }
      }
    }
    Spacer(Modifier.height(10.dp))
    PlanningWideButton(text = "确认高风险动作", enabled = true, onClick = onApproveActions)
  }
}

@Composable
private fun SafetyAndEventsSection(state: MainUiState) {
  PlanningSection(title = "运行反馈") {
    Text(
      text =
        buildString {
          append("安全审查与解释记录\n")
          val content =
            state.safetyRecords
              .take(12)
              .joinToString(separator = "\n\n") { record -> "${record.title}\n${record.detail}\n状态：${record.status}" }
              .ifBlank { "暂无安全记录。" }
          append(content)
        },
      color = OpenCrayUi.Ink,
      fontSize = 13.sp,
      lineHeight = 18.sp,
    )
    Spacer(Modifier.height(12.dp))
    Text(
      text =
        state.snapshot.recentEvents
          .take(20)
          .joinToString(separator = "\n• ", prefix = "• ")
          .ifBlank { "• 暂无最近事件。" },
      color = OpenCrayUi.Muted,
      fontSize = 13.sp,
      lineHeight = 18.sp,
    )
  }
}

@Composable
private fun PlanningCardItem(
  card: PlanningCard,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onDelete: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .planningCardSurface()
        .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 12.dp),
  ) {
    Text(
      text = card.title,
      color = OpenCrayUi.Ink,
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      lineHeight = 20.sp,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      PlanningPill(text = planningCardTypeLabel(card.type), color = OpenCrayUi.BrandPurple)
      Spacer(Modifier.width(6.dp))
      PlanningPill(text = planningCardStatusLabel(card.status), color = planningStatusColor(card.status))
      Spacer(Modifier.width(8.dp))
      Text(
        text = card.source.ifBlank { "规划页" },
        color = OpenCrayUi.Muted,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
    if (card.body.isNotBlank()) {
      Spacer(Modifier.height(8.dp))
      Text(
        text = card.body,
        color = OpenCrayUi.Muted,
        fontSize = 13.sp,
        lineHeight = 18.sp,
      )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      PlanningCardButton(text = "上移", enabled = canMoveUp, onClick = onMoveUp, modifier = Modifier.weight(1f))
      PlanningCardButton(text = "下移", enabled = canMoveDown, onClick = onMoveDown, modifier = Modifier.weight(1f))
      PlanningCardButton(text = "删除", enabled = true, onClick = onDelete, modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun PlanningPill(
  text: String,
  color: Color,
) {
  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(999.dp))
        .background(Color.White.copy(alpha = 0.82f))
        .padding(horizontal = 8.dp, vertical = 4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

@Composable
private fun PlanningCardButton(
  text: String,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .height(38.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(if (enabled) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.36f))
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (enabled) OpenCrayUi.Ink else OpenCrayUi.Muted.copy(alpha = 0.48f),
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun PlanningSection(
  title: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .planningCardSurface()
        .padding(14.dp),
  ) {
    SectionTitle(title)
    Spacer(Modifier.height(10.dp))
    content()
  }
}

@Composable
private fun SectionTitle(title: String) {
  Text(
    text = title,
    color = OpenCrayUi.Ink,
    fontSize = 16.sp,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
private fun PlanningWideButton(
  text: String,
  enabled: Boolean,
  primary: Boolean = false,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(44.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(
          when {
            !enabled -> Color.White.copy(alpha = 0.35f)
            primary -> OpenCrayUi.BrandPurple
            else -> Color.White.copy(alpha = 0.82f)
          },
        )
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color =
        when {
          !enabled -> OpenCrayUi.Muted.copy(alpha = 0.5f)
          primary -> Color.White
          else -> OpenCrayUi.Ink
        },
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun planningScheduleText(state: MainUiState): String =
  buildString {
    append("课程线索\n")
    val content =
      state.contextSignals
        .filter { it.title.contains("课程") || it.title.contains("课表") || it.detail.contains("课程") }
        .take(5)
        .joinToString("\n") { "• ${it.title}" }
        .ifBlank { "• 暂无课程数据，等待课程或校历能力返回。" }
    append(content)
  }

private fun planningAlarmText(state: MainUiState): String =
  buildString {
    append("提醒状态\n")
    val content =
      state.systemActions
        .filter { it.id.contains("alarm") || it.title.contains("提醒") || it.title.contains("闹钟") }
        .take(5)
        .joinToString("\n") { "• ${it.title} · ${it.status}" }
        .ifBlank { "• 暂无提醒任务，可从规划动作中添加。" }
    append(content)
  }

private fun planningTodoText(state: MainUiState): String =
  buildString {
    append("待办摘要\n")
    val content =
      state.tasks
        .take(6)
        .joinToString("\n") { "• ${it.goal.take(30)} · ${it.status}" }
        .ifBlank { "• 暂无待办任务，先在对话页提交目标。" }
    append(content)
  }

private fun homeQuickActionsText(): String =
  buildString {
    append("快捷入口\n")
    append("• 课程 DDL：查看即将到期的作业。\n")
    append("• 加入日历：把行动转成日程。\n")
    append("• 整理通知：聚合系统消息。")
  }

private fun focusedAction(actions: List<SystemAction>): SystemAction? =
  actions.firstOrNull { it.status in setOf("planned", "approved", "pending_approval", "conflict_pending") }
    ?: actions.firstOrNull { it.status !in setOf("executed", "failed", "ignored", "snoozed") }

private fun recommendationTier(action: SystemAction): String =
  when {
    action.status == "ignored" -> "已忽略"
    action.status == "snoozed" -> "稍后处理"
    action.requiresApproval || action.riskLevel.equals("high", ignoreCase = true) -> "强提醒"
    action.confidence >= 80 -> "强推荐"
    action.confidence >= 60 -> "中推荐"
    else -> "弱推荐"
  }

private fun planningStatusColor(status: String): Color =
  when (status) {
    "ok", "executed", "completed" -> Color(0xFF0F6F55)
    "failed", "conflict_pending" -> Color(0xFFB42318)
    "pending_approval", "queued", "running", "snoozed" -> Color(0xFF925E0E)
    "ignored" -> OpenCrayUi.Muted
    else -> OpenCrayUi.BrandPurple
  }

private fun planningCardTypeLabel(type: String): String =
  when (type) {
    "alarm" -> "闹钟"
    "todo" -> "待办"
    "course" -> "课表"
    "calendar" -> "日历"
    "homework" -> "作业"
    "notification" -> "通知"
    "search" -> "搜索"
    else -> "规划"
  }

private fun planningCardStatusLabel(status: String): String =
  when (status) {
    "planned" -> "待执行"
    "approved" -> "待执行"
    "pending_approval" -> "待确认"
    "queued" -> "等待端侧"
    "ok" -> "已完成"
    "running" -> "执行中"
    "executed" -> "已完成"
    "completed" -> "已完成"
    "failed" -> "未成功"
    "conflict_pending" -> "需处理冲突"
    "snoozed" -> "稍后处理"
    "ignored" -> "已忽略"
    else -> status.ifBlank { "待处理" }
  }

private object OpenCrayUi {
  val Background = Color(0xFFF8F4FA)
  val Ink = Color(0xFF1F1F1F)
  val Muted = Color(0xFF737986)
  val BrandPurple = Color(0xFF660874)
  val MenuBrandPurple = Color(0xFF7A35D8)
  val Yellow = Color(0xFFF2C94C)
  val Danger = Color(0xFFB42318)
  val DangerSoft = Color(0x18B42318)
  val Success = Color(0xFF0F6F55)
  val SuccessSoft = Color(0x180F6F55)
  val UserBubble = Color(0xFFE5DCE9)
}

private fun Modifier.sliderLikeSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 18.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    fun shadow(radiusDp: Dp, dx: Dp, dy: Dp, color: Color) {
      paint.setShadowLayer(radiusDp.toPx(), dx.toPx(), dy.toPx(), color.toArgb())
      drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    }
    shadow(12.dp, 0.dp, 0.dp, Color(0x40F2C94C))
    shadow(18.dp, 0.dp, 6.dp, Color(0x18660874))
    shadow(4.dp, 0.dp, 2.dp, Color(0x24660874))
    paint.clearShadowLayer()
    drawRoundRect(Color.White, cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color(0x33F2C94C), cornerRadius = CornerRadius(radius, radius), style = Stroke(width = 1.dp.toPx()))
    drawLine(
      color = Color.White.copy(alpha = 0.82f),
      start = Offset(radius, 1.dp.toPx()),
      end = Offset(size.width - radius, 1.dp.toPx()),
      strokeWidth = 1.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

private fun Modifier.composerSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 28.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(20.dp.toPx(), 0f, 8.dp.toPx(), Color(0x18000000).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(Color.White, cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color(0x1A647080), cornerRadius = CornerRadius(radius, radius), style = Stroke(width = 1.dp.toPx()))
  }

private fun Modifier.scrollToBottomSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = size.height / 2f
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(14.dp.toPx(), 0f, 5.dp.toPx(), Color(0x22660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.setShadowLayer(18.dp.toPx(), 0f, 0f, Color(0x24F2C94C).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(Color.White.copy(alpha = 0.92f), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
      color = Color(0x3DF2C94C),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
    drawLine(
      color = Color.White.copy(alpha = 0.86f),
      start = Offset(size.width * 0.32f, 1.2.dp.toPx()),
      end = Offset(size.width * 0.68f, 1.2.dp.toPx()),
      strokeWidth = 1.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

private fun Modifier.connectionStatusSurface(connected: Boolean): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = size.height / 2f
    val mainColor = if (connected) OpenCrayUi.Success else OpenCrayUi.Danger
    val fillColor = if (connected) OpenCrayUi.SuccessSoft else OpenCrayUi.DangerSoft
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = fillColor.toArgb()
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(10.dp.toPx(), 0f, 4.dp.toPx(), mainColor.copy(alpha = 0.14f).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.setShadowLayer(7.dp.toPx(), 0f, 0f, OpenCrayUi.Yellow.copy(alpha = if (connected) 0.18f else 0.12f).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(fillColor, cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
      color = mainColor.copy(alpha = 0.22f),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
    drawRoundRect(
      color = OpenCrayUi.Yellow.copy(alpha = 0.12f),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 0.7.dp.toPx()),
    )
  }

private fun Modifier.planningStatsSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 22.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(18.dp.toPx(), 0f, 7.dp.toPx(), Color(0x18660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.setShadowLayer(14.dp.toPx(), 0f, 0f, Color(0x22F2C94C).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(Color.White.copy(alpha = 0.76f), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
      color = Color(0x1F660874),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
    drawRoundRect(
      color = Color(0x24F2C94C),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 0.8.dp.toPx()),
    )
  }

private fun Modifier.metricTileSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 14.dp.toPx()
    drawRoundRect(Color(0xFFFDFBFF).copy(alpha = 0.9f), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
      color = Color(0x18660874),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
    drawLine(
      color = Color.White.copy(alpha = 0.86f),
      start = Offset(radius, 1.dp.toPx()),
      end = Offset(size.width - radius, 1.dp.toPx()),
      strokeWidth = 1.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

private fun Modifier.confirmationRequestSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 16.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(12.dp.toPx(), 0f, 4.dp.toPx(), Color(0x12660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.setShadowLayer(10.dp.toPx(), 0f, 0f, Color(0x18F2C94C).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(Color.White.copy(alpha = 0.78f), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
      color = Color(0x18660874),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
    drawRoundRect(
      color = Color(0x18F2C94C),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 0.8.dp.toPx()),
    )
  }

private fun Modifier.confirmationButtonSurface(primary: Boolean): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = size.height / 2f
    val fill = if (primary) Color(0xFFE7F6D5).copy(alpha = 0.96f) else OpenCrayUi.DangerSoft
    val border = if (primary) Color(0xFF5D8F28).copy(alpha = 0.62f) else OpenCrayUi.Danger.copy(alpha = 0.22f)
    val shadow = if (primary) Color(0x2E5D8F28) else OpenCrayUi.Danger.copy(alpha = 0.12f)
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = fill.toArgb()
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(10.dp.toPx(), 0f, 4.dp.toPx(), shadow.toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    if (primary) {
      paint.setShadowLayer(9.dp.toPx(), 0f, 0f, OpenCrayUi.Yellow.copy(alpha = 0.2f).toArgb())
      drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    }
    paint.clearShadowLayer()
    drawRoundRect(fill, cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
      color = border,
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
    if (primary) {
      drawRoundRect(
        color = OpenCrayUi.Yellow.copy(alpha = 0.16f),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 0.7.dp.toPx()),
      )
    }
  }

private fun Modifier.selectedIconGlow(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 8.dp.toPx()
    val paint = Paint().asFrameworkPaint().apply { isAntiAlias = true; color = Color.Transparent.toArgb() }
    paint.setShadowLayer(8.dp.toPx(), 0f, 0f, Color(0x33660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
  }

private fun Modifier.planningCardSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 18.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(14.dp.toPx(), 0f, 5.dp.toPx(), Color(0x14660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(Color.White.copy(alpha = 0.72f), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
      color = Color(0x12660874),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
  }

private fun Modifier.heroPlanningSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 24.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = OpenCrayUi.BrandPurple.toArgb()
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(18.dp.toPx(), 0f, 7.dp.toPx(), Color(0x2A660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(
      brush =
        Brush.linearGradient(
          colors = listOf(Color(0xFF7A35D8), Color(0xFF660874), Color(0xFF2CBEB6)),
          start = Offset(0f, 0f),
          end = Offset(size.width, size.height),
        ),
      cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
      color = Color.White.copy(alpha = 0.18f),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
  }
