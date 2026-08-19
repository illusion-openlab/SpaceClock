#!/bin/bash
# 门禁 B（Task 10 收尾轮，仿 verify-task8fix.sh 的形状）：
#   锁内装机 + 启动 + 等待 + 截图 + 立即停应用释放设备。锁内不做任何分析。
#
# 本轮把 controller notes §E 要求的全部取证并进**一个**锁窗口：
#   A 段 = 控制组（HEAD 未修的 APK）：拍 HOME/WORLD 两页 + 抓修复前的 ClockLayout 坐标。
#   B 段 = 实验组（修完的 APK，带 GLYPH-PROBE 临时钩子）：五页全量 + 修复后的坐标。
# 两段用**两个不同的 APK**，所以必须各装一次；但装机与截图都在同一个锁窗口内，
# 全程只抢一次共享设备（每轮设备固定成本 4–6 分钟，见 dispatch-policy.md）。
#
# 为什么等待写成 ≥12s 的默认常量（AGENTS.md 已实测的坑）：
#   `pico-cli capture screenshot` 会返回落后 6–8 秒的**陈旧帧**。Task 9 用 2–3s 的等待，
#   结果拍到的是切页之前的画面，被迫补跑了一整轮——省下来的时间被吃回去还倒亏。
#   所以首帧和每次交互后一律等 SETTLE 秒，不要为了赶时间调小它。
set -u

D="${1:-emulator-5554}"
PKG=tech.illusion.spaceclock
OUTDIR=.spatialsdk/ui-verify
LOCK=/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh
# owner 串必须跨 Bash 调用稳定（不要用 $$）：acquire 与 release 得是同一个字符串。
OWNER=spaceclock-task10

SCRATCH=/private/tmp/claude-501/-Users-zohar-WorkSpace-Project-PicoProjects/160e6abd-7201-4e61-87e2-58b98724a18a/scratchpad
APK_CONTROL="${APK_CONTROL:-$SCRATCH/app-control-HEAD.apk}"
APK_FIXED="${APK_FIXED:-$SCRATCH/app-fixed-hook.apk}"

# 陈旧帧的安全余量：实测偏差 6–8s，这里留到 12s 并且**不给更小的取值**。
SETTLE="${SETTLE:-12}"
MARKER_TIMEOUT="${MARKER_TIMEOUT:-120}"

export PICO_LOCK_MAX_WAIT="${PICO_LOCK_MAX_WAIT:-90}"
export PICO_HOME="${PICO_HOME:-/Users/zohar/Library/PICO/sdk}"

TAGS="ClockLayout:V WorldLayout:V AlarmLayout:V NavBar:V ClockVolume:V TimingService:V TimerTick:V WeatherRepository:V EarthScene:V AndroidRuntime:E ActivityManager:W"

mkdir -p "$OUTDIR"
[ -f "$APK_CONTROL" ] || { echo "missing $APK_CONTROL"; exit 2; }
[ -f "$APK_FIXED" ]   || { echo "missing $APK_FIXED"; exit 2; }

"$LOCK" status "$D"
if ! "$LOCK" acquire "$OWNER" 300 "$D"; then
  echo "LOCK BUSY — rerun: $0 $D"
  exit 1
fi
released=0
release_once() { [ "$released" = 1 ] && return; released=1; "$LOCK" release "$OWNER" "$D" 2>/dev/null; }
trap release_once EXIT

rc=0

# 等待一律走 adb shell sleep（设备侧），前台 sleep 在本 harness 里被禁。
nap() { adb -s "$D" shell sleep "$1" >/dev/null 2>&1; }

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

tap_nav() { # $1=page name; 按 navButton 的实测坐标点中心
  local page="$1" line x y w h cx cy
  line="$(adb -s "$D" logcat -d -s ClockLayout:V 2>/dev/null | grep "navButton page=$page" | tail -1)"
  if [ -z "$line" ]; then echo "tap_nav: no navButton line for $page"; return 1; fi
  w="$(echo "$line" | sed -n 's/.*w=\([0-9]*\).*/\1/p')"
  h="$(echo "$line" | sed -n 's/.* h=\([0-9]*\).*/\1/p')"
  x="$(echo "$line" | sed -n 's/.* x=\([0-9.]*\).*/\1/p')"
  y="$(echo "$line" | sed -n 's/.* y=\([0-9.]*\).*/\1/p')"
  cx="$(awk "BEGIN{printf \"%d\", $x + $w/2}")"
  cy="$(awk "BEGIN{printf \"%d\", $y + $h/2}")"
  echo "tap_nav $page -> ($cx,$cy)  [$line]"
  adb -s "$D" shell input tap "$cx" "$cy"
}

shot() { pico-cli capture screenshot -d "$D" -o "$OUTDIR/$1" || rc=1; }

# ============================ A 段：控制组（HEAD 未修） ============================
echo "=== PHASE A · control (unfixed HEAD) ==="
adb -s "$D" logcat -c
pico-cli app install -d "$D" -r -g "$APK_CONTROL" || rc=1
if [ "$rc" = 0 ]; then
  launch_and_wait_pid || rc=1
fi
if [ "$rc" = 0 ]; then
  wait_marker "glassPanel w=" "$MARKER_TIMEOUT"
  nap "$SETTLE"
  shot "task10-a1-control-home.png"
  tap_nav WORLD; nap "$SETTLE"
  shot "task10-a2-control-world.png"
  # 修复前的坐标：面板底边 vs 按钮条顶边的垂直重叠就从这份日志算出来。
  adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/task10-a-control-logcat.txt" 2>&1
fi
pico-cli app stop -d "$D" "$PKG"
nap 2

# ============================ B 段：实验组（修完 + 临时钩子） ============================
echo "=== PHASE B · fixed ==="
adb -s "$D" logcat -c
pico-cli app install -d "$D" -r -g "$APK_FIXED" || rc=1
if [ "$rc" = 0 ]; then
  launch_and_wait_pid || rc=1
fi
if [ "$rc" = 0 ]; then
  wait_marker "glassPanel w=" "$MARKER_TIMEOUT"
  nap "$SETTLE"
  # HOME 同时承载三件事：②的实验组对照、GLYPH-PROBE 字形检查、五页回归第 1 页。
  shot "task10-b1-fixed-home.png"

  tap_nav WORLD;  nap "$SETTLE"; shot "task10-b2-fixed-world.png"
  tap_nav ALARM;  nap "$SETTLE"; shot "task10-b3-fixed-alarm.png"
  tap_nav TIMER;  nap "$SETTLE"; shot "task10-b4-fixed-timer.png"
  tap_nav FOCUS;  nap "$SETTLE"; shot "task10-b5-fixed-focus.png"

  # 回到 WORLD 让 addCity 的坐标打点也进日志（该按钮只在 WORLD 页存在）。
  tap_nav WORLD;  nap 4

  adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/task10-b-fixed-logcat.txt" 2>&1
  adb -s "$D" shell dumpsys activity services "$PKG" > "$OUTDIR/task10-b-services.txt" 2>&1
fi
pico-cli app stop -d "$D" "$PKG"
release_once

echo "rc=$rc"
exit "$rc"
