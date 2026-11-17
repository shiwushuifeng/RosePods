#!/usr/bin/env bash
# RosePods 一键部署：编译 → 安装 → 在 Vector/LSPosed 中启用 → 重启蓝牙进程
#
# 用法：tools/deploy.sh [--no-build]
# 依赖：adb（设备已授权）、本机 Gradle 9.6.1、JDK 21、Android SDK 36
set -euo pipefail

ADB="${ADB:-adb}"
PKG="moe.chenxy.rosepods"
BT="com.android.bluetooth"
GRADLE="${GRADLE:-/c/Users/Qi/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle.bat}"

cd "$(dirname "$0")/.."

if [ "${1:-}" != "--no-build" ]; then
  echo "==> 编译"
  ANDROID_HOME='C:\Android\sdk' \
  ANDROID_SDK_ROOT='C:\Android\sdk' \
  JAVA_HOME='C:\Android\jdk-21' \
    "$GRADLE" assembleDebug --offline --console=plain | tail -5
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "缺少 $APK"; exit 1; }

echo "==> 检查设备"
"$ADB" wait-for-device
"$ADB" devices

echo "==> 推送 APK"
"$ADB" push "$APK" /data/local/tmp/rosepods.apk >/dev/null

echo "==> 安装（install-session，避开 pm install -r 限制）"
SESSION=$("$ADB" shell su -c "pm install-create -r -i $PKG" | tr -dc '0-9')
[ -n "$SESSION" ] || { echo "无法创建安装会话"; exit 1; }
"$ADB" shell su -c "pm install-write $SESSION base /data/local/tmp/rosepods.apk" >/dev/null
"$ADB" shell su -c "pm install-commit $SESSION"

echo "==> 在 Vector/LSPosed 中启用模块"
"$ADB" shell su -c "/data/adb/lspd/cli modules enable $PKG" || \
  echo "！自动启用失败：请在 LSPosed/Vector 管理器里手动启用并勾选 $BT"

echo "==> 设置作用域"
"$ADB" shell su -c "/data/adb/lspd/cli scope set $PKG $BT/0" || \
  echo "！作用域设置失败：请在管理器里手动勾选 $BT"

echo "==> 重启蓝牙进程使模块生效"
"$ADB" shell su -c "am force-stop $BT"
sleep 3
"$ADB" shell su -c "svc bluetooth enable"

echo "==> 最近日志"
sleep 2
"$ADB" logcat -d -s RosePods:* 2>/dev/null | tail -40 || true

cat <<'EOF'

完成。验证步骤：
  1. adb logcat -s RosePods:*     # 应看到 SystemBridge attached
  2. 连接耳机后，日志出现 connecting / 电量 / 降噪模式
  3. 打开 设置 → 蓝牙 → ROSE Ceramics U，查看电量
  4. 下拉快捷开关，添加「RosePods」磁贴测试切换

如果没反应：
  - 确认模块已启用且作用域含 com.android.bluetooth
  - adb shell su -c 'am force-stop com.android.bluetooth' 后重新打开蓝牙
EOF