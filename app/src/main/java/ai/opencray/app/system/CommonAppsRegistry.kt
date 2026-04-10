package ai.opencray.app.system

import android.content.Context
import android.content.pm.PackageManager
import ai.opencray.app.domain.model.CommonApp

class CommonAppsRegistry(
  private val context: Context,
) {
  fun resolveInstalledApps(apps: List<CommonApp>): List<CommonApp> {
    val packageManager = context.packageManager
    return apps.map { app ->
      app.copy(installed = isInstalled(packageManager, app.packageName))
    }
  }

  private fun isInstalled(
    packageManager: PackageManager,
    packageName: String,
  ): Boolean =
    try {
      packageManager.getPackageInfo(packageName, 0)
      true
    } catch (_: Exception) {
      false
    }
}
