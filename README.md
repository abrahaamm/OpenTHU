# OpenCray

OpenCray is an Android system-agent prototype built on top of an OpenClaw-style runtime layout.

Instead of acting like a plain chat client, OpenCray is oriented around:

- `Context`: what is happening on the phone right now
- `Actions`: what the agent can do across apps and system surfaces
- `Safety`: what must be approved, logged, and auditable

## Current prototype

The current Android app includes:

- A `Context` page with mocked notification/share/foreground-app signals
- An `Actions` page with:
  - agent link settings
  - capability toggles
  - executable prototype actions
  - common-app detection and app launch buttons
- A `Safety` page with pending approval and audit log simulation
- A common-app test panel that can detect and launch:
  - WeChat
  - Alipay
  - Amap
  - Taobao
  - Meituan
  - QQ

## Testable app-launch actions

The prototype can detect and try to launch several common Android apps if they are installed:

- WeChat
- Alipay
- Amap
- Taobao
- Meituan
- QQ

This makes it possible to verify that OpenCray is no longer just rendering text; it can now trigger real app-opening behavior on device.

## APK scripts

```bash
./scripts/download_official_apks.sh
./scripts/install_apks.sh
./scripts/verify_common_apps.sh
```

At the moment, the repository includes one APK that was downloaded from an official public source:

- `test-apks/amap-official.apk`

For several other common apps, the official sites do not expose a stable public APK URL suitable for scripted download, so the script leaves notes in `test-apks/README.txt` and expects manual installation from official channels.

## Build

If your local Gradle config contains a stale proxy, use:

```bash
cd OpenCray
./gradlew -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort= :app:assembleDebug
```

## Next steps

- Replace mocked context with real `NotificationListenerService` and share-intent ingestion
- Introduce real `AccessibilityService` execution for cross-app workflows
- Expand safety into per-action approval, provenance, and replay
