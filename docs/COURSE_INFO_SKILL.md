# Course Info Skill

This branch replaces the previous static `NOT_CONFIGURED` course handlers with real Tsinghua Learn/WebVPN crawlers.

## Implemented

- `get_semesters`: reads the current and nearby semester metadata from Tsinghua Learn.
- `get_courses`: reads the course list for a semester and augments each course with time/location blocks.
- `get_course_schedule`: reads the dated teaching-calendar timetable through WebVPN when `webvpn_cookie` is configured; otherwise it returns Learn course time/location blocks when `learn_cookie` or `homework_cookie` is available.

## Required Session Values

Android sends these values through the Agent-Core session from Settings:

- `learn_base_url`, default `https://learn.tsinghua.edu.cn`
- `homework_cookie` or `learn_cookie`
- `homework_csrf` or `learn_csrf`, optional
- `webvpn_cookie`, required for the full dated timetable source used by `thu-info-app`

## Reference

The WebVPN timetable path follows `thu-info-community/thu-info-app`:

- `packages/thu-info-lib/src/lib/schedule.ts`
- `packages/thu-info-lib/src/models/schedule/schedule.ts`
- `packages/thu-info-lib/src/lib/basics.ts`

OpenTHU keeps the implementation in Python Agent-Core so the mobile app can reuse the existing conversation and event-stream flow.
