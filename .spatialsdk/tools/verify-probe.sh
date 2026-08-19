#!/bin/bash
# 门禁 B：锁内装机 + 启动 + 截图 + 立即停应用释放设备。
# 锁内不做任何分析（读图 / 翻 logcat / 跑 gradle 都在锁外）。
# 用法: .spatialsdk/tools/verify-probe.sh [device-serial]
set -u

D="${1:-emulator-5554}"
PKG=tech.illusion.spaceclock
APK=app/build/outputs/apk/debug/app-debug.apk
OUTDIR=.spatialsdk/ui-verify
LOCK=/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh
OWNER=spaceclock-task0

export PICO_LOCK_MAX_WAIT=90
export PICO_HOME=/Users/zohar/Library/PICO/sdk

mkdir -p "$OUTDIR"
"$LOCK" status "$D"
if ! "$LOCK" acquire "$OWNER" 120 "$D"; then
  echo "LOCK BUSY — rerun: $0 $D"
  exit 1
fi
released=0
release_once() { [ "$released" = 1 ] && return; released=1; "$LOCK" release "$OWNER" "$D" 2>/dev/null; }
trap release_once EXIT

rc=0
adb -s "$D" logcat -c
pico-cli app install -d "$D" -r -g "$APK" || rc=1
if [ "$rc" = 0 ]; then
  pico-cli app launch -d "$D" "$PKG" || rc=1
fi
if [ "$rc" = 0 ]; then
  pid=""
  for _ in $(seq 1 20); do
    pid="$(adb -s "$D" shell pidof "$PKG" | tr -d '\r')"
    [ -n "$pid" ] && break
    adb -s "$D" shell sleep 1
  done
  echo "pid=$pid"
  # 进程起来之后还要等窗口 + 12.8MB usdz 解码 + 首帧；固定 sleep 会拍到空房间。
  adb -s "$D" shell sleep 12
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task0-1.png" || rc=1
  # 第二张隔开一段，用来对比：两张像素完全一致说明根本没渲染。同时给前台服务留出 2 个 tick。
  adb -s "$D" shell sleep 8
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task0-2.png" || rc=1
fi
pico-cli app stop -d "$D" "$PKG"
release_once

# 锁外：dump 日志留证据（只读，不分析）。
adb -s "$D" logcat -d -s ClockProbe:V ProbeService:V AndroidRuntime:E ActivityManager:W \
  > "$OUTDIR/task0-logcat.txt" 2>&1
echo "rc=$rc"
exit "$rc"
