# SpaceClock — Agent Guide

PICO OS 6 体积窗口时钟摆件。Spatial SDK 6.0.0 / Kotlin 2.1.20 / SpatialUI。

- 包名 / applicationId：`tech.illusion.spaceclock`
- 容器：`Form.Volumetric`（`DefaultWindowContainer`，manifest `windowcontainer.style=2`）。**架构决定，不许换成 `Form.Planar` 或 `Stage`。**
- WindowContainer id：`SpaceClockVolume`
- compileSdk / targetSdk / minSdk = 35，AGP 8.13.2
- 构建：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`，然后 `./gradlew :app:assembleDebug`（`JAVA_HOME` / `PICO_HOME` 不跨 Bash 调用持续，每条命令自己 export）
- 设备验证：`./.spatialsdk/tools/verify-probe.sh emulator-5554`（门禁 B：锁内装机→启动→截图→停应用→释放锁；分析全在锁外）。
  按事件取证用 `./.spatialsdk/tools/verify-task8fix.sh`（`LABEL=… SHOT1_MARKER='…'`：等 logcat 标记再拍照，
  固定 sleep 会拍到空房间；可选 `RELAUNCH=1` 走 force-stop→重开验落盘恢复，`REVOKE=1` 关通知权限，
  `CLEARALERT=1` 清掉上一轮留下的"未确认提醒"落盘状态）

## 平台探针结论（Task 0，模拟器 emulator-5554 实测）

证据：`.spatialsdk/ui-verify/task0-logcat.txt`、`.spatialsdk/ui-verify/task0-1.png`。

### `EARTH_MODEL_USABLE = true`

`app/src/main/assets/models/Earth.usdz`（12,877,702 字节）**可以直接用**，不需要设计文档 §4.4 的兜底方案。

- 加载无异常，`getVisualBounds` 实测 `size=(0.2807,0.2807,0.2807)`，与离线实测（R=0.140328）完全一致。
- 三张 **TGA 贴图被正常解析**：截图里大洲、海洋、云层清晰，颜色正确。
- **`UsdPreviewSurface` PBR 在没有任何动态光照的情况下依然明亮可见**，不是纯黑轮廓。风险清除。
- 缩放系数仍然按 `0.20 / size.x` 现算，不要写死 0.713。

### `EARTH_AXIS_CONVENTION = converted-to-Y-up`

加载器把模型的 `upAxis = Z` **转成了引擎的 Y-up，并且把旋转烘进了顶点**——实体自身 transform 是单位量：

```
ClockProbe: earth transform pos=Vector3(x=0.0, y=0.0, z=0.0) quat=Quat(x=0.0, y=0.0, z=0.0, w=1.0) scale=Vector3(x=1.0, y=1.0, z=1.0)
ClockProbe: earth bounds size=(0.2807,0.2807,0.2807) center=(-0.0000,0.1416,-0.0000)
```

模型球心在自己坐标系里是 `(0,0,0.141627)`，实测落在引擎的 **+Y** 上 → Z 极轴被映射到 Y。因为实体
transform 是单位量，**子节点不会跟着转**：按 §4.2 原式（Z 为极轴）算出来的点会和网格错开 90°。
探针实测证实了这一点——红（北极）与黄（北京）标记点算在 `z≈0.28`，被 0.30m 深的视口裁掉；
绿（里约）标记点飘在球体左下方球面之外 3cm：

```
ClockProbe: PIN-READOUT northpole engine=(-0.0002,-0.0001,0.2848) dominant=+Z
ClockProbe: PIN-READOUT rio       engine=(-0.1193,0.0561,0.0859) dominant=-X
```

**精确映射（已确认，含手性）：`engine = (x_model, z_model, −y_model)`**，即绕 X 轴 −90°，
右手系保持不变、**没有镜像**。定标方式：截图里正对相机的半球中心是 realLon ≈ 72°E（印度半岛
正居中）、右侧地平线是 162°E（澳洲刚好在内侧）、左侧地平线是 18°W（非洲西缘）。若映射是
`+y_model`（镜像假设），正对相机的会是美洲/太平洋；若 X 被取反，非洲会出现在印度右侧。两者都被
截图排除。§4.2 的 `+198°` 经度偏移由此在设备上得到验证。

**Task 4 的 `GeoMath` 必须自己做换算**（与计划预期一致）：

```
modelLon = (realLon + 198°) mod 360°
x =  R · cos(lat) · cos(modelLon)
y =  R · sin(lat)                     // = z_model − CZ
z = −R · cos(lat) · sin(modelLon)     // = −y_model
R = 0.140328, CZ = 0.141627, 标记点抬离球面用 R × 1.02
```

地球实体本身也要沿 **Y** 平移 `−CZ` 才能把球心放到视口原点（用实测 `boundsCenter` 动态做，
现在在 `EarthScene.build` 里）。**注意这只搬实体，不搬实体的局部原点**——挂在 earth 下的标记点
仍然在球心偏移的那套局部空间里，见下面「标记点定位」。

### `FGS_AVAILABLE = true`

`foregroundServiceType="specialUse"` 的前台服务在 PICO OS 6 上**正常工作**，Task 8 走正路，不需要降级
为进程内计时。

```
ProbeService: FGS-READOUT startForeground OK type=specialUse
ProbeService: FGS-READOUT alive tick=0 elapsedRealtime=17042559
... tick=1..5，间隔稳定 5001ms
```

`startForeground` 没有抛 `MissingForegroundServiceTypeException`，服务持续存活到 `app stop`。
manifest 里 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS` 三条权限
与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 都是必需的；`POST_NOTIFICATIONS` 是**运行时权限**，装机时用
`pico-cli app install -g` 授予，否则通知不显示（服务本身仍然运行）。

## 探针遗留物

- ~~`timing/ProbeService.kt`~~ — Task 8 已删除，换成 `timing/TimingService.kt`（manifest 里的
  `<service>` 也已指向新类）
- ~~`earth/ProbeScene.kt`~~ — Task 4 已删除，换成 `earth/EarthScene.kt` + `earth/EarthSpinSystem.kt`
- ~~`content/HomeVolume.kt`~~ / ~~manifest 0.4x0.4x0.4~~ — Task 1 已替换

## 跨任务契约：Task 8 前台服务必须持有三个独立计时引擎（Task 5 fix round 定稿）

**`ProbeService` 换成真正的计时服务时，必须持有秒表 + 倒计时两个独立 `TimerEngine` 实例，
外加一个番茄钟引擎——一共三个独立实例，不是一个共享实例。**

背景：Task 5 的 brief 原本给的是单一 `class TimerEngine`（配一个 `mode: TimerMode` 字段）。
实现时发现这个设计自相矛盾：一个实例只有一对 `accumulatedMs`/`startedAtMs`，装不下"暂停的
秒表 + 正在跑的倒计时"两份并存状态；而 `setCountdown` 按规格必须停表清零，共享实例会让用户
在倒计时页调时长时把正在跑/暂停的秒表悄悄清零——这是真实的数据丢失场景，不是代码整洁度偏好。
Fix round 把这个架构分歧提交人工裁决，**裁决保留两个独立 `TimerEngine` 实例**
（`stopwatchEngine`/`countdownEngine`，见 `ClockVolume.kt`），并删除了原来试图让单实例兼顾
两种用途的 `TimerMode` 枚举与 `mode` 字段（已确认没有测试或调用点依赖它们，纯净删除）。

Task 8 把这两个实例从 `ClockVolume` 迁移进前台服务时，必须原样保留"两个独立实例"这个数量，
再加上番茄钟自己的第三个引擎/状态机——**不要图省事把三者合并回一个带 `mode` 的共享实例**，
那正是本次裁决否决掉的设计。`TimerEngine` 本身（`start`/`pause`/`reset`/`lap`/`setCountdown`/
`elapsedMs`/`remainingMs`/`isFinished`，均为纯函数式、`nowMs` 由调用方传入）不需要改动，
服务只是换个地方持有三份实例。

**已落地（Task 8）**：`TimingService` 里就是 `stopwatch` / `countdown` / `pomodoro` 三个字段，
`TimerEngine` 一行未改。后续任务同样不要合并它们。

## 前台计时服务结论（Task 8，模拟器 emulator-5554 实测）

证据：`.spatialsdk/ui-verify/task8-logcat.txt`、`task8-round2-logcat.txt`、
`task8-svc-{visible,backgrounded,notif-denied}.txt`、`task8-windows-backgrounded.summary.txt`、
`task8-prefs-before.xml`。截图 `task8-{1,2,3,4}-*.png` 按 `.gitignore:38` 不入库，只在本地。

`timing/TimingService.kt` 现在是**计时状态的唯一真源**：秒表 + 倒计时两个独立 `TimerEngine`、
一个 `PomodoroEngine`、闹钟轮询（复用纯函数 `pollAlarms`）全部在服务里；`ClockVolume` /
`TimerPage` / `FocusPage` 只 `collectAsState()` 观察，一行计时状态都不留。

### 判断「服务真的在后台跑」用哪些判据

**不要**用"切回来后剩余时间少了 60 秒"当判据——`PomodoroEngine.remainingMs(nowMs)` 是 `nowMs`
的纯函数，`advanceTo` 又用 `while` 一次补跨多个阶段，所以**即使服务一秒没跑**，UI 恢复时重算
出来的剩余时间也正好少 60 秒。两个相反的假设给出同一个观测，这条判据永远不会失败。

实际用的三条（前两条是真正的判别器）：

1. **tick 日志连续性**：`TimingService: TICK ticks=…` 每 5 秒一条，必须覆盖整段"应用不可见"
   的时间。实测 `KEYCODE_BACK` 关窗（`ClockVolume: timing service unbound` 22:48:54.665，
   `dumpsys window` 里 spaceclock 窗口数 = 0）之后，ticks=100…475 从 22:48:58 连到 22:50:13
   一条不缺，22:50:16 重新绑定。
2. **阶段边界落在不可见窗口内**：`PHASE-BOUNDARY WORK -> SHORT_BREAK round=1 completedToday=6`
   的时间戳是 22:49:38.795 —— 在无窗口区间正中，且紧跟着 `remainingMs=8` 那一 tick，不是恢复时
   才补算的。这条比 tick 连续性更强：它测的是产品可见后果，不只是"协程还活着"。
3. **`dumpsys activity services`**：不可见期间同一个 `ServiceRecord` 仍是
   `isForeground=true types=0x40000000 startRequested=true lastStartId=1`。

### 只被绑定的服务活不过解绑 → 必须自己进 started 状态

`bindService(BIND_AUTO_CREATE)` 单独用不够：最后一个客户端解绑时服务就被销毁，窗口一关计时
就断。`ensureRunState()` 在推上前台前先 `startService(自己)`（命令只来自前台 UI，不会撞后台
启动限制），`dumpsys` 里的 `startRequested=true lastStartId=1` 就是这一步的证据。

### 今日统计的落盘必须跟着引擎搬进服务

Task 6 的落盘是**边缘触发**（比较前后值、变了就写），当时用 `am force-stop` 验过能存活。引擎
搬进服务后如果落盘还留在 `FocusPage`，后台完成的番茄就永远写不进磁盘。落盘已随引擎搬入
`tickLoop`，形状不变（不要改成 `onPause`/`onDispose` 落盘——force-stop 时那两个回调一行都不跑）。
`rollOverIfNewDay(today)` 必须在 `seedStats(...)` **之前**跑，否则回填值会被清零。

实测闭环：后台写 `STATS-SAVED completedToday=6 focusedTodayMs=360000`（22:49:38.796，无窗口期间）
→ 磁盘 `stats_count=6 / stats_ms=360000`（`task8-prefs-before.xml`）→ 两次 force-stop 后新进程
`seededCompletedToday=6 seededFocusedTodayMs=360000`。

### `POST_NOTIFICATIONS` 被拒不影响任何功能（已实测）

`pm revoke` 后启动：`ClockVolume: POST_NOTIFICATIONS granted=false` → `startForeground OK` →
tick 照跑（`foreground=true`），无崩溃。权限弹框在体积窗口内正常渲染（`task8-4-notif-denied.png`
里的「允许"SpaceClock"发送通知？」），所以 `rememberLauncherForActivityResult` 在
`WindowContainer` 内可用，不需要单独的 `LaunchActivity` 路由。每个进程只请求一次。

### 陷阱：「只在有东西在跑时发布状态」会吞掉"跑完了"这一帧

`tickLoop` 里为了不让空闲期每秒白重组 5 次，只在 `anyRunning` 时 `publish()`。**这个条件单独用
是错的**：倒计时归零那一帧先把 `countdown.pause()` 掉，`anyRunning` 立刻变 false，于是那一帧
（也就是唯一带 `countdownFinished=true` 的一帧）永远发不出去。上机实测过这个 bug——
`task8-5-countdown-red.png`：倒计时停在 `00:00`、按钮还写着「暂停」，「时间到」不出现。
修法是加一个 `mutated` 标记，本 tick 改过状态就必须发布（跨天归零同理）。
阴性/阳性对照：`task8-5-countdown-{red,green}.png`，同一套临时钩子、同一段等待，只差这个修复；
green 里是「时间到」+「知道了」。

### 到点告警 / 补弹 / 落盘恢复（Task 8 fix round 1，模拟器实测）

证据：`.spatialsdk/ui-verify/task8fix-*`（5 轮，含 A1 的 RED/GREEN 对照）。截图按
`.gitignore:38` 不入库，只在本地：`task8fix-{a1-red,a1-green,alarm-seam,notif-denied,stopwatch-persist,clean-reinstall}-*.png`。

用户裁决：到点要有系统通知；未确认的提醒落盘、重开**补弹**；暂停的秒表落盘、重开以暂停态恢复。

- **告警频道必须是新频道**。频道 importance 在同一次安装内创建后改不动，常驻通知那条
  `spaceclock_timing` 必须是 `IMPORTANCE_LOW`（否则每次文案变化都响一声），复用它会让「时间到」
  永远不响。新频道 `spaceclock_alerts` = `IMPORTANCE_HIGH` + 震动；倒计时 id 2001 / 闹钟 id 2002，
  **非常驻**（`stopForeground(STOP_FOREGROUND_REMOVE)` 摘不掉它们）。实测
  `dumpsys notification`：id=2001/2002 `importance=4 channel=spaceclock_alerts category=alarm`，
  同一份 dump 里 id=1001 仍是 `importance=2`。
- **post 必须在 `stopSelf()` 之前**：通知一旦 post 就独立于服务生命周期，晚一步服务就没了。
- **不引入 AlarmManager**（已定案）：倒计时在跑期间 FGS 保证进程存活，到点那一刻服务必定活着；
  进程被杀/重启的场景由补弹覆盖。精确闹钟在 Android 14+ 要 `USE_EXACT_ALARM`/`SCHEDULE_EXACT_ALARM`，
  商店政策敏感，为已覆盖的场景引入不划算。
- **补弹一律不重发通知**：`onCreate` 只回填界面上的提醒。实测 force-stop 后重开
  `RESURFACE countdownFinished=true`，同一时刻 `dumpsys notification` 里只有 id=1001、没有 2001。
- **`POST_NOTIFICATIONS` 被拒时通知是被系统整条丢掉的**：`AppSettings: ... importance=NONE`，
  `NotificationRecord` 一条不剩（连 FGS 那条也不显示），但 `notify()` **不抛异常**
  （`ALERT-POSTED` / `notify ongoing text=秒表 计时中` 两条都在 `granted=false` 下打出来，
  `AndroidRuntime:E` 零条），计时/闹钟/补弹全部照常。所以被拒路径的兜底就是补弹。
- **秒表快照只在"暂停且有内容"时落盘**，正在跑就把磁盘上那份清掉（否则重开会恢复过期数据）。
  写入走 `tickLoop` 的边沿触发（`onPause`/`onDestroy` 会被 force-stop 绕过）。实测
  `PERSIST stopwatchSnapshot=StopwatchSnapshot(elapsedMs=9002, laps=[3002, 3000])`（暂停那一刻
  写一次）→ force-stop → 新进程 `seededStopwatchElapsedMs=9002 seededLaps=2`，界面 `00:09.00` +
  两行计次、按钮是暂停态。计次要恢复 `lastLapTotalMs = laps.sum()`（不是总用时），否则重开后的
  第一段会把前面所有段再算一遍——`TimerEngineTest` 用一个总用时≠分段和的快照钉死这条。
- **「只问一次」必须是持久标记**：`LaunchedEffect(Unit)` 只保证"每次挂载一次"，而重开窗口就会
  重挂载这棵树（Task 8 的 `task8-logcat.txt` 里同一个 pid 18113 出现过两条请求日志）。标记落在
  `ClockStore.notificationPermissionAsked`，且在拉起弹框**之前**写盘。实测：被拒的第一段弹框出现
  一次（`task8fix-notif-denied-1.png`）、force-stop 后的第二段不再弹（`notif_asked=true`）。
- **`onSnooze` 的语义已上机定论**（原先只有代码审查）：注入两条同一 hh:mm 的闹钟让它们落进同一个
  轮询窗口 → `ALARM-FIRED ids=[…001, …002] queue=[…001, …002]`；卡片渲染的是
  `ALARM-CARD-SHOWN id=…001`，服务侧 `SNOOZE-APPLIED snoozedId=…001 until=+10min
  queueAfter=[…002]`，随后 `ALARM-CARD-SHOWN id=…002` 接上卡片。**被显示的那个才是被 snooze 的
  那个**，`firingQueue.first()` 与卡片是同一条 `firingAlarm`。
- **UI 页面状态的播种要把"未确认的到点"算进来**。`TimerPage` 的 uiMode 原先只看
  `countdownRunning`：倒计时在别的页归零后回到本页，`countdownRunning` 已经是 false，uiMode 落到
  秒表，而「时间到」只在倒计时分支渲染 → StateFlow 里有 `countdownFinished=true`，界面上**零暴露面**。
  上机 RED/GREEN 对照（同一套钩子、只差这个表达式）：`task8fix-a1-red-1.png` 是 `00:00.00` 的秒表，
  `task8fix-a1-green-1.png` 是「时间到」+「知道了」。播种之外还加了一条反应式切换，覆盖"用户正停在
  秒表标签上"和"本页在服务连上之前就挂载了"这两种播种碰不到的情况。

### 已知取舍

- 服务只在「有任何计时在跑 或 有启用的闹钟」时驻前台（简报锁定的判据）。未确认的「时间到」
  **不**延长服务寿命——那样会让一条通知因为一个早已到点的提醒无限期挂着。改用「告警通知 + 落盘
  补弹」覆盖这个场景（见上一节）。
- **正在跑**的秒表/倒计时被强杀后仍然丢失（只有"暂停的秒表"落盘）。跑着的秒表由 FGS 保证存活，
  它的快照每 tick 都在变，落盘就变成每秒 5 次写 SharedPreferences。
- 告警通知的点击意图（`PendingIntent` → `LaunchActivity`）**没有运行时证据**：模拟器上没有可靠的
  点通知手段。
- `FGS_AVAILABLE = true` 仍然**只有模拟器证据**，真机确认待办。
- 模拟器残留状态：3 条闹钟（07:00 / 20:43 / 20:58，Task 7 留下）+ 今日统计 6 个番茄 +
  `pomo_short=1 / pomo_long=1`（Task 6 临时验证留下的时长配置）。都不是 bug。

## 地球场景结论（Task 4，模拟器 emulator-5554 实测）

证据：`.spatialsdk/ui-verify/task4-logcat.txt`（按标签筛过、已提交）与
`.spatialsdk/ui-verify/task4-r{1..5}-*.png`（已提交），推导过程见
`.superpowers/sdd/2026-08-19-spatial-clock-implementation/task-4-report.md`。
（原始全量 logcat `task4-r{1..5}-logcat-full.txt` 被 `.gitignore:39` 忽略，不在仓库里，别指过去。）

### 引擎方位角关系（这是本任务所有角度结论的地基）

以 `atan2(z, x)`（度）为**引擎方位角**。实测 9 座城市、4 轮、多个自转角，全部满足：

```
城市方位角 λ = (realLon + 198) mod 360          （GeoMath.LON_OFFSET_DEG）
spin = s 时该城标记点的引擎方位角 = −(λ + s)
```

`PIN-READOUT` 里每座城市的实测值与该式的偏差 < 0.1°。观察者（体积窗口正面）在 **+Z**，
即引擎方位角 **+90°**——Task 0 的截图（spin≈0 时印度居中 = realLon 72°E = λ 270° → −270 ≡ +90）
和 Task 4 round 1 的截图（spin=162.13 时美洲居中 = realLon 90°W，与 λ_c = 270 − s 吻合）两次独立印证。

### `FRONT_AZIMUTH_DEG = −90`（等价 270），上机校准

`focusOn` 的目标自转角是 `s = FRONT_AZIMUTH_DEG − λ`。**校准方法**：round 1 先填 0 跑，
实测被转到正面的是 realLon 90.1°W 而不是请求的伦敦（0.13°W），差 90° → 常量补 −90。
round 2 用 −90 复跑，`FOCUS-LANDED ... azimuth=90.0 (viewer=90.0)`、
`pos=(−0.1700, 0.0798, 0.0635)`（x 正好等于球心 X），一次到位。

### 标记点定位：局部球心偏移不能漏

标记点是 earth 的子节点，坐标系是 earth 的**局部**空间，而球心在局部 `(0, 0.1416, 0)`
（Task 0）。`build` 给实体设的 `position` 移动的是整个实体，**不会**把局部原点搬到球心。
判定用「标记点到球心的距离」而不是截图：实测 9 个标记点全部 `distFromCentre=0.1020`
= 0.10 × 1.02（PIN_LIFT），与理论值一致到 1e-4 m。漏掉偏移项的话这些距离会散布在 0..0.20。
落点也有截图直证：`task4-r4-2.png` 里伦敦压在不列颠、迪拜压在波斯湾、约翰内斯堡压在南非、
北京压在华北——与离线参考渲染 `pin_check2.png` 的落点一致。

### 自转轴只能是引擎 Y

`EulerAngles(0f, deg, 0f).toQuat()`——**yaw 分量**。球心在局部 Y 轴上，绕 Y 转球心原地不动；
绕 X/Z 会把整个球绕实体原点甩出去（那是公转不是自转）。System tick 实测 `dt≈0.0167s`（60Hz）
且**跑在主线程**（`TICK-DIAG ... thread=main`），所以 `advance` 里可以直接调 `@MainThread` 实体 API。

### 昼夜暗罩降级到 V2（本任务实测后删除）

原 Step 6 的实现（`createSphere` + 横向 alpha 渐变贴图 `night_mask.png`，`BlendingMode.TRANSPARENT`）
在设备上**把整个可见半球压暗了，包括白天侧**。对照实测（同一相机、同一窗口位姿、只差有没有暗罩，
取圆盘内海洋像素的亮度中位数）：

```
有暗罩 (task4-r2-2/3)：25.4 / 26.8
无暗罩 (task4-r4-2/3)：35.2 / 36.4      ← 且左右方向是平的，没有残留梯度
```

三条已定位的原因，V2 重做时从这里接：

1. **`MeshResource.createSphere` 的 UV 原点/绕向没有文档**。把渐变的暗核放在 u=0.75 时，
   实测它落在网格局部方位角约 50°（±25°，单次截图估计），不是假设的 270°。要么先用一张
   高对比诊断贴图上机测出这个常量，要么改用自建网格（自己控制 UV）。
2. **朝向公式的符号错了**。绕 Y 转 yaw 会让局部方位角 θ 落到 `θ − yaw`，所以正确形式是
   `yaw = θ_暗核 + subsolarLon + 18`，而简报里的 `spinQuat(-azimuth)` 把经度项的符号弄反了。
3. **设计层面还没定**：本工程的自转是 3°/s 的摆件转速（2 分钟一圈），不是真实地球自转。
   暗罩若做成 earth 的兄弟节点（简报的选择），暗区在空间里固定、地理从底下转过去，看起来自然，
   但"暗的正好是夜里的城市"只在某一瞬间成立；若做成子节点跟着自转，地理对得上但暗区像贴在地球上。
   这个取舍要先定，再谈贴图朝向。

贴图生成器保留在 `.spatialsdk/tools/make-night-mask.py`（2048×1024 RGBA，参数可调），
`app/src/main/assets/textures/night_mask.png` 已删除——不留没人用的资产进包。

### 清理路径：两条上机验过 + 竞态路径仅代码审查

`EarthScene` 自己攥住 `content`/`root`/`earth`（不是 Task 0 `ProbeHandle` 那种「build 正常返回后
才登记」的形状——那个漏异常路径）。`build` 一 `addEntity` 就登记，异常时 `dispose()` 再重抛；
`build` 唯一的挂起点（`loadSuspend`）恢复后立即检查 `disposed` 并拆除，覆盖「dispose 撞上未完成
的 `loadSuspend`」；方法收尾处再留一次兜底检查。

- 异常路径（把资产路径改成不存在的文件，round 3，**上机验过**）：
  `E earth scene build failed; tearing down` → `I earth scene torn down` → `E earth scene failed`，
  没有静默泄漏。同时 2D UI 照常工作（地球加载失败不拖垮页面）。
- 正常路径（优雅关闭，round 4/5，**上机验过**）：`KEYCODE_BACK` → `ActivityTaskManager:
  finishIfPossible reason=app-request` → 155~182ms 后 `I earth scene torn down`（两轮独立复现）。
  **`pico-cli app stop` 验不出这个**（force-stop 语义，`DisposableEffect` 一行都不跑，Task 1 实测）。
- 竞态路径（`dispose()` 撞上未完成的 `loadSuspend`）：**只有代码路径审查 + 日志钩子（`"dispose
  arrived during load; tearing down immediately"`），没有运行时证据**。触发需要在 12.8MB usdz
  解码完成前关窗口，模拟器上装机→启动→首帧的时序不受控，两轮实施都没找到可靠的触发手段。
  Fix round 复核过代码本身：修复前 `build` 恢复后直接往已被销毁的 `root`/`earth` 上继续
  `addChild` / `getVisualBounds` / 写变换，只在方法收尾才检查 `disposed`（迟了一整段同步代码）；
  已改成 `loadSuspend` 恢复后立即检查并 `return`，收尾检查保留作二层兜底。

## tnum 等宽数字结论（Task 2，模拟器 emulator-5554 实测，round 2 含阴性对照）

**产品要求（已确认满足）**：数字跨位宽变化时不能抖动。`LeftColumn` 里的四位数字（"11:11" vs
"00:00"，同 style、同位数、只换数字值）实测宽度全部相等——**不管 `fontFeatureSettings` 是否
传 `"tnum"`**：

```
TnumProbe: A tnum=on  text=11:11 width=430
TnumProbe: B tnum=on  text=00:00 width=430
TnumProbe: C tnum=off text=11:11 width=430
TnumProbe: D tnum=off text=00:00 width=430
```

**计划要求（未确认，round 1 的结论被证伪）**：round 1 只测了 A/B（`tnum=on` 时两个数字串等宽），
把这当成了"tnum 生效"的证据——但缺阴性对照，"A==B" 同样可以用"这套字体的 lining digits 本来
就等宽、跟 tnum 开不开无关"来解释，两种假设无法区分。round 2 加了 C/D（同两个字符串，`style`
上完全不传 `fontFeatureSettings`），结果 **C==D，且和 A、B 一样都是 430**。这直接证伪了
round 1 的结论：关掉 tnum 后数字宽度毫无变化，说明**设备字体本身的数字字形天生等宽（inherently
tabular），`fontFeatureSettings = "tnum"` 在这套字体上的独立作用没有被证实**——它可能确实在
起作用但被字体的默认行为掩盖了，也可能是纯粹的空操作，本实验设计无法区分这两种情况。

**结论怎么用**：产品要求已经满足，不需要 Step 5 提到的固定宽度容器兜底，继续在
`TextStyle` 上写 `fontFeatureSettings = TABULAR_FIGURES` 作为零成本保险——如果换字体，这行代码
可能从"没用但无害"变成"真正必需"，删掉它没有收益。但**不要把这次实测引用为"tnum 已验证生效"
的证据**：如果未来需要证明 `tnum` 本身的因果作用（而不是"数字不抖"这个结果），当前证据不够，
需要换一套能展示字体默认比例数字的样本（例如切换到已知非等宽的字体做对照）才能分离两个假设。

## 已知待办 / 陷阱

- `app/build.gradle.kts` 里 `androidx.compose.ui:ui-tooling` 是 `implementation`（不是 `debugImplementation`），
  会把 Material 拖进 release 包。全局约束禁止 Material/Material3，Task 1 起需要处理。
- `local.properties` 已被 gitignore，内含 `sdk.dir` / `spatial.tools.dir`，换机需重建。
- 模拟器是多会话共享的：截图里可能出现别的会话的窗口（本次截图背景那个「视频资源库」面板就是别人的）。
- 模拟器截图相机固定且视角窄，只能用来验证渲染管线与相对位置，不能用来判断整体摆放好不好看。
- **`pico-cli capture screenshot` 会给出落后数秒的旧帧**（Task 8 fix round 1 实测：文件写入时间比
  目标 UI 出现晚 6 秒，画面里仍是它出现之前的状态，`task8fix-alarm-seam-1.png`）。所以不能拿单张
  截图的时刻去推断当时的 UI 状态；关键时刻要么在日志里留标记，要么把等待放宽到十几秒再拍。
