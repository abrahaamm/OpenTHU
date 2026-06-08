package ai.opencray.app.di

import android.content.Context
import ai.opencray.app.data.repository.ChatRepository
import ai.opencray.app.data.repository.FakeChatRepository
import ai.opencray.app.data.repository.FakeRuntimeRepository
import ai.opencray.app.data.repository.RuntimeRepository
import ai.opencray.app.data.repository.SharedPreferencesRuntimeMemoryStore
import ai.opencray.app.runtime.OpenCrayRuntime

class DefaultAppContainer(context: Context) : AppContainer {
  override val runtimeRepository: RuntimeRepository =
    FakeRuntimeRepository(memoryStore = SharedPreferencesRuntimeMemoryStore(context.applicationContext))
  override val chatRepository: ChatRepository = FakeChatRepository()
  override val runtime: OpenCrayRuntime =
    OpenCrayRuntime(
      appContext = context.applicationContext,
      runtimeRepository = runtimeRepository,
      chatRepository = chatRepository,
    )
}
