#!/usr/bin/env bash
set -euo pipefail

ADB_BIN="${ADB:-}"

if [ -z "$ADB_BIN" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
  elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
  else
    echo "adb not found"
    exit 1
  fi
fi

APPS=(
  "WeChat:com.tencent.mm"
  "Alipay:com.eg.android.AlipayGphone"
  "Amap:com.autonavi.minimap"
  "Taobao:com.taobao.taobao"
  "Meituan:com.sankuai.meituan"
  "QQ:com.tencent.mobileqq"
)

is_installed() {
  local pkg="$1"
  "$ADB_BIN" shell pm list packages | grep -q "$pkg"
}

echo "Installed packages:"
for entry in "${APPS[@]}"; do
  label="${entry%%:*}"
  pkg="${entry#*:}"
  if is_installed "$pkg"; then
    echo "  [ok] $label ($pkg)"
  else
    echo "  [--] $label ($pkg)"
  fi
done

echo
echo "Launch smoke test:"
for entry in "${APPS[@]}"; do
  label="${entry%%:*}"
  pkg="${entry#*:}"
  if is_installed "$pkg"; then
    echo "  launching $label"
    "$ADB_BIN" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
  fi
done

echo "Verification pass complete."
