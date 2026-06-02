package ai.opencray.app.ui.settings

import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencray.app.R

enum class SettingsTab(val label: String) {
  Chat("对话"),
  Plan("规划"),
  Settings("设置"),
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
  var selectedTab by remember { mutableStateOf(SettingsTab.Settings) }
  val items =
    listOf(
      SettingsItem("能力与隐私", R.drawable.ic_settings_shield_24, onCapabilityClick),
      SettingsItem("连接与模型", R.drawable.ic_settings_network_24, onConnectionClick),
      SettingsItem("校园与搜索", R.drawable.ic_settings_book_open_24, onCampusClick),
      SettingsItem("记忆", R.drawable.ic_settings_brain_24, onMemoryClick),
      SettingsItem("设备与系统", R.drawable.ic_settings_monitor_24, onDeviceClick),
    )

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(SettingsColors.Background)
        .padding(horizontal = SettingsSpacing.ScreenHorizontal, vertical = SettingsSpacing.ScreenVertical),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "设置",
      color = SettingsColors.BrandPurple,
      fontSize = SettingsTypography.TitleSize,
      fontWeight = SettingsTypography.TitleWeight,
      letterSpacing = 0.56.sp,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(SettingsSpacing.TitleBottom))

    SegmentedControl(
      selectedTab = selectedTab,
      onTabSelected = { tab ->
        selectedTab = tab
        onTabSelected(tab)
      },
    )

    Spacer(Modifier.height(SettingsSpacing.SegmentBottom))

    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .settingsCardShadow()
          .clip(RoundedCornerShape(SettingsShapes.CardRadius))
          .background(SettingsColors.Card),
    ) {
      items.forEachIndexed { index, item ->
        SettingsItemRow(item = item)
        if (index != items.lastIndex) {
          Box(
            modifier =
              Modifier
                .padding(horizontal = SettingsSpacing.RowHorizontal)
                .height(1.dp)
                .fillMaxWidth()
                .background(SettingsColors.Divider),
          )
        }
      }
    }
  }
}

@Composable
fun SegmentedControl(
  selectedTab: SettingsTab,
  onTabSelected: (SettingsTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val tabs = SettingsTab.entries
  val index = tabs.indexOf(selectedTab).coerceAtLeast(0)
  val sliderWidth = (SettingsSpacing.SegmentWidth - SettingsSpacing.SegmentPadding * 2) / 3
  val targetOffset = SettingsSpacing.SegmentPadding + sliderWidth * index
  val animatedOffset by animateDpAsState(
    targetValue = targetOffset,
    animationSpec = tween(SettingsMotion.DurationMillis, easing = SettingsMotion.Easing),
    label = "segmentedOffset",
  )
  val squashX by animateFloatAsState(
    targetValue = 1f,
    animationSpec = tween(SettingsMotion.DurationMillis, easing = SettingsMotion.Easing),
    label = "segmentedSquashX",
  )

  Box(
    modifier =
      modifier
        .width(SettingsSpacing.SegmentWidth)
        .height(SettingsSpacing.SegmentHeight)
        .recessedTrack()
        .padding(SettingsSpacing.SegmentPadding),
  ) {
    Box(
      modifier =
        Modifier
          .offset(x = animatedOffset - SettingsSpacing.SegmentPadding)
          .width(sliderWidth)
          .height(SettingsSpacing.SegmentHeight - SettingsSpacing.SegmentPadding * 2)
          .raisedSlider(scaleX = squashX),
    )

    Row(modifier = Modifier.fillMaxSize()) {
      tabs.forEach { tab ->
        val selected = tab == selectedTab
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxSize()
              .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
              ) { onTabSelected(tab) },
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = tab.label,
            color = if (selected) SettingsColors.BrandPurple else SettingsColors.UnselectedText,
            fontSize = SettingsTypography.TabSize,
            fontWeight = if (selected) SettingsTypography.TabSelectedWeight else SettingsTypography.TabWeight,
          )
        }
      }
    }
  }
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

private fun Modifier.recessedTrack(): Modifier =
  drawBehind {
    val radius = size.height / 2f
    drawRoundRect(
      color = SettingsColors.Track,
      cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
      color = Color(0x1F660874),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 2.dp.toPx()),
    )
    drawRoundRect(
      color = Color(0x14660874),
      topLeft = androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()),
      cornerRadius = CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
  }

private fun Modifier.raisedSlider(scaleX: Float): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val corner = size.height / 2f
    val paint = Paint().asFrameworkPaint().apply {
      isAntiAlias = true
      color = android.graphics.Color.WHITE
      style = android.graphics.Paint.Style.FILL
    }

    fun shadow(radius: Dp, dx: Dp, dy: Dp, color: Color) {
      paint.setShadowLayer(radius.toPx(), dx.toPx(), dy.toPx(), color.toArgb())
      drawContext.canvas.nativeCanvas.drawRoundRect(rect, corner, corner, paint)
    }

    shadow(12.dp, 0.dp, 0.dp, Color(0x40F2C94C))
    shadow(20.dp, 0.dp, 0.dp, Color(0x26F2C94C))
    shadow(6.dp, 0.dp, 3.dp, Color(0x1F660874))
    shadow(12.dp, 0.dp, 6.dp, Color(0x14660874))
    shadow(2.dp, 0.dp, 1.dp, Color(0x26660874))
    paint.clearShadowLayer()

    drawRoundRect(
      brush =
        Brush.verticalGradient(
          0f to SettingsColors.SliderTop,
          0.5f to SettingsColors.SliderMiddle,
          1f to SettingsColors.SliderBottom,
        ),
      cornerRadius = CornerRadius(corner, corner),
    )
    drawRoundRect(
      color = Color(0x33F2C94C),
      cornerRadius = CornerRadius(corner, corner),
      style = Stroke(width = 1.dp.toPx()),
    )
    drawLine(
      color = Color(0xCCFFFFFF),
      start = androidx.compose.ui.geometry.Offset(corner, 1.dp.toPx()),
      end = androidx.compose.ui.geometry.Offset(size.width - corner, 1.dp.toPx()),
      strokeWidth = 1.dp.toPx(),
      cap = StrokeCap.Round,
    )
    drawLine(
      color = Color(0x08000000),
      start = androidx.compose.ui.geometry.Offset(corner, size.height - 1.dp.toPx()),
      end = androidx.compose.ui.geometry.Offset(size.width - corner, size.height - 1.dp.toPx()),
      strokeWidth = 1.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }

private fun Modifier.settingsCardShadow(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = SettingsShapes.CardRadius.toPx()
    val paint = Paint().asFrameworkPaint().apply {
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
