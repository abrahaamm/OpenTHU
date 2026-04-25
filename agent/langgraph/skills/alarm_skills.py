from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

try:
    from ..skill_core import SkillHandler, SkillInvocation, SkillResult
except ImportError:
    import sys
    import os
    sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from skill_core import SkillHandler, SkillInvocation, SkillResult

class SetAlarmSkill(SkillHandler):
    def invoke(
        self,
        invocation: SkillInvocation,
        session: dict[str, Any],
        state: dict[str, Any],
    ) -> SkillResult:
        args = invocation.args
        from uuid import uuid4
        
        return SkillResult(
            skill_name=invocation.skill_name,
            request_id=invocation.request_id,
            code="OK",
            data={
                "alarm_id": f"alm_{uuid4().hex[:10]}",
                "status": "set"
            },
            from_cache=False,
            fetched_at=datetime.now(timezone.utc).isoformat(),
            source="system_alarm",
        )
