package ai.opencray.app.ui.settings

import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencray.app.R

enum class SettingsTab(val label: String) {
  Chat("\u5bf9\u8bdd"),
  Plan("\u89c4\u5212"),
  Settings("\u8bbe\u7f6e"),
}

private enum class SettingsRoute(
  val title: String,
  @DrawableRes val iconRes: Int,
  val rows: List<String>,
) {
  Overview(
    title = "\u8bbe\u7f6e",
    iconRes = R.drawable.ic_settings_monitor_24,
    rows = emptyList(),
  ),
  Capability(
    title = "\u80fd\u529b\u4e0e\u9690\u79c1",
    iconRes = R.drawable.ic_settings_shield_24,
    rows =
      listOf(
        "\u901a\u77e5\u4e0a\u4e0b\u6587",
        "\u5b89\u5168\u5ba1\u67e5",
        "\u89c4\u5212\u7ec6\u8282",
      ),
  ),
  Connection(
    title = "\u8fde\u63a5\u4e0e\u6a21\u578b",
    iconRes = R.drawable.ic_settings_network_24,
    rows =
      listOf(
        "Agent-Core \u5730\u5740",
        "\u6a21\u578b\u540d\u79f0",
        "\u6a21\u578b Base URL",
        "\u6a21\u578b API Key",
        "\u7528\u6237\u6807\u8bc6",
      ),
  ),
  Campus(
    title = "\u6821\u56ed\u4e0e\u641c\u7d22",
    iconRes = R.drawable.ic_settings_book_open_24,
    rows =
      listOf(
        "\u6e05\u534e\u7edf\u4e00\u767b\u5f55",
        "\u7f51\u7edc\u5b66\u5802\u5730\u5740",
        "\u8bfe\u7a0b\u4e0e\u4f5c\u4e1a Cookie",
        "\u6821\u56ed\u6570\u636e\u6587\u4ef6",
        "\u641c\u7d22\u670d\u52a1",
      ),
  ),
  Memory(
    title = "\u8bb0\u5fc6",
    iconRes = R.drawable.ic_settings_brain_24,
    rows =
      listOf(
        "\u957f\u671f\u504f\u597d",
        "\u8bb0\u5fc6\u6587\u4ef6",
        "\u957f\u671f\u8bb0\u5fc6 TTL",
        "\u4e2d\u671f\u8bb0\u5fc6 TTL",
        "\u77ed\u671f\u8bb0\u5fc6 TTL",
        "\u8bb0\u5fc6\u534a\u8870\u671f",
      ),
  ),
  Device(
    title = "\u8bbe\u5907\u4e0e\u7cfb\u7edf",
    iconRes = R.drawable.ic_settings_monitor_24,
    rows =
      listOf(
        "\u8bbe\u5907\u65f6\u533a",
        "\u672c\u673a\u6267\u884c\u5668",
        "\u8fd0\u884c\u53cd\u9988",
      ),
  ),
}

data class SettingsItem(
  val label: String,
  @DrawableRes val iconRes: Int,
  val onClick: () -> Unit,
)

@Composable
fun SettingsScreen(
  modifier: Modifier = Modifier,
  onTabSelected: (SettingsTab) -> Unit = {},
  onCapabilityClick: () -> Unit = {},
  onConnectionClick: () -> Unit = {},
  onCampusClick: () -> Unit = {},
  onMemoryClick: () -> Unit = {},
  onDeviceClick: () -> Unit = {},
) {
  var route by remember { mutableStateOf(SettingsRoute.Overview) }
  // The drawer owns the tab segmented control. Settings keeps only the overview/detail pages.

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(SettingsColors.Background)
        .padding(horizontal = SettingsSpacing.ScreenHorizontal, vertical = SettingsSpacing.ScreenVertical),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (route == SettingsRoute.Overview) {
      SettingsOverview(
        onOpen = { next ->
          route = next
          when (next) {
            SettingsRoute.Capability -> onCapabilityClick()
            SettingsRoute.Connection -> onConnectionClick()
            SettingsRoute.Campus -> onCampusClick()
            SettingsRoute.Memory -> onMemoryClick()
            SettingsRoute.Device -> onDeviceClick()
            SettingsRoute.Overview -> Unit
          }
        },
      )
    } else {
      SettingsDetail(
        route = route,
        onBack = { route = SettingsRoute.Overview },
      )
    }
  }
}

@Composable
private fun SettingsOverview(onOpen: (SettingsRoute) -> Unit) {
  val items =
    listOf(
      SettingsItem("\u80fd\u529b\u4e0e\u9690\u79c1", R.drawable.ic_settings_shield_24) {
        onOpen(SettingsRoute.Capability)
      },
      SettingsItem("\u8fde\u63a5\u4e0e\u6a21\u578b", R.drawable.ic_settings_network_24) {
        onOpen(SettingsRoute.Connection)
      },
      SettingsItem("\u6821\u56ed\u4e0e\u641c\u7d22", R.drawable.ic_settings_book_open_24) {
        onOpen(SettingsRoute.Campus)
      },
      SettingsItem("\u8bb0\u5fc6", R.drawable.ic_settings_brain_24) {
        onOpen(SettingsRoute.Memory)
      },
      SettingsItem("\u8bbe\u5907\u4e0e\u7cfb\u7edf", R.drawable.ic_settings_monitor_24) {
        onOpen(SettingsRoute.Device)
      },
    )

  SettingsTitle("\u8bbe\u7f6e")
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
  onBack: () -> Unit,
) {
  SettingsTitle(route.title)
  Spacer(Modifier.height(18.dp))
  SettingsCard {
    SettingsItemRow(
      item =
        SettingsItem(
          label = "\u8fd4\u56de\u8bbe\u7f6e",
          iconRes = R.drawable.ic_chevron_right_24,
          onClick = onBack,
        ),
    )
  }
  Spacer(Modifier.height(18.dp))
  SettingsCard {
    route.rows.forEachIndexed { index, label ->
      SettingsItemRow(
        item =
          SettingsItem(
            label = label,
            iconRes = route.iconRes,
            onClick = {},
          ),
      )
      SettingsDivider(visible = index != route.rows.lastIndex)
    }
  }
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
