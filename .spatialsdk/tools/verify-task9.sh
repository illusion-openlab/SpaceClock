#!/bin/bash
# 门禁 B（Task 9 变体，仿 verify-task8fix.sh 的形状）：锁内装机 + 启动 + 按 logcat 标记拍照 +
# 立即停应用释放设备。锁内不做任何分析（读图 / 翻 logcat / 跑 gradle 都在锁外）。
#
# 顺序按 controller notes §2 给定的合并方案：启动(联网) → 截联网态 → 原地断网 → 截断网态 →
# 恢复网络 → 停应用。同一进程走完两条分支，不重启，省一次装机+首帧。
#
# 已知局限（写在这里，报告里也会重复一遍）：
#   - 断网发生在联网轮已经成功拉过一次天气之后，内存/磁盘缓存都是"刚拉到的新鲜数据"，
#     所以断网轮看到的是"缓存仍新鲜、界面数字不变"这一支，不是"完全没缓存 -> 显示 —"
#     或"缓存过期 -> 显示『更新于 X 分钟前』"那两支——后两支需要等 30 分钟或改代码里的
#     窗口常量，本轮不打算为了赶时间碰这两件事里的任何一件（改窗口常量属于"钩子提交前
#     删净"，风险留证不划算；等 30 分钟不现实）。
#   - 断网期间"其它功能仍可用"这条本脚本只能验到:①页面能正常渲染不崩,②TimingService
#     仍是 foreground（dumpsys）。真正的"阴性对照"（实际操作秒表/番茄钟按钮并看到状态变化）
#     需要这些按钮的屏幕坐标，而 TimerPage/FocusPage 目前没有像 NavBar/AlarmCard 那样打
#     onGloballyPositioned 坐标日志，本任务文件清单也不包含这两个文件——不在这轮里现改。
#     这部分在报告里会明确标"未验证"，不冒充"已验证"。
#
# 用法: [DEVICE=emulator-5554] .spatialsdk/tools/verify-task9.sh
set -u

D="${1:-emulator-5554}"
PKG=tech.illusion.spaceclock
APK=app/build/outputs/apk/debug/app-debug.apk
OUTDIR=.spatialsdk/ui-verify
LOCK=/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh
# owner 串必须跨 Bash 调用稳定（不要用 $$）。
OWNER=spaceclock-task9

export PICO_LOCK_MAX_WAIT="${PICO_LOCK_MAX_WAIT:-90}"
export PICO_HOME="${PICO_HOME:-/Users/zohar/Library/PICO/sdk}"

TAGS="WeatherRepository:V ClockVolume:V ClockLayout:V NavBar:V TimingService:V AndroidRuntime:E ActivityManager:W"
MARKER_TIMEOUT="${MARKER_TIMEOUT:-90}"

mkdir -p "$OUTDIR"
"$LOCK" status "$D"
if ! "$LOCK" acquire "$OWNER" 300 "$D"; then
  echo "LOCK BUSY — rerun: $0 $D"
  exit 1
fi
released=0
release_once() { [ "$released" = 1 ] && return; released=1; "$LOCK" release "$OWNER" "$D" 2>/dev/null; }
trap release_once EXIT

rc=0

wait_marker() { # $1=regex $2=timeout-seconds
  local i=0
  while [ "$i" -lt "$2" ]; do
    if adb -s "$D" logcat -d -s $TAGS 2>/dev/null | grep -q -- "$1"; then
      echo "marker hit: $1 (after ${i}s)"
      return 0
    fi
    adb -s "$D" shell sleep 2 >/dev/null 2>&1
    i=$((i + 2))
  done
  echo "marker MISS: $1 (${2}s)"
  return 1
}

launch_and_wait_pid() {
  pico-cli app launch -d "$D" "$PKG" || return 1
  local pid=""
  for _ in $(seq 1 20); do
    pid="$(adb -s "$D" shell pidof "$PKG" | tr -d '\r')"
    [ -n "$pid" ] && break
    adb -s "$D" shell sleep 1 >/dev/null 2>&1
  done
  echo "pid=$pid"
  [ -n "$pid" ]
}

tap_nav() { # $1=page name (HOME/WORLD/ALARM/TIMER/FOCUS), taps its NavBar button
  local page="$1"
  local line x y w h cx cy
  line="$(adb -s "$D" logcat -d -s ClockLayout:V 2>/dev/null | grep "navButton page=$page" | tail -1)"
  if [ -z "$line" ]; then
    echo "tap_nav: no navButton log line for $page yet"
    return 1
  fi
  w="$(echo "$line" | sed -n 's/.*w=\([0-9]*\).*/\1/p')"
  h="$(echo "$line" | sed -n 's/.*h=\([0-9]*\).*/\1/p')"
  x="$(echo "$line" | sed -n 's/.*x=\([0-9.]*\).*/\1/p')"
  y="$(echo "$line" | sed -n 's/.*y=\([0-9.]*\).*/\1/p')"
  cx="$(awk "BEGIN{printf \"%d\", $x + $w/2}")"
  cy="$(awk "BEGIN{printf \"%d\", $y + $h/2}")"
  echo "tap_nav $page -> ($cx,$cy) from line: $line"
  adb -s "$D" shell input tap "$cx" "$cy"
}

adb -s "$D" logcat -c
pico-cli app install -d "$D" -r -g "$APK" || rc=1

if [ "$rc" = 0 ]; then
  echo "wifi state before launch:"
  adb -s "$D" shell dumpsys wifi | grep -i "Wi-Fi is" | head -1
  launch_and_wait_pid || rc=1
fi

if [ "$rc" = 0 ]; then
  # ===== Round 1: 联网 =====
  wait_marker "WEATHER-SNAPSHOT" "$MARKER_TIMEOUT"
  adb -s "$D" shell sleep 3 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-1-online-home.png" || rc=1

  tap_nav WORLD
  adb -s "$D" shell sleep 2 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-2-online-world.png" || rc=1
  adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/task9-p1-online-logcat.txt" 2>&1

  # ===== 原地断网 =====
  adb -s "$D" shell svc wifi disable
  adb -s "$D" shell sleep 3 >/dev/null 2>&1
  echo "wifi state after disable:"
  adb -s "$D" shell dumpsys wifi | grep -i "Wi-Fi is" | head -1

  # ===== Round 2: 断网 =====
  tap_nav HOME
  adb -s "$D" shell sleep 2 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-3-offline-home.png" || rc=1

  tap_nav WORLD
  adb -s "$D" shell sleep 2 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-4-offline-world.png" || rc=1

  # 页面能正常渲染(不崩)的弱证据；TimingService 仍在跑的判据用 dumpsys（Task 8 已确认的
  # discriminator），不是靠这几张截图本身。
  tap_nav ALARM
  adb -s "$D" shell sleep 2 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-5-offline-alarm.png" || rc=1

  tap_nav TIMER
  adb -s "$D" shell sleep 2 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-6-offline-timer.png" || rc=1

  tap_nav FOCUS
  adb -s "$D" shell sleep 2 >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task9-7-offline-focus.png" || rc=1

  adb -s "$D" shell dumpsys activity services "$PKG" > "$OUTDIR/task9-offline-services.txt" 2>&1
  adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/task9-p2-offline-logcat.txt" 2>&1

  # ===== 恢复网络（brief Step 8 要求：验完必须恢复网络）=====
  adb -s "$D" shell svc wifi enable
fi

pico-cli app stop -d "$D" "$PKG"
release_once

adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/task9-full-logcat.txt" 2>&1
echo "rc=$rc"
exit "$rc"
