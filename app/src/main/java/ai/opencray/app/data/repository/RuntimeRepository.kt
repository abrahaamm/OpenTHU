package ai.opencray.app.data.repository

import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.CommonApp

interface RuntimeRepository {
  fun getSnapshot(): RuntimeSnapshot

  fun replaceSnapshot(snapshot: RuntimeSnapshot)

  fun markRuntimeBooted()

  fun updateConnectionStatus(status: String)

  fun updateConnectionConfig(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
  )

  fun setCapabilityEnabled(
    capabilityId: String,
    enabled: Boolean,
  )

  fun markActionExecuted(actionId: String)

  fun approvePendingSafety()

  fun updateCommonApps(apps: List<CommonApp>)

  fun appendEvent(event: String)
}
