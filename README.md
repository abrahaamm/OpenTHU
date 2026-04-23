# OpenTHU

OpenTHU is an Android system-agent prototype built on top of an OpenClaw-style runtime layout.

Its goal is not to be just another chat client. The project is organized around three core ideas:

- `Context`: capture what is happening on the phone right now
- `Actions`: turn that context into executable mobile actions
- `Safety`: make those actions reviewable, controllable, and auditable

## Current scope

The current version is an Android prototype with:

- a `Context / Actions / Safety` app structure
- a lightweight runtime, repository, and domain model layout
- mocked mobile context signals
- prototype system actions and safety records
- common-app detection and launch testing for:
  - WeChat
  - Alipay
  - Amap
  - Taobao
  - Meituan
  - QQ

This means OpenTHU can already be used to verify a basic system-agent loop on device:

1. detect whether common apps are installed
2. expose them as candidate action targets
3. trigger real app launches
4. write the result back into the runtime state and event log

## What is still missing

The project is still a prototype. The major unfinished areas are:

- real context ingestion
  - notifications
  - share intents
  - foreground-app state
- real cross-app execution
  - deep links
  - structured intents
  - accessibility-driven UI automation
- stronger safety controls
  - per-action approval
  - execution provenance
  - replayable audit trails
- backend integration
  - real OpenClaw gateway/protocol connection
  - planner/tool execution loop
  - persistent task state

In short:

> OpenClaw provides the runtime direction; OpenTHU is meant to become the Android-side eyes, hands, and safety layer.

## Scripts

```bash
./scripts/download_official_apks.sh
./scripts/install_apks.sh
./scripts/verify_common_apps.sh
```

These scripts support local testing of the common-app launch flow. At the moment:

- `Amap` is included as a locally downloaded official APK for testing
- several other apps must still be installed manually from their official channels, because their public sites do not expose stable scripted APK download links

## Build

If your local Gradle config contains a stale proxy, build with:

```bash
cd OpenTHU
./gradlew -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort= :app:assembleDebug
```

## LangGraph Agent Pipeline

A standalone LangGraph implementation of the agent flow is available at:

- `agent/langgraph/openthu_agent.py`
- `agent/langgraph/README.md`

Pipeline order:

1. requirement normalization
2. planning
3. safety review
4. execution
5. failed-action replanning
6. audit record
7. memory update
