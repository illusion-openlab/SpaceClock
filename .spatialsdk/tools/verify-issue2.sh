#!/bin/bash
# 门禁 B（issue ② 面板不透明化，形状仿 verify-task10.sh）：
#   锁内装机 + 启动 + 等待 + 截图 + 立即停应用释放设备。锁内不做任何分析。
#
# 本轮取证目标（设计契约 .spatialsdk/design-contract.md §6）：
#   1) HOME：面板区域采样色是否 ≈ #0F1A29（fillLight 9% over fillSecondary），描边是否可见
#   2) ALARM：跨页采样一致性 +「＋ 新建闹钟」按钮是否可见
#   3) 点「＋ 新建闹钟」后：编辑器是否真的打开 —— 验证撤掉 backgroundMaterial 那层
#      合成器 modifier node（它带 depth 与 isCollisionEnable）之后，面板内输入没有回归
#
# 等待为什么是 ≥12s 的默认常量：`pico-cli capture screenshot` 返回落后 6–8s 的**陈旧帧**
# （AGENTS.md 实测）。Task 9 用 2–3s，拍到的是切页之前的画面，被迫补跑一整轮。不要调小。
set -u

D="${1:-emulator-5554}"
PKG=tech.illusion.spaceclock
OUTDIR=.spatialsdk/ui-verify
LOCK=/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh
# owner 串必须跨 Bash 调用稳定（不要用 $$）：acquire 与 release 得是同一个字符串。
OWNER=spaceclock-issue2

APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
SETTLE="${SETTLE:-12}"
MARKER_TIMEOUT="${MARKER_TIMEOUT:-120}"

export PICO_LOCK_MAX_WAIT="${PICO_LOCK_MAX_WAIT:-90}"
export PICO_HOME="${PICO_HOME:-/Users/zohar/Library/PICO/sdk}"

TAGS="ClockLayout:V AlarmLayout:V NavBar:V ClockVolume:V AndroidRuntime:E ActivityManager:W"

mkdir -p "$OUTDIR"
[ -f "$APK" ] || { echo "missing $APK"; exit 2; }

"$LOCK" status "$D"
if ! "$LOCK" acquire "$OWNER" 300 "$D"; then
  echo "LOCK BUSY — rerun: $0 $D"
  exit 1
fi
released=0
release_once() { [ "$released" = 1 ] && return; released=1; "$LOCK" release "$OWNER" "$D" 2>/dev/null; }
trap release_once EXIT

rc=0
nap() { adb -s "$D" shell sleep "$1" >/dev/null 2>&1; }
shot() { pico-cli capture screenshot -d "$D" -o "$OUTDIR/$1" || rc=1; }

wait_marker() { # $1=regex $2=timeout-seconds
  local i=0
  while [ "$i" -lt "$2" ]; do
    if adb -s "$D" logcat -d -s $TAGS 2>/dev/null | grep -q -- "$1"; then
      echo "marker hit: $1 (after ${i}s)"; return 0
    fi
    nap 2; i=$((i + 2))
  done
  echo "marker MISS: $1 (${2}s)"; return 1
}

launch_and_wait_pid() {
  pico-cli app launch -d "$D" "$PKG" || return 1
  local pid=""
  for _ in $(seq 1 20); do
    pid="$(adb -s "$D" shell pidof "$PKG" | tr -d '\r')"
    [ -n "$pid" ] && break
    nap 1
  done
  echo "pid=$pid"
  [ -n "$pid" ]
}

tap_logged() { # $1=log tag  $2=grep 模式；按实测坐标点中心
  local tag="$1" pat="$2" line x y w h cx cy
  line="$(adb -s "$D" logcat -d -s "$tag":V 2>/dev/null | grep "$pat" | tail -1)"
  if [ -z "$line" ]; then echo "tap_logged: no line for '$pat'"; return 1; fi
  w="$(echo "$line" | sed -n 's/.*w=\([0-9]*\).*/\1/p')"
  h="$(echo "$line" | sed -n 's/.* h=\([0-9]*\).*/\1/p')"
  x="$(echo "$line" | sed -n 's/.* x=\([0-9.]*\).*/\1/p')"
  y="$(echo "$line" | sed -n 's/.* y=\([0-9.]*\).*/\1/p')"
  cx="$(awk "BEGIN{printf \"%d\", $x + $w/2}")"
  cy="$(awk "BEGIN{printf \"%d\", $y + $h/2}")"
  echo "tap_logged '$pat' -> ($cx,$cy)  [$line]"
  adb -s "$D" shell input tap "$cx" "$cy"
}

echo "=== issue2 · opaque content panel ==="
adb -s "$D" logcat -c
pico-cli app install -d "$D" -r -g "$APK" || rc=1
if [ "$rc" = 0 ]; then launch_and_wait_pid || rc=1; fi
if [ "$rc" = 0 ]; then
  # 新的日志标记：面板打点已从 glassPanel 改名为 contentPanel（它不再是玻璃）。
  wait_marker "contentPanel w=" "$MARKER_TIMEOUT"
  nap "$SETTLE"
  shot "issue2-1-home.png"

  tap_logged ClockLayout "navButton page=ALARM"; nap "$SETTLE"
  shot "issue2-2-alarm.png"

  tap_logged AlarmLayout "newAlarm w="; nap "$SETTLE"
  shot "issue2-3-alarm-editor.png"

  adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/issue2-logcat.txt" 2>&1
fi
pico-cli app stop -d "$D" "$PKG"
release_once

echo "rc=$rc"
exit "$rc"
