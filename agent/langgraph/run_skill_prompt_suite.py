from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

try:
    from .openthu_agent import OpenTHULangGraphAgent
except ImportError:
    from openthu_agent import OpenTHULangGraphAgent


def build_suite_cases() -> list[dict[str, str]]:
    return [
        {"name": "login", "prompt": "请帮我登录清华信息门户"},
        {"name": "refresh_session", "prompt": "请帮我刷新会话"},
        {"name": "logout", "prompt": "请帮我退出登录"},
        {"name": "get_user_info", "prompt": "帮我查一下我的个人信息和院系"},
        {"name": "get_semesters", "prompt": "帮我获取学期列表"},
        {"name": "get_courses", "prompt": "帮我获取当前学期课程信息"},
        {"name": "get_notices", "prompt": "帮我获取当前清华信息门户的消息"},
        {"name": "get_files", "prompt": "帮我获取课程文件和课件"},
        {"name": "get_assignments", "prompt": "请读取本学期所有课程作业和DDL"},
        {"name": "crawl_course_homeworks", "prompt": "请抓取我这学期所有课程的全部作业"},
        {"name": "crawl_unsubmitted_homeworks", "prompt": "请抓取我所有未提交作业"},
        {"name": "preview_homework_attachments", "prompt": "查看指定作业提交窗口里的附件"},
        {"name": "upload_homework_attachment", "prompt": "把本地作业附件上传到作业提交窗口"},
        {"name": "submit_homework", "prompt": "把作业文本和附件一起提交"},
        {"name": "get_academic_calendar", "prompt": "帮我查看教务日历和校历"},
        {"name": "get_campus_activities", "prompt": "帮我看近期校园活动和讲座"},
        {"name": "search", "prompt": "帮我搜索机器学习相关作业和通知"},
        {"name": "create_reminder", "prompt": "帮我创建一个提醒事项，提醒我明天交作业"},
        {"name": "create_calendar_event", "prompt": "把这周的作业DDL加到日历"},
        {"name": "set_alarm", "prompt": "请帮我设置一个明早8点的闹钟"},
        {"name": "show_summary", "prompt": "帮我展示当前学习任务摘要"},
        {"name": "send_notification", "prompt": "请给我推送一个学习提醒notification"},
        {"name": "open_url", "prompt": "请打开这个链接 https://learn.tsinghua.edu.cn"},
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="Run prompt-driven skill workflow suite")
    parser.add_argument(
        "--output",
        default="/tmp/openthu_skill_suite_full_logs.json",
        help="Path to output JSON file",
    )
    parser.add_argument(
        "--memory-file",
        default="/tmp/openthu_memory_suite.json",
        help="Path to memory JSON file used in suite",
    )
    parser.add_argument(
        "--approve-sensitive",
        action="store_true",
        help="Approve medium/high-risk skills during execution",
    )
    parser.add_argument(
        "--user-id",
        default="skill_suite_user",
        help="User id for suite execution",
    )
    parser.add_argument(
        "--llm-model",
        default="gpt-4.1-mini",
        help="Optional LLM model name",
    )
    parser.add_argument(
        "--llm-base-url",
        default="",
        help="Optional OpenAI-compatible base URL",
    )
    args = parser.parse_args()

    agent = OpenTHULangGraphAgent(
        memory_file=Path(args.memory_file),
        llm_model=args.llm_model,
        llm_base_url=args.llm_base_url,
    )

    cases = build_suite_cases()
    results: list[dict[str, Any]] = []
    covered: set[str] = set()

    for idx, case in enumerate(cases, start=1):
        run_state = agent.run(
            user_input=case["prompt"],
            user_id=args.user_id,
            approve_sensitive=args.approve_sensitive,
            semester_id="2025-2026-2",
        )
        final_response = run_state.get("final_response", {})
        data = final_response.get("data", {})
        planned = [item.get("skill_name", "") for item in data.get("skill_plan", [])]
        executed = [item.get("skill_name", "") for item in data.get("skill_results", [])]
        blocked = [item.get("skill_name", "") for item in data.get("blocked_skills", [])]
        covered.update(planned)
        covered.update(executed)
        covered.update(blocked)

        results.append(
            {
                "index": idx,
                "case_name": case["name"],
                "prompt": case["prompt"],
                "final_response": final_response,
            }
        )

    all_skills = {item["name"] for item in cases}
    missing = sorted(all_skills - covered)

    report = {
        "suite_meta": {
            "approve_sensitive": args.approve_sensitive,
            "case_count": len(cases),
            "covered_skills": sorted(covered),
            "missing_skills": missing,
            "output_file": args.output,
            "memory_file": args.memory_file,
        },
        "cases": results,
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report["suite_meta"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
