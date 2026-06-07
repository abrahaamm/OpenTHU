package ai.opencray.app.ui.drawer

import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencray.app.ConversationSummary
import ai.opencray.app.MainUiState
import ai.opencray.app.R
import ai.opencray.app.domain.model.AppDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun OpenCrayDrawerMenu(
  state: MainUiState,
  onDestinationSelected: (AppDestination) -> Unit,
  onCreateConversation: () -> Unit,
  onConversationSelected: (String) -> Unit,
  onDeleteConversation: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .width(340.dp)
        .fillMaxHeight()
        .drawerSurface()
        .padding(horizontal = 22.dp, vertical = 22.dp),
  ) {
    Text(
      text = "OpenTHU",
      color = DrawerColors.MenuBrandPurple,
      fontSize = 38.sp,
      fontWeight = FontWeight.Bold,
      fontFamily = FontFamily.Cursive,
    )

    Spacer(Modifier.height(18.dp))

    DrawerSegmentedControl(
      selected = state.currentDestination,
      onSelected = onDestinationSelected,
    )

    Spacer(Modifier.height(18.dp))

    ConversationSection(
      conversations = state.conversationSummaries,
      onCreateConversation = onCreateConversation,
      onConversationSelected = onConversationSelected,
      onDeleteConversation = onDeleteConversation,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun DrawerSegmentedControl(
  selected: AppDestination,
  onSelected: (AppDestination) -> Unit,
) {
  val tabs =
    listOf(
      AppDestination.Chat to "\u5bf9\u8bdd",
      AppDestination.Planning to "\u89c4\u5212",
      AppDestination.Settings to "\u8bbe\u7f6e",
    )
  var localSelected by remember { mutableStateOf(selected) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(selected) {
    localSelected = selected
  }
  val selectedIndex = tabs.indexOfFirst { it.first == localSelected }.coerceAtLeast(0)
  val trackWidth = 296.dp
  val trackHeight = 54.dp
  val padding = 5.dp
  val sliderWidth = (trackWidth - padding * 2) / 3
  val targetOffset = padding + sliderWidth * selectedIndex
  val animatedOffset by animateDpAsState(
    targetValue = targetOffset,
    animationSpec = tween(400, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
    label = "drawerSegmentedOffset",
  )
  val squash = remember { Animatable(1f) }
  LaunchedEffect(selected) {
    squash.snapTo(0.75f)
    squash.animateTo(
      targetValue = 1f,
      animationSpec = tween(400, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
    )
  }

  Box(
    modifier =
      Modifier
        .width(trackWidth)
        .height(trackHeight)
        .segmentedTrack()
        .padding(padding),
  ) {
    Box(
      modifier =
        Modifier
          .offset(x = animatedOffset - padding)
          .width(sliderWidth)
          .height(trackHeight - padding * 2)
          .scale(scaleX = squash.value, scaleY = 0.92f + squash.value * 0.08f)
          .segmentedSlider(),
    )

    Row(modifier = Modifier.fillMaxSize()) {
      tabs.forEach { (destination, label) ->
        val isSelected = destination == localSelected
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxHeight()
              .clickable {
                if (destination == localSelected) return@clickable
                localSelected = destination
                scope.launch {
                  delay(400)
                  onSelected(destination)
                }
              },
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = label,
            color = if (isSelected) DrawerColors.BrandPurple else DrawerColors.UnselectedText,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = FontFamily.SansSerif,
          )
        }
      }
    }
  }
}

@Composable
private fun ConversationSection(
  conversations: List<ConversationSummary>,
  onCreateConversation: () -> Unit,
  onConversationSelected: (String) -> Unit,
  onDeleteConversation: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .roundedSection()
        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "\u4f1a\u8bdd\u5217\u8868",
        color = DrawerColors.Ink,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        modifier = Modifier.weight(1f),
      )
      SmallLimeButton(
        text = "\u65b0\u5efa\u5bf9\u8bdd",
        onClick = onCreateConversation,
      )
    }

    Spacer(Modifier.height(8.dp))

    if (conversations.isEmpty()) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "\u7a7a",
            color = Color(0xFF9AA0AA),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = "\u2014\u2014 \u00b7 \u2014\u2014",
            color = DrawerColors.Yellow,
            fontSize = 13.sp,
            style =
              TextStyle(
                shadow =
                  Shadow(
                    color = Color(0x99F2C94C),
                    offset = Offset(0f, 0f),
                    blurRadius = 12f,
                  ),
              ),
          )
        }
      }
    } else {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState()),
      ) {
        conversations.forEach { summary ->
          ConversationRow(
            summary = summary,
            onClick = { onConversationSelected(summary.id) },
            onDelete = { onDeleteConversation(summary.id) },
          )
        }
      }
    }
  }
}

@Composable
private fun ConversationRow(
  summary: ConversationSummary,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val density = LocalDensity.current
  val deleteWidth = 72.dp
  val deleteWidthPx = with(density) { deleteWidth.toPx() }
  var targetOffsetPx by remember { mutableStateOf(0f) }
  var dragOffsetPx by remember { mutableStateOf(0f) }
  val targetOffsetDp = with(density) { targetOffsetPx.toDp() }
  val animatedOffset by animateDpAsState(
    targetValue = targetOffsetDp,
    animationSpec = tween(180, easing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)),
    label = "conversationDeleteReveal",
  )
  val contentOffset =
    if (abs(dragOffsetPx - targetOffsetPx) > 0.5f) {
      with(density) { dragOffsetPx.toDp() }
    } else {
      animatedOffset
    }
  val revealProgress = (-with(density) { contentOffset.toPx() } / deleteWidthPx).coerceIn(0f, 1f)
  val deleteVisibleWidth = deleteWidth * revealProgress
  val deleteSlideOffset = deleteWidth * (1f - revealProgress)
  val deleteAlpha = revealProgress

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(32.dp)
        .clip(RoundedCornerShape(8.dp)),
  ) {
    Box(
      modifier =
        Modifier
          .align(Alignment.CenterEnd)
          .offset(x = deleteSlideOffset)
          .width(deleteVisibleWidth)
          .height(28.dp)
          .alpha(deleteAlpha)
          .clickable(enabled = revealProgress > 0.92f, onClick = onDelete),
      contentAlignment = Alignment.Center,
    ) {
      if (deleteVisibleWidth >= 42.dp) {
        Text(
          text = "删除",
          color = DrawerColors.Danger,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    }
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(28.dp)
          .offset(x = contentOffset)
          .conversationChrome(selected = summary.selected)
          .pointerInput(summary.id) {
            detectHorizontalDragGestures(
              onDragEnd = {
                val shouldReveal = dragOffsetPx < -deleteWidthPx * 0.38f
                targetOffsetPx = if (shouldReveal) -deleteWidthPx else 0f
                dragOffsetPx = targetOffsetPx
              },
              onDragCancel = {
                dragOffsetPx = targetOffsetPx
              },
              onHorizontalDrag = { _, dragAmount ->
                dragOffsetPx = (dragOffsetPx + dragAmount).coerceIn(-deleteWidthPx, 0f)
              },
            )
          }
          .clickable {
            if (targetOffsetPx < 0f) {
              targetOffsetPx = 0f
              dragOffsetPx = 0f
            } else {
              onClick()
            }
          }
          .padding(horizontal = 8.dp),
      contentAlignment = Alignment.CenterStart,
    ) {
      Text(
        text = "${summary.title} \u00b7 ${summary.subtitle}",
        color = DrawerColors.Ink,
        fontSize = 13.sp,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun SmallLimeButton(
  text: String,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .height(38.dp)
        .limeButtonSurface()
        .clip(RoundedCornerShape(999.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "+ $text",
      color = DrawerColors.Ink,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

private object DrawerColors {
  val Background = Color(0xFFF8F4FA)
  val Card = Color.White
  val BrandPurple = Color(0xFF660874)
  val MenuBrandPurple = Color(0xFF7A35D8)
  val UnselectedText = Color(0xFF6B5C70)
  val Ink = Color(0xFF0E1117)
  val Muted = Color(0xFF737986)
  val Track = Color(0xFFF0EBF3)
  val Lime = Color(0xFFB7D83A)
  val Yellow = Color(0xFFF2C94C)
  val Danger = Color(0xFFB42318)
  val DangerSoft = Color(0x18B42318)
}

private fun Modifier.drawerSurface(): Modifier =
  drawBehind {
    drawRect(
      brush =
        Brush.radialGradient(
          colors = listOf(Color(0x70DFF8F5), DrawerColors.Background),
          center = androidx.compose.ui.geometry.Offset(size.width * 0.85f, size.height * 0.05f),
          radius = size.width * 0.9f,
        ),
    )
  }

private fun Modifier.segmentedTrack(): Modifier =
  drawBehind {
    val radius = size.height / 2f
    drawRoundRect(color = DrawerColors.Track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
    drawRoundRect(
      color = Color(0x1F660874),
      topLeft = androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx()),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
      style = Stroke(width = 2.dp.toPx()),
    )
    drawRoundRect(
      color = Color(0x14660874),
      topLeft = androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
  }

private fun Modifier.segmentedSlider(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val corner = size.height / 2f
    val paint =
      Paint().asFrameworkPaint().apply {
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
          colors = listOf(Color.White, Color(0xFFFEFEFE), Color(0xFFF9F9F9)),
        ),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )
    drawRoundRect(
      color = Color(0x33F2C94C),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
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

private fun Modifier.roundedSection(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = 18.dp.toPx()
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
        setShadowLayer(16.dp.toPx(), 0f, 4.dp.toPx(), Color(0x14660874).toArgb())
      }
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(color = DrawerColors.Card, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
  }

private fun Modifier.limeButtonSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = size.height / 2f
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = DrawerColors.Lime.toArgb()
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(6.dp.toPx(), 0f, 3.dp.toPx(), Color(0x24596916).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.setShadowLayer(22.dp.toPx(), 0f, 10.dp.toPx(), Color(0x38688414).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(
      color = DrawerColors.Lime,
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    )
    drawRoundRect(
      color = Color(0xFF98B82B),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
  }


private fun Modifier.conversationChrome(selected: Boolean): Modifier =
  drawBehind {
    if (selected) {
      drawRoundRect(
        color = Color(0xFFE9EBEF),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
      )
    }
    drawLine(
      color = Color(0x1A7C6E86),
      start = androidx.compose.ui.geometry.Offset(8.dp.toPx(), size.height),
      end = androidx.compose.ui.geometry.Offset(size.width - 8.dp.toPx(), size.height),
      strokeWidth = 1.dp.toPx(),
    )
  }

private fun Modifier.conversationDeleteButtonSurface(): Modifier =
  drawBehind {
    val rect = RectF(0f, 0f, size.width, size.height)
    val radius = size.height / 2f
    val paint =
      Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = DrawerColors.DangerSoft.toArgb()
        style = android.graphics.Paint.Style.FILL
      }
    paint.setShadowLayer(9.dp.toPx(), 0f, 4.dp.toPx(), DrawerColors.Danger.copy(alpha = 0.13f).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.setShadowLayer(7.dp.toPx(), 0f, 0f, DrawerColors.Yellow.copy(alpha = 0.13f).toArgb())
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, paint)
    paint.clearShadowLayer()
    drawRoundRect(DrawerColors.DangerSoft, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
    drawRoundRect(
      color = DrawerColors.Danger.copy(alpha = 0.22f),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
      style = Stroke(width = 1.dp.toPx()),
    )
    drawRoundRect(
      color = DrawerColors.Yellow.copy(alpha = 0.12f),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
      style = Stroke(width = 0.7.dp.toPx()),
    )
  }

