#!/usr/bin/env bash
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found"
  exit 1
fi

declare -A APPS=(
  [WeChat]="com.tencent.mm"
  [Alipay]="com.eg.android.AlipayGphone"
  [Amap]="com.autonavi.minimap"
  [Taobao]="com.taobao.taobao"
  [Meituan]="com.sankuai.meituan"
  [QQ]="com.tencent.mobileqq"
)

echo "Installed packages:"
for label in "${!APPS[@]}"; do
  pkg="${APPS[$label]}"
  if adb shell pm list packages | grep -q "$pkg"; then
    echo "  [ok] $label ($pkg)"
  else
    echo "  [--] $label ($pkg)"
  fi
done

echo
echo "Launch smoke test:"
for label in Amap WeChat Alipay Taobao Meituan QQ; do
  pkg="${APPS[$label]}"
  if adb shell pm list packages | grep -q "$pkg"; then
    echo "  launching $label"
    adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null
  fi
done

echo "Verification pass complete."
