package ai.opencray.app.ui

import android.graphics.RectF
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencray.app.MainUiState
import ai.opencray.app.MainViewModel
import ai.opencray.app.domain.model.AppDestination
import ai.opencray.app.domain.model.ContextSignal
import ai.opencray.app.domain.model.PlanningCard
import ai.opencray.app.domain.model.SafetyRecord
import ai.opencray.app.domain.model.SystemAction
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
        quickSkillsExpanded = true,
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
        onQuickSkillsToggle = {},
        onSkillInvoked = { skillId ->
          viewModel.invokeSkill(skillId)
          refresh()
          scope.launch { drawerState.close() }
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
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(OpenCrayUi.Background)
        .padding(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 14.dp)
        .imePadding(),
  ) {
    TopOpenCrayBar(onMenuClick = onMenuClick)
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
      AppDestination.Settings -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) { SettingsScreen() }
    }
  }
}

@Composable
private fun TopOpenCrayBar(onMenuClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SliderLikeIconButton(onClick = onMenuClick)
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
private fun ChatScreen(
  messages: List<ChatMessage>,
  selectedFileName: String,
  onSend: (String) -> Unit,
  onAttachClick: () -> Unit,
  onClearAttachment: () -> Unit,
  onRetry: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var input by remember { mutableStateOf("") }
  val hiddenAssistantIds = remember { mutableStateMapOf<String, Boolean>() }
  val visibleMessages = messages.filterNot { hiddenAssistantIds[it.id] == true }
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
            onRetry = {
              if (message.role == ChatRole.Assistant && retryPrompt.isNotBlank()) {
                hiddenAssistantIds[message.id] = true
                onRetry(retryPrompt)
              }
            },
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

@Composable
private fun ChatMessageItem(
  message: ChatMessage,
  retryPrompt: String,
  onRetry: () -> Unit,
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
          Text(text = message.text, color = OpenCrayUi.Ink, fontSize = 15.sp, lineHeight = 21.sp)
        }
      }
    }
    ChatRole.Assistant,
    ChatRole.System,
    -> {
      Column(Modifier.fillMaxWidth()) {
        Text(
          text = message.text,
          color = OpenCrayUi.Ink,
          fontSize = 15.sp,
          lineHeight = 23.sp,
        )
        if (message.role == ChatRole.Assistant && message.text.isNotBlank()) {
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
  val focusedAction = focusedAction(state.systemActions)
  LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { PlanningOverview(state = state) }
    item { PlanningFocus(state = state) }
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
    item { PlanningContextSection(state = state) }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlanningMiniSection(
          title = "课程与提醒",
          body = planningScheduleText(state),
          secondaryBody = planningAlarmText(state),
          modifier = Modifier.weight(1f),
        )
        PlanningMiniSection(
          title = "待办与节奏",
          body = planningTodoText(state),
          secondaryBody = homeQuickActionsText(),
          modifier = Modifier.weight(1f),
        )
      }
    }
    item { PlanningFlowSection() }
    item {
      ActionsPanel(
        state = state,
        focusedAction = focusedAction,
        onRunAgentPlan = onRunAgentPlan,
        onExecuteAction = onExecuteAction,
        onSnoozeAction = onSnoozeAction,
        onIgnoreAction = onIgnoreAction,
        onApproveActions = onApproveActions,
        onResolveConflict = onResolveConflict,
      )
    }
    item { SafetyAndEventsSection(state = state) }
  }
}

@Composable
private fun PlanningOverview(state: MainUiState) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .heroPlanningSurface()
        .padding(16.dp),
  ) {
    Text("PLAN OVERVIEW", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text("今日计划中心", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(
      text = "今天的工作台已经为你收拢了任务、动作、提醒与长期偏好。\n先看当前焦点，再决定是继续执行、补充计划，还是调整提醒节奏。",
      color = Color.White,
      fontSize = 14.sp,
      lineHeight = 20.sp,
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MetricTile("任务", state.tasks.size.toString(), Modifier.weight(1f))
      MetricTile("动作", state.systemActions.size.toString(), Modifier.weight(1f))
      MetricTile("提醒", state.safetyRecords.size.toString(), Modifier.weight(1f))
      MetricTile("记忆", state.memoryRecords.size.toString(), Modifier.weight(1f))
    }
  }
}

@Composable
private fun MetricTile(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .clip(RoundedCornerShape(14.dp))
        .background(Color.White.copy(alpha = 0.82f))
        .padding(10.dp),
  ) {
    Text(label, color = OpenCrayUi.BrandPurple, fontSize = 11.sp)
    Spacer(Modifier.height(4.dp))
    Text(value, color = OpenCrayUi.BrandPurple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
