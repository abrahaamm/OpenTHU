package ai.opencray.app.agent

import ai.opencray.app.data.model.RuntimeSnapshot
import ai.opencray.app.domain.model.SystemAction

class ActionPlanner {
  fun recommendedAction(snapshot: RuntimeSnapshot): SystemAction? = snapshot.systemActions.firstOrNull()
}
