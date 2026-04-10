package ai.opencray.app.di

import ai.opencray.app.data.repository.ChatRepository
import ai.opencray.app.data.repository.RuntimeRepository
import ai.opencray.app.runtime.OpenCrayRuntime

interface AppContainer {
  val runtimeRepository: RuntimeRepository
  val chatRepository: ChatRepository
  val runtime: OpenCrayRuntime
}
