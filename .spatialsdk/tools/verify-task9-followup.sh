#!/bin/bash
# Task 9 补充轮：verify-task9.sh 的截图普遍卡在"上一步"的画面(AGENTS.md 已知的截图滞后
# 陷阱,~6s 旧帧),这轮把每次操作后的等待放宽到 12s,换取"文件名和画面对得上"的干净证据。
set -u
D="${1:-emulator-5554}"
PKG=tech.illusion.spaceclock
OUTDIR=.spatialsdk/ui-verify
LOCK=/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh
OWNER=spaceclock-task9-followup
export PICO_LOCK_MAX_WAIT="${PICO_LOCK_MAX_WAIT:-90}"
export PICO_HOME="${PICO_HOME:-/Users/zohar/Library/PICO/sdk}"

mkdir -p "$OUTDIR"
"$LOCK" status "$D"
if ! "$LOCK" acquire "$OWNER" 240 "$D"; then
  echo "LOCK BUSY"
  exit 1
fi
released=0
release_once() { [ "$released" = 1 ] && return; released=1; "$LOCK" release "$OWNER" "$D" 2>/dev/null; }
trap release_once EXIT

rc=0
tap_nav() {
  local page="$1" line x y w h cx cy
  line="$(adb -s "$D" logcat -d -s ClockLayout:V 2>/dev/null | grep "navButton page=$page" | tail -1)"
  [ -z "$line" ] && { echo "no navButton line for $page"; return 1; }
  w="$(echo "$line" | sed -n 's/.*w=\([0-9]*\).*/\1/p')"
  h="$(echo "$line" | sed -n 's/.*h=\([0-9]*\).*/\1/p')"
  x="$(echo "$line" | sed -n 's/.*x=\([0-9.]*\).*/\1/p')"
  y="$(echo "$line" | sed -n 's/.*y=\([0-9.]*\).*/\1/p')"
  cx="$(awk "BEGIN{printf \"%d\", $x + $w/2}")"
  cy="$(awk "BEGIN{printf \"%d\", $y + $h/2}")"
  echo "tap_nav $page -> ($cx,$cy)"
  adb -s "$D" shell input tap "$cx" "$cy"
}

pico-cli app launch -d "$D" "$PKG" || rc=1
if [ "$rc" = 0 ]; then
  for _ in $(seq 1 20); do
    pid="$(adb -s "$D" shell pidof "$PKG" | tr -d '\r')"
    [ -n "$pid" ] && break
    adb -s "$D" shell sleep 1 >/dev/null 2>&1
  done
  echo "pid=$pid"
  adb -s "$D" shell sleep 12 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-v2-1-online-home.png" || rc=1

  tap_nav WORLD
  adb -s "$D" shell sleep 12 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-v2-2-online-world.png" || rc=1

  adb -s "$D" shell svc wifi disable
  adb -s "$D" shell sleep 3 >/dev/null 2>&1
  adb -s "$D" shell dumpsys wifi | grep -i "Wi-Fi is" | head -1

  tap_nav HOME
  adb -s "$D" shell sleep 12 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-v2-3-offline-home.png" || rc=1

  adb -s "$D" shell svc wifi enable
fi
pico-cli app stop -d "$D" "$PKG"
release_once
echo "rc=$rc"
exit "$rc"
