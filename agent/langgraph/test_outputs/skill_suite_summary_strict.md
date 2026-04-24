# skill_suite_strict_logs.json

- approve_sensitive: `False`
- case_count: `19`
- missing_skills: `[]`

| idx | case | code | planned_skills | blocked_skills | trace_nodes |
|---:|---|---|---|---|---|
| 1 | login | SKILL_EXECUTION_FAILED | login,get_courses,get_notices,show_summary | login | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 2 | refresh_session | APPROVAL_REQUIRED | refresh_session | refresh_session | normalize_requirement,plan_skills,safety_check,execute_skills,audit_record,memory_update |
| 3 | logout | APPROVAL_REQUIRED | login,logout | login,logout | normalize_requirement,plan_skills,safety_check,execute_skills,audit_record,memory_update |
| 4 | get_user_info | SKILL_EXECUTION_FAILED | get_user_info | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 5 | get_semesters | SKILL_EXECUTION_FAILED | get_semesters | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 6 | get_courses | SKILL_EXECUTION_FAILED | get_semesters,get_courses,show_summary | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 7 | get_notices | SKILL_EXECUTION_FAILED | get_courses,get_notices,show_summary | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 8 | get_files | SKILL_EXECUTION_FAILED | get_courses,get_files,show_summary | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 9 | get_assignments | SKILL_EXECUTION_FAILED | get_courses,get_assignments,show_summary | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 10 | get_academic_calendar | SKILL_EXECUTION_FAILED | get_academic_calendar,create_calendar_event | create_calendar_event | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 11 | get_campus_activities | SKILL_EXECUTION_FAILED | get_campus_activities,show_summary | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 12 | search | SKILL_EXECUTION_FAILED | get_courses,get_assignments,get_notices,search,show_summary | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 13 | create_reminder | SKILL_EXECUTION_FAILED | get_courses,get_assignments,show_summary,create_reminder | create_reminder | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 14 | create_calendar_event | SKILL_EXECUTION_FAILED | get_courses,get_assignments,show_summary,create_calendar_event | create_calendar_event | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 15 | set_alarm | SKILL_EXECUTION_FAILED | set_alarm | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 16 | show_summary | SKILL_EXECUTION_FAILED | show_summary | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 17 | send_notification | SKILL_EXECUTION_FAILED | create_reminder,send_notification | create_reminder | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 18 | open_url | SKILL_EXECUTION_FAILED | open_url | - | normalize_requirement,plan_skills,safety_check,execute_skills,replan_failed,audit_record,memory_update |
| 19 | launch_app | APPROVAL_REQUIRED | launch_app | launch_app | normalize_requirement,plan_skills,safety_check,execute_skills,audit_record,memory_update |