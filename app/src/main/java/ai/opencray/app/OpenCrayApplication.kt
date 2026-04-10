package ai.opencray.app

import android.app.Application
import ai.opencray.app.di.AppContainer
import ai.opencray.app.di.DefaultAppContainer

class OpenCrayApplication : Application() {
  lateinit var appContainer: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    appContainer = DefaultAppContainer(this)
  }
}
