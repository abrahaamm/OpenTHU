package ai.opencray.app.ui.settings

import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencray.app.R
import ai.opencray.app.SettingsUiState
import ai.opencray.app.domain.model.MemoryRecord
import ai.opencray.app.domain.model.SafetyRecord

private enum class SettingsRoute(
  val title: String,
  @DrawableRes val iconRes: Int,
) {
  Overview("设置", R.drawable.ic_settings_monitor_24),
  Capability("能力与隐私", R.drawable.ic_settings_shield_24),
  Connection("连接与模型", R.drawable.ic_settings_network_24),
  Campus("校园与搜索", R.drawable.ic_settings_book_open_24),
  Memory("记忆", R.drawable.ic_settings_brain_24),
  Device("设备与系统", R.drawable.ic_settings_monitor_24),
}

data class SettingsItem(
  val label: String,
  @DrawableRes val iconRes: Int,
  val onClick: () -> Unit,
)

@Composable
fun SettingsScreen(
  settings: SettingsUiState,
  memoryRecords: List<MemoryRecord>,
  recentEvents: List<String>,
  safetyRecords: List<SafetyRecord>,
  notificationContextEnabled: Boolean,
  safetyGuardEnabled: Boolean,
  onSettingsChange: (SettingsUiState) -> Unit,
  onSaveSettings: () -> Boolean,
  onTestSettings: () -> List<String>,
  onConnectGateway: () -> Unit,
  onLaunchLearnCookieLogin: (String) -> Unit,
  onToggleCapability: (String, Boolean) -> Unit,
  onAddPreference: (String) -> Boolean,
  onUpdatePreference: (Int, String) -> Boolean,
  onDeletePreference: (Int) -> Boolean,
  onClearAllMemory: () -> Boolean,
  modifier: Modifier = Modifier,
) {
  var route by remember { mutableStateOf(SettingsRoute.Overview) }
  var statusMessage by remember { mutableStateOf("") }

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(SettingsColors.Background)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = SettingsSpacing.ScreenHorizontal, vertical = SettingsSpacing.ScreenVertical),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (route == SettingsRoute.Overview) {
      SettingsOverview(onOpen = { route = it })
    } else {
      SettingsDetail(
        route = route,
        settings = settings,
        memoryRecords = memoryRecords,
        recentEvents = recentEvents,
        safetyRecords = safetyRecords,
        notificationContextEnabled = notificationContextEnabled,
        safetyGuardEnabled = safetyGuardEnabled,
        statusMessage = statusMessage,
        onBack = { route = SettingsRoute.Overview },
        onSettingsChange = onSettingsChange,
        onSaveSettings = {
          statusMessage = if (onSaveSettings()) "设置已保存" else "请检查服务地址和端口"
        },
        onTestSettings = {
          val warnings = onTestSettings()
          statusMessage = if (warnings.isEmpty()) "当前配置检查通过" else warnings.joinToString("；")
        },
        onConnectGateway = onConnectGateway,
        onLaunchLearnCookieLogin = onLaunchLearnCookieLogin,
        onToggleCapability = onToggleCapability,
        onAddPreference = onAddPreference,
        onUpdatePreference = onUpdatePreference,
        onDeletePreference = onDeletePreference,
        onClearAllMemory = {
          statusMessage = if (onClearAllMemory()) "已清除全部记忆" else "当前没有可清除的记忆"
        },
      )
    }
  }
}

@Composable
private fun SettingsOverview(onOpen: (SettingsRoute) -> Unit) {
  val items =
    listOf(
      SettingsItem("能力与隐私", R.drawable.ic_settings_shield_24) { onOpen(SettingsRoute.Capability) },
      SettingsItem("连接与模型", R.drawable.ic_settings_network_24) { onOpen(SettingsRoute.Connection) },
      SettingsItem("校园与搜索", R.drawable.ic_settings_book_open_24) { onOpen(SettingsRoute.Campus) },
      SettingsItem("记忆", R.drawable.ic_settings_brain_24) { onOpen(SettingsRoute.Memory) },
      SettingsItem("设备与系统", R.drawable.ic_settings_monitor_24) { onOpen(SettingsRoute.Device) },
    )

  SettingsTitle("设置")
  Spacer(Modifier.height(SettingsSpacing.TitleBottom))
  SettingsCard {
    items.forEachIndexed { index, item ->
      SettingsItemRow(item = item)
      SettingsDivider(visible = index != items.lastIndex)
    }
  }
}

@Composable
private fun SettingsDetail(
  route: SettingsRoute,
  settings: SettingsUiState,
  memoryRecords: List<MemoryRecord>,
  recentEvents: List<String>,
  safetyRecords: List<SafetyRecord>,
  notificationContextEnabled: Boolean,
  safetyGuardEnabled: Boolean,
  statusMessage: String,
  onBack: () -> Unit,
  onSettingsChange: (SettingsUiState) -> Unit,
  onSaveSettings: () -> Unit,
  onTestSettings: () -> Unit,
  onConnectGateway: () -> Unit,
  onLaunchLearnCookieLogin: (String) -> Unit,
  onToggleCapability: (String, Boolean) -> Unit,
  onAddPreference: (String) -> Boolean,
  onUpdatePreference: (Int, String) -> Boolean,
  onDeletePreference: (Int) -> Boolean,
  onClearAllMemory: () -> Unit,
) {
  SettingsTitle(route.title)
  Spacer(Modifier.height(18.dp))
  SettingsCard {
    SettingsItemRow(
      item =
        SettingsItem(
          label = "返回设置",
          iconRes = R.drawable.ic_chevron_right_24,
          onClick = onBack,
        ),
    )
  }
  Spacer(Modifier.height(16.dp))
  when (route) {
    SettingsRoute.Capability -> CapabilitySettings(settings, notificationContextEnabled, safetyGuardEnabled, onSettingsChange, onToggleCapability)
    SettingsRoute.Connection -> ConnectionSettings(settings, statusMessage, onSettingsChange, onSaveSettings, onTestSettings, onConnectGateway)
    SettingsRoute.Campus -> CampusSettings(settings, onSettingsChange, onLaunchLearnCookieLogin)
    SettingsRoute.Memory -> MemorySettings(settings, memoryRecords, onSettingsChange, onAddPreference, onUpdatePreference, onDeletePreference, onClearAllMemory)
    SettingsRoute.Device -> DeviceSettings(settings, recentEvents, safetyRecords, onSettingsChange)
    SettingsRoute.Overview -> Unit
  }
}

@Composable
private fun CapabilitySettings(
  settings: SettingsUiState,
  notificationContextEnabled: Boolean,
  safetyGuardEnabled: Boolean,
  onSettingsChange: (SettingsUiState) -> Unit,
  onToggleCapability: (String, Boolean) -> Unit,
) {
  SettingsCard {
    SettingSwitchRow("通知上下文", "允许系统通知进入 Agent 上下文。", notificationContextEnabled) {
      onToggleCapability("notification_context", it)
    }
    SettingsDivider(true)
    SettingSwitchRow("安全审查", "高风险动作执行前先进入安全审查。", safetyGuardEnabled) {
      onToggleCapability("safety_guard", it)
    }
    SettingsDivider(true)
    SettingSwitchRow("规划细节", "显示任务流、上下文和执行解释。", settings.showPlanningDetails) {
      onSettingsChange(settings.copy(showPlanningDetails = it))
    }
  }
}

@Composable
private fun ConnectionSettings(
  settings: SettingsUiState,
  statusMessage: String,
  onSettingsChange: (SettingsUiState) -> Unit,
  onSaveSettings: () -> Unit,
  onTestSettings: () -> Unit,
  onConnectGateway: () -> Unit,
) {
  SettingsCard {
    SettingInputRow("服务地址", "Agent-Core 或本地网关 Host。", settings.host, "10.0.2.2") { onSettingsChange(settings.copy(host = it)) }
    SettingsDivider(true)
    SettingInputRow("服务端口", "Agent-Core 端口。", settings.port, "18789") { onSettingsChange(settings.copy(port = it)) }
    SettingsDivider(true)
    SettingSwitchRow("使用 TLS", "连接网关时启用 HTTPS/TLS。", settings.tlsEnabled) { onSettingsChange(settings.copy(tlsEnabled = it)) }
    SettingsDivider(true)
    SettingInputRow("模型名称", "实际发送给模型服务的模型名。", settings.llmModel, "moonshot-v1-8k") { onSettingsChange(settings.copy(llmModel = it)) }
    SettingsDivider(true)
    SettingInputRow("模型 Base URL", "自定义模型服务地址，可留空。", settings.llmBaseUrl, "LLM Base URL") { onSettingsChange(settings.copy(llmBaseUrl = it)) }
    SettingsDivider(true)
    SettingInputRow("OpenAI Key", "用于访问 OpenAI 或兼容接口的 API Key。", settings.openAiKey, "OPENAI_API_KEY") { onSettingsChange(settings.copy(openAiKey = it)) }
    SettingsDivider(true)
    SettingInputRow("用户标识", "区分不同用户的会话与记忆命名空间。", settings.userId, "android_user") { onSettingsChange(settings.copy(userId = it)) }
  }
  Spacer(Modifier.height(14.dp))
  SettingsButtonRow(
    primaryText = "保存设置",
    onPrimary = onSaveSettings,
    secondaryText = "检测配置",
    onSecondary = onTestSettings,
  )
  Spacer(Modifier.height(10.dp))
  SettingsWideButton("连接服务器", onClick = onConnectGateway)
  StatusMessage(statusMessage)
}

@Composable
private fun CampusSettings(
  settings: SettingsUiState,
  onSettingsChange: (SettingsUiState) -> Unit,
  onLaunchLearnCookieLogin: (String) -> Unit,
) {
  SettingsWideButton("清华统一登录", onClick = { onLaunchLearnCookieLogin(settings.learnBaseUrl.ifBlank { "https://learn.tsinghua.edu.cn" }) })
  Spacer(Modifier.height(14.dp))
  SettingsCard {
    SettingInputRow("网络学堂地址", "作业和课程 skill 的 Learn 基础地址。", settings.learnBaseUrl, "https://learn.tsinghua.edu.cn") { onSettingsChange(settings.copy(learnBaseUrl = it)) }
    SettingsDivider(true)
    SettingInputRow("WebVPN Cookie", "课表和校内资讯可读取该登录态。", settings.webvpnCookie, "OPENTHU_WEBVPN_COOKIE", minHeight = 76) { onSettingsChange(settings.copy(webvpnCookie = it)) }
    SettingsDivider(true)
    SettingInputRow("WebVPN CSRF", "部分校内接口需要的 CSRF Token。", settings.webvpnCsrf, "OPENTHU_WEBVPN_CSRF") { onSettingsChange(settings.copy(webvpnCsrf = it)) }
    SettingsDivider(true)
    SettingInputRow("网络学堂 Cookie", "作业抓取、上传和提交使用的 Learn Cookie。", settings.homeworkCookie, "JSESSIONID=...; XSRF-TOKEN=...", minHeight = 88) { onSettingsChange(settings.copy(homeworkCookie = it)) }
    SettingsDivider(true)
    SettingInputRow("网络学堂 CSRF", "可留空，执行器会优先从 Cookie 中提取。", settings.homeworkCsrf, "XSRF-TOKEN") { onSettingsChange(settings.copy(homeworkCsrf = it)) }
    SettingsDivider(true)
    SettingInputRow("校园活动文件", "活动读取能力的本地兜底数据路径。", settings.campusFile, "OPENTHU_CAMPUS_ACTIVITIES_FILE") { onSettingsChange(settings.copy(campusFile = it)) }
  }
  Spacer(Modifier.height(14.dp))
  SettingsCard {
    SettingInputRow("搜索提供方", "duckduckgo / searxng / brave。", settings.searchProvider, "duckduckgo") { onSettingsChange(settings.copy(searchProvider = it)) }
    SettingsDivider(true)
    SettingInputRow("搜索接口地址", "搜索服务 endpoint。", settings.searchEndpoint, "https://lite.duckduckgo.com/lite/") { onSettingsChange(settings.copy(searchEndpoint = it)) }
    SettingsDivider(true)
    SettingInputRow("搜索 API Key", "仅部分 provider 需要。", settings.searchApiKey, "OPENTHU_SEARCH_API_KEY") { onSettingsChange(settings.copy(searchApiKey = it)) }
    SettingsDivider(true)
    SettingInputRow("搜索范围", "campus / general / hybrid。", settings.searchScene, "hybrid") { onSettingsChange(settings.copy(searchScene = it)) }
    SettingsDivider(true)
    SettingInputRow("搜索缓存时长", "单位为秒。", settings.searchTtl, "3600") { onSettingsChange(settings.copy(searchTtl = it)) }
  }
}

@Composable
private fun MemorySettings(
  settings: SettingsUiState,
  memoryRecords: List<MemoryRecord>,
  onSettingsChange: (SettingsUiState) -> Unit,
  onAddPreference: (String) -> Boolean,
  onUpdatePreference: (Int, String) -> Boolean,
  onDeletePreference: (Int) -> Boolean,
  onClearAllMemory: () -> Unit,
) {
  var preferenceText by remember { mutableStateOf("") }
  var editingIndex by remember { mutableStateOf<Int?>(null) }
  val longPreferences = memoryRecords.filter { it.scope == "long" }.sortedByDescending { it.updatedAtEpochMs }

  SettingsCard {
    SettingInputRow("偏好描述", "保存后会作为长期记忆参与后续规划。", preferenceText, "输入新的偏好，例如：少推荐晚间活动") { preferenceText = it }
    SettingsDivider(true)
    SettingsItemRow(
      item = SettingsItem(if (editingIndex == null) "新增偏好" else "保存修改", R.drawable.ic_settings_brain_24) {
        val ok = editingIndex?.let { onUpdatePreference(it, preferenceText) } ?: onAddPreference(preferenceText)
        if (ok) {
          preferenceText = ""
          editingIndex = null
        }
      },
    )
    SettingsDivider(true)
    SettingsItemRow(
      item = SettingsItem("清除全部记忆", R.drawable.ic_settings_shield_24, onClearAllMemory),
    )
  }
  Spacer(Modifier.height(14.dp))
  SettingsCard {
    if (longPreferences.isEmpty()) {
      StaticInfoRow("长期偏好", "还没有长期偏好。")
    } else {
      longPreferences.forEachIndexed { index, record ->
        PreferenceRow(
          text = record.value,
          onEdit = {
            editingIndex = index
            preferenceText = record.value
          },
          onDelete = { onDeletePreference(index) },
        )
        SettingsDivider(index != longPreferences.lastIndex)
      }
    }
  }
  Spacer(Modifier.height(14.dp))
  SettingsCard {
    SettingInputRow("记忆文件", "Agent-Core 任务记忆文件路径。", settings.memoryFile, "agent/langgraph/memory_store.json") { onSettingsChange(settings.copy(memoryFile = it)) }
    SettingsDivider(true)
    SettingInputRow("长期记忆 TTL", "单位：天。", settings.memoryLongTtl, "365") { onSettingsChange(settings.copy(memoryLongTtl = it)) }
    SettingsDivider(true)
    SettingInputRow("中期记忆 TTL", "单位：天。", settings.memoryMidTtl, "30") { onSettingsChange(settings.copy(memoryMidTtl = it)) }
    SettingsDivider(true)
    SettingInputRow("短期记忆 TTL", "单位：天。", settings.memoryShortTtl, "7") { onSettingsChange(settings.copy(memoryShortTtl = it)) }
    SettingsDivider(true)
    SettingInputRow("记忆半衰期", "单位：天，影响记忆权重衰减。", settings.memoryHalfLife, "30") { onSettingsChange(settings.copy(memoryHalfLife = it)) }
  }
}

@Composable
private fun DeviceSettings(
  settings: SettingsUiState,
  recentEvents: List<String>,
  safetyRecords: List<SafetyRecord>,
  onSettingsChange: (SettingsUiState) -> Unit,
) {
  SettingsCard {
    SettingSwitchRow("跟随系统时区", "开启后自动使用设备系统时区。", settings.timezoneFollowSystem) { onSettingsChange(settings.copy(timezoneFollowSystem = it)) }
    SettingsDivider(true)
    SettingInputRow("日历时区", "例如 Asia/Shanghai。", settings.timezone, "Asia/Shanghai") { onSettingsChange(settings.copy(timezone = it)) }
    SettingsDivider(true)
    SettingInputRow("ADB 路径", "本机执行器可读取该路径。", settings.adbBin, "adb") { onSettingsChange(settings.copy(adbBin = it)) }
    SettingsDivider(true)
    SettingInputRow("ADB 设备序列号", "多设备调试时指定设备。", settings.adbSerial, "emulator-5554") { onSettingsChange(settings.copy(adbSerial = it)) }
  }
  Spacer(Modifier.height(14.dp))
  SettingsCard {
    StaticInfoRow("安全记录", safetyRecords.take(8).joinToString("\n\n") { "${it.title}\n${it.detail}\n状态：${it.status}" }.ifBlank { "暂无安全记录。" })
    SettingsDivider(true)
    StaticInfoRow("最近事件", recentEvents.take(12).joinToString("\n") { "• $it" }.ifBlank { "• 暂无最近事件。" })
  }
}

@Composable
private fun SettingInputRow(
  label: String,
  description: String,
  value: String,
  hint: String,
  minHeight: Int = 54,
  onChange: (String) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsSpacing.RowHorizontal, vertical = 12.dp)) {
    Text(label, color = SettingsColors.BodyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(description, color = SettingsColors.UnselectedText, fontSize = 12.sp, lineHeight = 16.sp)
    Spacer(Modifier.height(8.dp))
    BasicTextField(
      value = value,
      onValueChange = onChange,
      textStyle = TextStyle(color = SettingsColors.BodyText, fontSize = 14.sp, lineHeight = 18.sp),
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(SettingsColors.Track)
          .padding(horizontal = 12.dp, vertical = 10.dp)
          .height(minHeight.dp),
      decorationBox = { inner ->
        Box(Modifier.fillMaxWidth()) {
          if (value.isBlank()) {
            Text(hint, color = SettingsColors.UnselectedText.copy(alpha = 0.62f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
          inner()
        }
      },
    )
  }
}

@Composable
private fun SettingSwitchRow(
  label: String,
  description: String,
  checked: Boolean,
  onChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsSpacing.RowHorizontal, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(label, color = SettingsColors.BodyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(4.dp))
      Text(description, color = SettingsColors.UnselectedText, fontSize = 12.sp, lineHeight = 16.sp)
    }
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun StaticInfoRow(
  label: String,
  body: String,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsSpacing.RowHorizontal, vertical = 14.dp)) {
    Text(label, color = SettingsColors.BodyText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(body, color = SettingsColors.UnselectedText, fontSize = 13.sp, lineHeight = 18.sp)
  }
}

@Composable
private fun PreferenceRow(
  text: String,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsSpacing.RowHorizontal, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text, color = SettingsColors.BodyText, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(8.dp))
    MiniTextButton("修改", onEdit)
    Spacer(Modifier.width(6.dp))
    MiniTextButton("删除", onDelete)
  }
}

@Composable
private fun MiniTextButton(
  text: String,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(12.dp))
        .background(SettingsColors.Track)
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 7.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = SettingsColors.BrandPurple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun SettingsButtonRow(
  primaryText: String,
  onPrimary: () -> Unit,
  secondaryText: String,
  onSecondary: () -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    SettingsWideButton(primaryText, Modifier.weight(1f), onPrimary)
    SettingsWideButton(secondaryText, Modifier.weight(1f), onSecondary, primary = false)
  }
}

@Composable
private fun SettingsWideButton(
  text: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
  primary: Boolean = true,
) {
  Box(
    modifier =
      modifier
        .height(46.dp)
        .settingsButtonShadow(primary)
        .clip(RoundedCornerShape(16.dp))
        .background(if (primary) SettingsColors.BrandPurple else SettingsColors.Card)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = if (primary) Color.White else SettingsColors.BrandPurple, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun StatusMessage(message: String) {
  if (message.isBlank()) return
  Spacer(Modifier.height(10.dp))
  Text(
    text = message,
    color = SettingsColors.UnselectedText,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun SettingsTitle(text: String) {
  Text(
    text = text,
    color = SettingsColors.BrandPurple,
    fontSize = SettingsTypography.TitleSize,
    fontWeight = SettingsTypography.TitleWeight,
    letterSpacing = 0.56.sp,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .settingsCardShadow()
        .clip(RoundedCornerShape(SettingsShapes.CardRadius))
        .background(SettingsColors.Card),
    content = content,
  )
}

@Composable
private fun SettingsDivider(visible: Boolean) {
  if (!visible) return
  Box(
    modifier =
      Modifier
        .padding(horizontal = SettingsSpacing.RowHorizontal)
        .height(1.dp)
        .fillMaxWidth()
        .background(SettingsColors.Divider),
  )
}

@Composable
fun SettingsItemRow(
  item: SettingsItem,
  modifier: Modifier = Modifier,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(if (pressed) SettingsColors.RowPressed else Color.Transparent)
        .clickable(
          interactionSource = interactionSource,
          indication = null,
        ) { item.onClick() }
        .padding(horizontal = SettingsSpacing.RowHorizontal, vertical = SettingsSpacing.RowVertical),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SettingIcon(iconRes = item.iconRes)
    Spacer(Modifier.width(SettingsSpacing.RowIconGap))
    Text(
      text = item.label,
      color = SettingsColors.BodyText,
      fontSize = SettingsTypography.BodySize,
      fontWeight = SettingsTypography.BodyWeight,
      modifier = Modifier.weight(1f),
    )
    Image(
      painter = painterResource(id = R.drawable.ic_chevron_right_24),
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      contentScale = ContentScale.Fit,
    )
  }
}

@Composable
fun SettingIcon(
  @DrawableRes iconRes: Int,
  modifier: Modifier = Modifier,
) {
  Image(
    painter = painterResource(id = iconRes),
    contentDescription = null,
    modifier = modifier.size(22.dp),
    contentScale = ContentScale.Fit,
  )
}

private fun Modifier.settingsCardShadow(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = SettingsShapes.CardRadius.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(16.dp.toPx(), 0f, 4.dp.toPx(), Color(0x14660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.setShadowLayer(8.dp.toPx(), 0f, 2.dp.toPx(), Color(0x0A660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
  }

private fun Modifier.settingsButtonShadow(primary: Boolean): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 16.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = if (primary) SettingsColors.BrandPurple.toArgb() else android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(14.dp.toPx(), 0f, 5.dp.toPx(), Color(0x22660874).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
  }
