package ai.opencray.app.system

import android.content.Context
import android.content.Intent
import ai.opencray.app.domain.model.CommonApp

class AppLaunchController(
  private val context: Context,
) {
  fun launch(app: CommonApp): Boolean {
    val packageManager = context.packageManager
    val launchIntent =
      packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    if (launchIntent == null) {
      return false
    }
    context.startActivity(launchIntent)
    return true
  }
}
