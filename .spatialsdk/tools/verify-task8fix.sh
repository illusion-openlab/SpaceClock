#!/bin/bash
# 门禁 B（Task 8 fix round 1 变体）：锁内装机 + 启动 + 按 logcat 标记拍照 + 立即停应用释放设备。
# 锁内不做任何分析（读图 / 翻 logcat / 跑 gradle 都在锁外）。
#
# verify-probe.sh 是 Task 0 的形状（写死 task0-* 文件名、固定 sleep）。这里改成：
#   1. 输出文件名由 LABEL 决定；
#   2. 拍照时机由 **logcat 标记** 决定而不是固定 sleep（AGENTS.md：固定 sleep 会拍到空房间）；
#   3. 可选的 force-stop → 重启第二段，用来验"补弹"。
#
# 用法: LABEL=xxx SHOT1_MARKER='page->TIMER' [SHOT1_EXTRA=6] [SHOT2_MARKER=...]
#       [RELAUNCH=1] [REVOKE=1] .spatialsdk/tools/verify-task8fix.sh [device-serial]
set -u

D="${1:-emulator-5554}"
PKG=tech.illusion.spaceclock
APK=app/build/outputs/apk/debug/app-debug.apk
OUTDIR=.spatialsdk/ui-verify
LOCK=/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh
# owner 串必须跨 Bash 调用稳定（不要用 $$）：acquire 与 release 得是同一个字符串。
OWNER=spaceclock-task8fix

LABEL="${LABEL:?LABEL required}"
SHOT1_MARKER="${SHOT1_MARKER:-}"
SHOT1_EXTRA="${SHOT1_EXTRA:-5}"
SHOT2_MARKER="${SHOT2_MARKER:-}"
SHOT2_EXTRA="${SHOT2_EXTRA:-5}"
RELAUNCH="${RELAUNCH:-0}"
REVOKE="${REVOKE:-0}"
MARKER_TIMEOUT="${MARKER_TIMEOUT:-150}"

export PICO_LOCK_MAX_WAIT=90
export PICO_HOME=/Users/zohar/Library/PICO/sdk

TAGS="TimingService:V ClockVolume:V Task8Verify:V AlarmLayout:V AndroidRuntime:E ActivityManager:W"

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

dumps() { # $1=phase-name
  adb -s "$D" shell dumpsys activity services "$PKG" > "$OUTDIR/task8fix-$LABEL-$1-svc.txt" 2>&1
  # 只留和本包相关的行：全量 dumpsys notification 有 80KB 噪声，仓库里不该躺这种东西。
  adb -s "$D" shell dumpsys notification --noredact 2>&1 \
    | grep -E "spaceclock" | grep -vE "^[[:space:]]+NotificationChannel\{" \
    > "$OUTDIR/task8fix-$LABEL-$1-notif.txt"
}

adb -s "$D" logcat -c
pico-cli app install -d "$D" -r -g "$APK" || rc=1
if [ "$rc" = 0 ] && [ "${CLEARALERT:-0}" = 1 ]; then
  # 轮次之间清掉三份"未确认/可恢复"落盘状态，否则上一轮留下的补弹会在本轮开局就浮现，
  # 让"本轮真的到点了"与"上一轮的残留"两个假设给出同一张截图。应用此时没在跑（刚装机）。
  PREFS=/data/data/$PKG/shared_prefs/spaceclock.xml
  adb -s "$D" shell "run-as $PKG sed -i '/countdown_alert_pending/d;/pending_alarm_alerts/d;/sw_elapsed/d;/sw_laps/d' $PREFS"
  adb -s "$D" shell run-as "$PKG" cat "$PREFS" > "$OUTDIR/task8fix-$LABEL-prefs-before.xml" 2>&1
fi
if [ "$rc" = 0 ] && [ "$REVOKE" = 1 ]; then
  adb -s "$D" shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
  adb -s "$D" shell dumpsys package "$PKG" | grep -A1 POST_NOTIFICATIONS | head -4
fi

if [ "$rc" = 0 ]; then
  launch_and_wait_pid || rc=1
fi
if [ "$rc" = 0 ]; then
  marker1=0
  if [ -n "$SHOT1_MARKER" ]; then
    wait_marker "$SHOT1_MARKER" "$MARKER_TIMEOUT" || marker1=1
  fi
  adb -s "$D" shell sleep "$SHOT1_EXTRA" >/dev/null 2>&1
  pico-cli capture screenshot -d "$D" -o "$OUTDIR/task8fix-$LABEL-1.png" || rc=1
  dumps p1
  adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/task8fix-$LABEL-p1-logcat.txt" 2>&1

  # 第一个标记都没等到就别再空等第二个（白占几分钟锁）。
  if [ -n "$SHOT2_MARKER" ] && [ "$marker1" = 0 ]; then
    if [ "$RELAUNCH" = 1 ]; then
      # force-stop（onDispose 一行不跑，正是"熬过强杀"要的语义），再重开验补弹。
      pico-cli app stop -d "$D" "$PKG"
      adb -s "$D" shell sleep 2 >/dev/null 2>&1
      adb -s "$D" logcat -c
      launch_and_wait_pid || rc=1
    fi
    wait_marker "$SHOT2_MARKER" "$MARKER_TIMEOUT"
    adb -s "$D" shell sleep "$SHOT2_EXTRA" >/dev/null 2>&1
    pico-cli capture screenshot -d "$D" -o "$OUTDIR/task8fix-$LABEL-2.png" || rc=1
    dumps p2
  fi
fi
pico-cli app stop -d "$D" "$PKG"
release_once

# 锁外：dump 日志留证据（只读，不分析）。
adb -s "$D" logcat -d -s $TAGS > "$OUTDIR/task8fix-$LABEL-p2-logcat.txt" 2>&1
echo "rc=$rc"
exit "$rc"
