package ai.opencray.app.system

import ai.opencray.app.data.model.RuntimeSnapshot

class SystemContextCoordinator {
  fun summarize(snapshot: RuntimeSnapshot): String {
    val sources = snapshot.contextSignals.joinToString { it.source }
    return "Sources: $sources"
  }
}
