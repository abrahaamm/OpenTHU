package ai.opencray.app.ui.settings

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontWeight.Companion.Medium
import androidx.compose.ui.text.font.FontWeight.Companion.SemiBold
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SettingsColors {
  val Background = Color(0xFFF8F4FA)
  val BrandPurple = Color(0xFF660874)
  val Track = Color(0xFFF0EBF3)
  val UnselectedText = Color(0xFF6B5C70)
  val BodyText = Color(0xFF1F1F1F)
  val Card = Color.White
  val Yellow = Color(0xFFF2C94C)
  val RowPressed = Color(0x99F8F4FA)
  val SliderTop = Color.White
  val SliderMiddle = Color(0xFFFEFEFE)
  val SliderBottom = Color(0xFFF9F9F9)
  val Divider = Color(0x14660874)
}

object SettingsTypography {
  val TitleSize: TextUnit = 28.sp
  val TitleWeight: FontWeight = Medium
  val TabSize: TextUnit = 15.sp
  val TabSelectedWeight: FontWeight = SemiBold
  val TabWeight: FontWeight = Medium
  val BodySize: TextUnit = 16.sp
  val BodyWeight: FontWeight = Medium
}

object SettingsSpacing {
  val ScreenHorizontal: Dp = 20.dp
  val ScreenVertical: Dp = 32.dp
  val TitleBottom: Dp = 32.dp
  val SegmentWidth: Dp = 320.dp
  val SegmentHeight: Dp = 54.dp
  val SegmentPadding: Dp = 5.dp
  val SegmentBottom: Dp = 32.dp
  val RowHorizontal: Dp = 20.dp
  val RowVertical: Dp = 16.dp
  val RowIconGap: Dp = 16.dp
}

object SettingsShapes {
  val Pill: Dp = 999.dp
  val CardRadius: Dp = 16.dp
}

object SettingsMotion {
  const val DurationMillis = 400
  val Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}
