#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_DIR="$ROOT_DIR/test-apks"

mkdir -p "$APK_DIR"

echo "[1/2] Downloading official Amap APK..."
curl -L \
  -A 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Mobile Safari/537.36' \
  -e 'https://wap.amap.com/?from=m&type=m' \
  'https://mdp.amap.com/channel/download?custom_id=C0211000325983' \
  -o "$APK_DIR/amap-official.apk"

echo "[2/2] Writing notes for apps without stable public APK links..."
cat > "$APK_DIR/README.txt" <<'EOF'
Downloaded from official source:
- amap-official.apk

Not auto-downloaded:
- WeChat
- Alipay
- Taobao
- Meituan
- QQ

Reason:
- Their official sites either do not expose a stable public APK URL, or redirect through dynamic/app-store flows that are not suitable for reliable scripted download.

Recommended approach:
- Install those apps manually from their official distribution channels.
- Then use scripts/install_apks.sh and scripts/verify_common_apps.sh together with the OpenCray Actions page for launch verification.
EOF

echo "Done. Files saved in: $APK_DIR"
