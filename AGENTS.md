# SpaceClock — Agent Guide

PICO OS 6 体积窗口时钟摆件：一个挂在空间里长期不关的电子时钟挂件，左侧一颗缓慢自转的真实地球 +
本地大时间，右侧一条面板承载世界时钟 / 闹钟 / 秒表倒计时 / 番茄钟，底部一排按钮切页；天气以**纯文字**
形式融入时钟主页和闹钟提醒卡。Spatial SDK 6.0.0 / Kotlin 2.1.20 / SpatialUI。

- 包名 / applicationId：`tech.illusion.spaceclock`
- 容器：`Form.Volumetric`（`DefaultWindowContainer`，manifest `windowcontainer.style=2`，
  `defaultsize=0.60x0.45x0.30 meters`）。**架构决定，不许换成 `Form.Planar` 或 `Stage`。**
- WindowContainer id：`SpaceClockVolume`
- compileSdk / targetSdk / minSdk = 35，AGP 8.13.2

## 设计与计划文档（判定 UI 对不对的唯一事实源）

改任何 UI 之前先读这三份，**设计稿说了算**，不要凭观感拍板。

> ⚠️ **下面四个路径都不在本仓库里**（前三个在仓库**之外**的绝对路径上，第四个虽然在仓库目录树内
> 但被 `.superpowers/sdd/.gitignore:1` 的 `*` 整目录排除）。`git clone` 之后**一个都不存在**，
> 这不是环境坏了。没有它们时怎么办见下面「证据文件约定」的最后一条。

| 文档 | 路径 | 里面有什么 |
|---|---|---|
| 设计规划 | `/Users/zohar/WorkSpace/Project/PicoProjects/docs/superpowers/specs/2026-08-19-spatial-clock-design.md` | §1 关键决策表、§2 容器/深度三层、§3 五页布局与状态副行表、§4 地球公式、§5.4 UI 约束、**§5.4.1 深空蓝配色 token**、§5.6 权限降级、「不做的事」「变更记录」 |
| 实施计划 | `/Users/zohar/WorkSpace/Project/PicoProjects/docs/superpowers/plans/2026-08-19-spatial-clock-implementation.md` | Task 0–10 的分解与逐步验收标准 |
| 概念验证图 | `/Users/zohar/WorkSpace/Project/PicoProjects/.superpowers/brainstorm/59255-1787106659/content/` | `layout-v2.html`（布局三选，**C 方案是被选中的那个，正面几何以它为准**）、`pages.html` 五页线框、`earth-pins.html` 标注验证、`palette.html` 配色三选（选了 A 深空蓝） |

SDD 账本与逐任务 brief / report：`.superpowers/sdd/2026-08-19-spatial-clock-implementation/`
（**不入库**，见上面的警告框）。

**`layout-v2.html` C 方案的正面几何**（百分比是相对体积窗口正面的宽/高，Task 10 据此判定布局）：

```
.globe  width:36%  left:5%   top:28%
.big    left:5%    top:9%                     ← 时间大数字
.sub    left:6%    bottom:19%                 ← 状态副行
.pane   left:47%   right:4%   top:9%  bottom:19%   ← 右侧面板：**不贴窗口底边**
.brow   left:5%    right:4%   bottom:5%  height:9%  ← 按钮条，在面板下方，间隔 5%
```

注意 `.pane` 的 `bottom:19%`：**设计稿里面板与按钮条是上下分区、留 5% 间隙的**，
面板并不延伸到窗口底边。§1/§2 说的「通高面板」指的是"通内容区的高"，不是"通到窗口底边"。
Task 1 把它实现成了「面板 `fillMaxHeight()` + 按钮条 `align(BottomCenter)` 浮在上面」，
造成了下面「布局结论」那一节记的遮挡缺陷。

## 构建与验证命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest      # 当前基线 105 个全绿
./gradlew :app:assembleDebug
```

`JAVA_HOME` / `PICO_HOME` **不跨 Bash 调用持续**，每条命令自己 export。

**已知坑：单测里碰 `org.json.JSONObject` 必须标 `@RunWith(RobolectricTestRunner::class)`。**
纯 JUnit 下 Android SDK 是 mock jar，`org.json` 的访问器方法会抛
`RuntimeException("Method X not mocked")`；current-location-weather 功能 Task 2 踩过这个坑，
`LocationRepositoryTest` 由此加了这个注解，改的时候别删掉。

设备验证（门禁 B：锁内装机→启动→截图→停应用→释放锁；**分析全在锁外**）：

| 脚本 | 形状 |
|---|---|
| `.spatialsdk/tools/verify-probe.sh <serial>` | Task 0 的原始形状：写死文件名 + 固定 sleep。**新脚本不要抄它** |
| `.spatialsdk/tools/verify-task8fix.sh` | 按事件取证：`LABEL=… SHOT1_MARKER='…'` 等 logcat 标记再拍照；`RELAUNCH=1` force-stop 后重开验落盘恢复，`REVOKE=1` 关通知权限，`CLEARALERT=1` 清掉上一轮的"未确认提醒"落盘 |
| `.spatialsdk/tools/verify-task9.sh` | 五页导航 + 原地断网/复网，含按实测坐标点 NavBar 的 `tap_nav` |
| `.spatialsdk/tools/verify-task10.sh` | **两个 APK 的 A/B 对照塞进同一个锁窗口**（控制组 APK 与实验组 APK 事先在锁外构建好，锁内各装一次）；等待统一走 `SETTLE` 常量 |

**等待时长的硬规矩**：`pico-cli capture screenshot` 会返回落后 **6–8 秒**的陈旧帧（Task 8 fix
round 1 实测：文件写入时间比目标 UI 出现晚 6 秒，画面里还是它出现之前的状态）。所以首帧与
**每次交互之后**一律等 **≥12s**，并写成脚本里的默认常量。Task 9 用 2–3s，拍到的是切页之前的画面，
被迫补跑一整轮——省下的装机时间被吃回去还倒亏。前台 `sleep` 在 harness 里被禁，用 `adb shell sleep`。

设备锁：`/Users/zohar/WorkSpace/Project/PicoProjects/.claude/skills/spatial-design-first-build/scripts/device-lock.sh`。
**owner 串跨调用必须稳定**（不要用 `$$`，acquire 与 release 得是同一个字符串），
`PICO_LOCK_MAX_WAIT` 别用默认 300s（会吃光整轮超时），设成 90。

## 证据文件约定（读之前先看这一节，能省掉一次"我环境是不是坏了"）

`.gitignore:38-44` 把**截图、录屏、原始全量 logcat、原始 dumpsys 转储**排除在版本库外：

```
.spatialsdk/ui-verify/*.png        .spatialsdk/ui-verify/*.mp4
.spatialsdk/ui-verify/*-logcat-full.txt      .spatialsdk/ui-verify/*-windows.txt
```

所以：

- **仓库里没有任何一张 `.spatialsdk/ui-verify/*.png`。** 本文档提到的所有截图文件名都是
  **本地产物**，`git clone` 之后它们不存在。这不是环境坏了，重跑对应的 `verify-*.sh` 就会重新生成
  （画面会随模拟器房间/别人的会话变，但判据是坐标与色值，可复现）。
- 入库的是**筛过标签的** `taskN-*logcat.txt`、`*-svc.txt`、`*-notif.txt`、`*-prefs-before.xml`、
  `*.summary.txt`。想核对某条结论，优先翻这些。`git ls-files .spatialsdk` 一眼看全。
- **另一类不在本仓库里的东西：设计 spec / 实施计划 / brainstorm 概念图 / SDD 账本与逐任务
  brief·report。** 前三者在 `/Users/zohar/WorkSpace/Project/PicoProjects/{docs,.superpowers}/…`
  这些**仓库外**的绝对路径上（属于上层工作区，不随本仓库分发）；SDD 目录虽在树内但被
  `.superpowers/sdd/.gitignore:1` 的 `*` 整目录排除。
  **没有它们时怎么办**：本文档已经把判定 UI 所需的定量锚点（C 方案正面几何百分比表、
  §5.4.1 的 16 个配色 token 值、「被明确否掉的方案」清单）**内联抄进来了**，日常开发够用；
  只有要追溯"当初为什么这么定"才需要回上层工作区拿原文——找不到就去问工作区持有者，
  不要凭猜重建设计意图。
- 本文档 2026-08-20 之前的版本里有两类坏指针，已修掉，**别再写出来**：
  ① 声称 `task4-r{1..5}-*.png`「已提交」（实际被 ignore）；
  ② `pin_check2.png`（Task 4 的离线参考渲染）——这个文件**在仓库和工作区里都不存在**，
  指向一个根本不存在的东西比没有指针更糟。Task 4 落点的可核对证据是
  `task4-logcat.txt` 里的 `PIN-READOUT` 与 `distFromCentre`，不是那张图。

## 被明确否掉的方案清单（照抄设计文档，防止以后凭直觉加回来）

「不做的事」（设计文档 §「不做的事」，V1 明确排除）：

- **语音 TTS 播报** —— 用户原始需求写的是「结合天气播报」，第 3 轮澄清后确认 V1 的"播报"落成
  **文字提醒卡片**，完全不碰 `TextToSpeech`。理由：PICO OS 6 上是否预装可用中文 TTS 未验证，
  `speak()` 可能静默失败。**不要凭直觉把语音加回来。**
- Full Space / `Stage` 沉浸场景
- 第二个 `WindowContainer` / `Augment` 伴生工具栏（V1 少一个 API 未知项）
- 设备定位权限自动识别城市（仍然不申请 Android 定位权限；2026-08-20 加了不需要权限的 IP 地理定位
  "使用当前位置"选项，作为一个可选的、用户主动触发的城市来源，天气城市列表整体仍是用户手动管理，
  见 `docs/superpowers/specs/2026-08-20-current-location-weather-design.md`）
- 逐小时天气预报
- 闹钟铃声选择（V1 单一提示音）
- 番茄钟历史统计 / 周月图表 / 任务标签
- **`AlarmManager` 系统级精确唤醒**（应用被彻底杀掉后仍能响）——见下面 Task 8 那节的取舍与产品洞

「变更记录」里已经作废、**不要复活**的方案：

- 平面窗口 + 底部 `TabBar` 四页（第 6 轮改成体积窗口 + 窗口内底部按钮条；因此"常驻时钟头 + TabBar"
  和"TabBar 切几页"两个结论整体作废，时间数字常驻**左侧栏**而不是顶部横条）
- 「2D 内容 + 深度分层、零 3D 资产」以及「地球仪留到 V2」（第 7 轮改成 V1 直接做 3D 地球主体）
- 「线框 / 发光点阵程序化地球」（用户直接给了写实 `Earth.usdz`）
- 配色 B 暖琥珀（用户选 A 深空蓝；**保留一个局部例外**：城市标记点用暖白浅金 `#FFE9B8`，
  否则在蓝色球面上认不出来）
- **昼夜晨昏线**：设计里有、Task 4 实测后按预案**降到 V2**（见地球那节的三条原因）

## 平台探针结论（Task 0，模拟器 emulator-5554 实测）

证据：`.spatialsdk/ui-verify/task0-logcat.txt`（入库）；截图 `task0-{1,2}.png` 按 `.gitignore:38`
不入库，只在本地。

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

**`GeoMath` 必须自己做换算**：

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

**这条仍然只有模拟器证据，真机确认待办。**

## 正面布局结论（Task 1 建的，Task 10 实测修的）

### 实测像素几何（debug 构建，模拟器 emulator-5554，root 1500×1125，density 2.0 → 1dp = 2px）

```
修复前（Task 1–9 的构建）             修复后（Task 10）
root       w=1500 h=1125 x=0   y=0     root       w=1500 h=1125 x=0   y=0
leftColumn w=568  h=1045 x=40  y=40    leftColumn w=568  h=909  x=40  y=40
glassPanel w=795  h=1045 x=665 y=40    glassPanel w=795  h=909  x=665 y=40
navBar     w=1420 h=96   x=40  y=989   navBar     w=1420 h=96   x=40  y=989
```

证据：`task10-a-control-logcat.txt`（修复前）/ `task10-b-fixed-logcat.txt`（修复后），两份都入库。

### 缺陷：NavBar 与内容面板整条垂直重叠（Task 10 修掉）

`NavBar` 用 `align(Alignment.BottomCenter)` 浮在 `Row` 之上，而左右两栏都 `fillMaxHeight()`
一直延伸到窗口底边。**算术判据**：面板底边 `40+1045=1085`，按钮条顶边 `989`，
重叠 **1085−989 = 96px**，正好是整条按钮条的高度（96px = 48dp）。两个后果都实测到了：

- **渲染**：`backgroundMaterial` 是**合成器层**的玻璃，不走 Compose 的兄弟节点绘制顺序 ——
  即使 `NavBar` 在 `Box` 里加在后面，玻璃面板照样盖在它前面。修复前的截图里
  「闹钟 / 计时 / 专注」三个按钮**被面板遮得基本看不见**，左侧的状态副行也整条被压掉
  （Task 9 遇到的"WORLD 页状态副行被裁"就是这个）。
- **输入**：命中测试走 Compose 顺序，`NavBar` 在后面 → 重叠区里**它**吃掉指针事件，于是面板底部
  贴底的全宽按钮点不到。受影响：`AlarmPage` 的「＋新建闹钟」/「保存」、`WorldPage` 的「＋添加城市」。
  这两个按钮在页面内有 16dp 底 padding，所以被压掉的是 `1085−32−989 = 64px`，
  即按钮 96px 高度里的 **2/3**（这一个数字是由修复前的面板几何 + 页面 padding 推出来的，
  不是直接量到的——修复前那一轮没有给这两个按钮打坐标；修复后直接量到 `y=821 h=96`，
  与同一套算术吻合）。

**为什么 Task 1 的几何验证没抓到**：那轮量的是按钮条的**宽度与左右位置**（水平裁剪），
没量内容面板与按钮条的**垂直重叠**。这是"我们量过几何了"给出虚假信心的典型——
量对了一个维度不等于覆盖了这个问题。**以后改这块布局，水平和垂直都要打点。**

**修法**（`ClockVolume.kt`）：根 `Box` 里放一个 `Column`，内容 `Row` 取 `weight(1f)`，
`NavBar` 作为它下面的兄弟节点，中间 `Spacer(20.dp)`。**不要给面板加写死的 bottom padding**——
按钮条高度是 SpatialUI `Button` 的固有尺寸（实测 96px），写死会在组件改版时静默失效。
这也回到了设计稿 `layout-v2.html` C 方案的原样（面板 `bottom:19%`、按钮条在下方留 5% 间隙；
间隙复用外边距的 20.dp，不新造常量）。

修复后实测：面板底边 `40+909=949` < 按钮条顶边 `989`，**留白 40px**；
`addCity` / `newAlarm` 都是 `y=821 h=96` → 底边 917，离按钮条还有 72px。
五页截图里五个按钮全部可见、状态副行全部可见（`task10-b{1..5}-fixed-*.png`，本地）。

### 体积窗口底色必须应用自己铺（Task 10 修掉）

设计文档 §5.4.1 把 `#080E17`（= `fillSecondary`）定义为**「体积窗口底色」**，而 manifest 里
`windowcontainer.materialbackground=0` 刻意关掉了系统那整块玻璃底（"会糊掉体积感"）。
两件事加起来 = **除了应用自己画，没有别的东西会画这个底色**。Task 1–9 的构建里根容器没有任何
`background`，所以窗口底就是透视画面。同一采样点的实测：

```
修复前 leftColumn 区域底色 = #89847B   ← 模拟器房间的墙
修复后 leftColumn 区域底色 = #090E16   ← 设计要求的 #080E17（差 1/通道，PNG 与合成器取整）
```

修法就是根 `Box` 加 `.background(PicoTheme.colorScheme.fillSecondary)`。
排除的反向假设：「系统本来就画了底色、加这行没用」——修复前同一点采到的是房间墙的暖灰，不是深空蓝。

### 已定案：右侧面板改为不透明（issue ②，用户 2026-08-20 裁决后实施并上机验过）

用户报的「窗口面板背景色不对」**只解决了一半**（窗口底色），面板本身仍然发白。已经排除的三条：

1. **不是配色表写错。** `theme/SpaceClockTheme.kt` 的 `defaultColorScheme(...)` 16 个角色与
   设计文档 §5.4.1 逐字一致（面板 `rgba(90,150,220,0.09)` = `fillLight` = `0x175A96DC`，
   alpha `0x17`=23/255≈0.090，精确对上），`Main.kt` 也确实用 `PicoTheme(colorScheme = …)` 包住了。
   **不要去改那张表。**
2. **不是"玻璃底下没铺底色"。** 铺了根底色之后，**面板内部采样值一个字节都没变**：
   修复前 `#A8A7A5` / 修复后 `#A8A7A5`（同一点、同一相机位姿）。同一张图里窗口底色从
   `#89847B` 变成了 `#090E16`。结论：`backgroundMaterial(Material.Thin)` 是合成器层的材质，
   **采样的是环境/透视画面，不是应用自己画在它后面的那一层**，所以"在玻璃之下垫色"这条路无效。
   **这个对照的单变量控制项是"窗外那面墙"**：`task10-a1` / `task10-b1` 里窗口**之外**的房间
   墙面三个采样点逐字节相同（`#C6C0BA` / `#777671` / `#9E9993`）——环境、相机位姿、光照
   都没变，唯一变的是应用画了底色。少了这一项，"面板没变"可以被"这两帧环境本来就不同"解释掉。
3. **不是 modifier 顺序丢圆角。** **载重证据是 `task10-a1-control-home.png` 自己**：那张图跑的是
   **原始 modifier 顺序**（`backgroundMaterial` 在前、`clip` 在后），而面板四角**已经是 16dp
   圆角**。所以原顺序从来没丢过圆角，假设直接被单张图证伪。
   （`a1`↔`b1` 那个对照也做了、结论一致，但它同时动了两个变量（顺序 + 根底色），
   本身不是干净的判别器——真正钉死这条的是 a1 单图。）假设被证伪，
   `ClockVolume.kt` 里的顺序**已还原成原样**，不要再改它。

剩下的是一个**设计与平台的真冲突，需要人决定**，Task 10 没有擅自定：设计稿
`layout-v2.html` 的 `.pane` 是 `rgba(255,255,255,.05)` 叠在 `#0b0f14` 的**不透明暗底**上，
所以面板"应该读起来是暗的"；但平台的 `Material.Thin` 采环境，亮房间里就读成 `#A6A6A3`
（多点均值 `#A6A5A2`，设计意图约 `#121A28`，亮度差约 8 倍）。而 §5.4 又明确写
「用 `backgroundMaterial` 做玻璃质感，**而不是自己叠半透明色块**」。三者不能同时成立。

**可读性用 WCAG 实数说，别用"不足 2:1"这种含糊话**（按实测面板色 `#A6A6A3` 计算）：

| 文字角色 | 压在实测面板 `#A6A6A3` 上 | 压在设计意图底色 `#080E17` 上 |
|---|---|---|
| `labelSecondary #8BA8C9`（状态副行、次要信息） | **1.01:1** —— 几乎完全不可读 | **7.87:1** |
| `labelPrimary #E8F2FF`（主值、列表主文字） | **2.16:1** —— 连大字号的 3:1 都不到 | **17.11:1** |

这四个数才是给人裁决用的依据：现状不是"偏亮一点"，是**次要文字实际不可读**。

- **面板填充有设计裁决值，不是"没有依据可推"**：设计 §5.4.1 的「面板」= `rgba(90,150,220,0.09)`
  = 工程里的 `fillLight` `0x175A96DC`，用途明确写着「右侧玻璃面板、按钮底」。
  **但它解决不了这个问题**：实测把 9% 的 `fillLight` 叠在 `#A6A6A3` 上得到 `#9FA5A8`，
  `labelSecondary` 从 1.01:1 变成 **1.01:1**（纹丝不动），`labelPrimary` 从 2.16 到 2.21。
  所以「实现对齐设计 token」和「把面板压暗到可读」是两件事：
  **前者有裁决值（见记账 13），后者——超出该 token 的额外压暗量——才是留给人的产品决定。**
- 额外压暗**技术上可行且已被同一批截图自证**：面板内**应用自己画的元素**（WORLD 页选中行高亮底、
  FOCUS 页进度环、`＋新建闹钟` 按钮）叠在 `backgroundMaterial` **之后**就是正常暗色、清晰可读。
- 但**压暗量是环境相关的**：模拟器那间房是亮白色卧室，固定窄视角相机也只能给这一种环境。
  「该压多暗」在真机的实际房间里可能完全不同 → 这一项标 **「待真机 + 待产品决定」**。
  可定量的部分（采样色值、四个对比度、玻璃采环境而非应用背景、圆角没丢、配色表没错）
  已经在模拟器上定论。

### issue ② 的最终裁决与实施（2026-08-20）

上面三条排除法与「设计-平台真冲突」的分析**全部保留有效**，但结论不再是"待人定"：
**用户选了不透明面板**，代价（偏离 §5.4「用毛玻璃而不是自己叠色块」）明确接受，记为第 5 条设计偏差。

实施 = `ClockVolume.kt` 面板那段 modifier 链：撤掉 `backgroundMaterial(Material.Thin)`，换成
`clip(16dp)` → `background(fillSecondary)` → `background(fillLight)` → `border(1dp, dividerLine, 16dp)`。
`fillSecondary` 那层负责**不透明**（`fillLight` 只有 9% alpha，单铺会透出 EarthView）；
`fillLight` 是 §5.4.1 指派给「右侧玻璃面板、按钮底」的 token（原记账 13 由此关闭）；
`border` 关闭原记账 11。设计契约落在 `.spatialsdk/design-contract.md` v1，验证记录在
`.spatialsdk/ui-verification-log.md` 轮次 1，脚本 `.spatialsdk/tools/verify-issue2.sh`。

**上机结论（`issue2-{1,2,3}-*.png`，emulator-5554）**：

| 判据 | 结果 |
|---|---|
| 面板读起来是暗的 | **PASS** —— 面板/窗口底亮度比 **11.0×（b1 的 165/15）→ 1.11×（本轮 41/37）**，帧内比值 |
| 描边 + 16dp 圆角 | PASS（3× 裁剪图可见） |
| 文字可读 | PASS（目视，`27°`/`32°/23°`/`74%·1级`/`05:30/19:05` 均可读） |
| 面板内输入未回归 | **PASS** —— 点「＋新建闹钟」拍到编辑器打开。撤掉 `backgroundMaterial` 那层 modifier node（它带 `depth` 与 `isCollisionEnable`）不影响命中测试 |
| 面板绝对色值 ≈ `#0F1A29` | **不可判定** —— 见下面「整窗半透」 |

### 平台观察：某些帧整个窗口以约 60% 不透明度合成（**不是应用能修的**）

`issue2-1-home.png` 里面板空白区实测 `#25282E`（L=41）而非期望 `#0F1A29`（L=27），且横扫呈
周期起伏、极差 20，周期对得上背后墙面的同心圆。

**归因证据是帧内的、很强**：左栏与那颗 3D 地球**代码一行没改**，却同样透出同心圆
—— **Compose 的 `background()` 不可能让一个不透明的 3D mesh 变透明**。所以半透发生在
窗口/合成层。而 `task10-b1-fixed-home.png` 证明窗口**能**完全不透明（同一段左栏代码在那帧是
`#090E16` / L=15）。

**待验假设**：该帧房间内容与 b1 完全不同（壁画+沙发 vs 卧室+床），怀疑当时别的会话占着
环境/焦点、我方窗口被按未聚焦态压暗。下一轮设备取证顺手抓 `dumpsys window` 的 focused app
即可判别。**推论**：跨轮次比较面板绝对色值不可靠，**要比就比帧内比值**。

### 深色调色板里 WCAG 比值会骗人：判可见性要看每通道差

近黑色区两色的 WCAG 比值被压缩在 1.0–1.2，**同一个 1.03:1 可以对应"看得见"和"看不见"两种结果**：

| 关系 | 每通道差 | WCAG | 实拍可见性 |
|---|---|---|---|
| NavBar 未选中 `fillTertiary` 压 `fillSecondary` | (6,8,15) | 1.03:1 | b1 图上**看得到**淡药丸形 |
| 面板内按钮 `fillTertiary` 压新面板 `#0F1A29` | **(1,4,3)** | 1.03:1 | **看不见** |

所以本项目判「两个暗色分不分得开」用**每通道差**，WCAG 只用于判文字可读性。

## tnum 等宽数字结论（Task 2，模拟器实测，round 2 含阴性对照）

**产品要求（已确认满足）**：数字跨位宽变化时不能抖动。`LeftColumn` 里的四位数字（"11:11" vs
"00:00"，同 style、同位数、只换数字值）实测宽度全部相等——**不管 `fontFeatureSettings` 是否
传 `"tnum"`**：

```
TnumProbe: A tnum=on  text=11:11 width=430
TnumProbe: B tnum=on  text=00:00 width=430
TnumProbe: C tnum=off text=11:11 width=430
TnumProbe: D tnum=off text=00:00 width=430
```

**计划要求（未确认，round 1 的结论被证伪）**：round 1 只测了 A/B，把"A==B"当成"tnum 生效"的
证据——但缺阴性对照，"A==B" 同样可以用"这套字体的 lining digits 本来就等宽、跟 tnum 开不开无关"
来解释，两种假设无法区分。round 2 加了 C/D（`style` 上完全不传 `fontFeatureSettings`），
结果 **C==D，且和 A、B 一样都是 430**。这直接证伪了 round 1 的结论：**设备字体本身的数字字形
天生等宽（inherently tabular），`fontFeatureSettings = "tnum"` 在这套字体上的独立作用没有被证实**
——它可能确实在起作用但被字体默认行为掩盖，也可能是纯粹的空操作，本实验设计无法区分这两种情况。

**结论怎么用**：产品要求已满足，不需要固定宽度容器兜底；继续在 `TextStyle` 上写
`fontFeatureSettings = TABULAR_FIGURES` 作为零成本保险——换字体时这行可能从"没用但无害"变成
"真正必需"，删掉它没有收益。但**不要把这次实测引用为"tnum 已验证生效"**：要证明 `tnum` 本身的
因果作用，需要换一套已知非等宽的字体做对照才能分离两个假设。

## 地球场景结论（Task 4，模拟器 emulator-5554 实测）

证据：`.spatialsdk/ui-verify/task4-logcat.txt`（按标签筛过、**入库**）。推导过程见
`.superpowers/sdd/2026-08-19-spatial-clock-implementation/task-4-report.md`。
截图 `task4-r{1..5}-*.png` 与原始全量 `task4-r{1..5}-logcat-full.txt` 按 `.gitignore:38/43`
**不入库，只在本地**；要重现用 `.spatialsdk/tools/verify-probe.sh`。

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
落点另有截图佐证（伦敦压在不列颠、迪拜压在波斯湾、约翰内斯堡压在南非、北京压在华北），
但那张图不在仓库里，**可核对的判据是 logcat 里的 `distFromCentre` 与 `PIN-READOUT`**。

### 自转轴只能是引擎 Y

`EulerAngles(0f, deg, 0f).toQuat()`——**yaw 分量**。球心在局部 Y 轴上，绕 Y 转球心原地不动；
绕 X/Z 会把整个球绕实体原点甩出去（那是公转不是自转）。System tick 实测 `dt≈0.0167s`（60Hz）
且**跑在主线程**（`TICK-DIAG ... thread=main`），所以 `advance` 里可以直接调 `@MainThread` 实体 API。

### 昼夜暗罩降级到 V2（本任务实测后删除）

原实现（`createSphere` + 横向 alpha 渐变贴图 `night_mask.png`，`BlendingMode.TRANSPARENT`）
在设备上**把整个可见半球压暗了，包括白天侧**。对照实测（同一相机、同一窗口位姿、只差有没有暗罩，
取圆盘内海洋像素的亮度中位数）：

```
有暗罩：25.4 / 26.8
无暗罩：35.2 / 36.4      ← 且左右方向是平的，没有残留梯度
```

三条已定位的原因，V2 重做时从这里接：

1. **`MeshResource.createSphere` 的 UV 原点/绕向没有文档**。把渐变的暗核放在 u=0.75 时，
   实测它落在网格局部方位角约 50°（±25°，单次截图估计），不是假设的 270°。要么先用一张
   高对比诊断贴图上机测出这个常量，要么改用自建网格（自己控制 UV）。
2. **朝向公式的符号错了**。绕 Y 转 yaw 会让局部方位角 θ 落到 `θ − yaw`，所以正确形式是
   `yaw = θ_暗核 + subsolarLon + 18`，而原先的 `spinQuat(-azimuth)` 把经度项的符号弄反了。
3. **设计层面还没定**：本工程的自转是 3°/s 的摆件转速（2 分钟一圈），不是真实地球自转。
   暗罩若做成 earth 的兄弟节点，暗区在空间里固定、地理从底下转过去，看起来自然，
   但"暗的正好是夜里的城市"只在某一瞬间成立；若做成子节点跟着自转，地理对得上但暗区像贴在地球上。
   这个取舍要先定，再谈贴图朝向。

贴图生成器保留在 `.spatialsdk/tools/make-night-mask.py`（2048×1024 RGBA，参数可调，**入库**），
`app/src/main/assets/textures/night_mask.png` **已删除**——不留没人用的资产进包。

### 清理路径：两条上机验过 + 竞态路径仅代码审查

`EarthScene` 自己攥住 `content`/`root`/`earth`（不是 Task 0 `ProbeHandle` 那种「build 正常返回后
才登记」的形状——那个漏异常路径）。`build` 一 `addEntity` 就登记，异常时 `dispose()` 再重抛；
`build` 唯一的挂起点（`loadSuspend`）恢复后立即检查 `disposed` 并拆除；方法收尾处再留一次兜底检查。

- 异常路径（把资产路径改成不存在的文件，**上机验过**）：
  `E earth scene build failed; tearing down` → `I earth scene torn down` → `E earth scene failed`，
  没有静默泄漏。同时 2D UI 照常工作（地球加载失败不拖垮页面）。
- 正常路径（优雅关闭，**上机验过，两轮独立复现**）：`KEYCODE_BACK` → `ActivityTaskManager:
  finishIfPossible reason=app-request` → 155~182ms 后 `I earth scene torn down`。
  **`pico-cli app stop` 验不出这个**（force-stop 语义，`DisposableEffect` 一行都不跑）。
- 竞态路径（`dispose()` 撞上未完成的 `loadSuspend`）：**只有代码路径审查 + 日志钩子
  （`"dispose arrived during load; tearing down immediately"`），没有运行时证据**。触发需要在
  12.8MB usdz 解码完成前关窗口，模拟器上装机→启动→首帧的时序不受控，两轮实施都没找到可靠触发手段。

## 跨任务契约：前台服务必须持有三个独立计时引擎（Task 5 fix round 定稿）

**必须持有秒表 + 倒计时两个独立 `TimerEngine` 实例，外加一个番茄钟引擎——一共三个独立实例，
不是一个共享实例。**

背景：Task 5 的 brief 原本给的是单一 `class TimerEngine`（配一个 `mode: TimerMode` 字段）。
实现时发现这个设计自相矛盾：一个实例只有一对 `accumulatedMs`/`startedAtMs`，装不下"暂停的
秒表 + 正在跑的倒计时"两份并存状态；而 `setCountdown` 按规格必须停表清零，共享实例会让用户
在倒计时页调时长时把正在跑/暂停的秒表悄悄清零——这是真实的数据丢失场景，不是代码整洁度偏好。
Fix round 把这个架构分歧提交人工裁决，**裁决保留两个独立 `TimerEngine` 实例**，
并删除了原来试图让单实例兼顾两种用途的 `TimerMode` 枚举与 `mode` 字段（纯净删除）。

**已落地（Task 8）**：`TimingService` 里就是 `stopwatch` / `countdown` / `pomodoro` 三个字段，
`TimerEngine` 一行未改。**后续任务同样不要合并它们。**

## 前台计时服务结论（Task 8 + fix round 1，模拟器 emulator-5554 实测）

证据（均入库）：`task8-logcat.txt`、`task8-round2-logcat.txt`、
`task8-svc-{visible,backgrounded,notif-denied}.txt`、`task8-windows-backgrounded.summary.txt`、
`task8-prefs-before.xml`、`task8fix-*-p{1,2}-{logcat,svc,notif}.txt`、`task8fix-*-prefs-before.xml`。
截图 `task8-{1..5}-*.png` / `task8fix-*-{1,2}.png` 按 `.gitignore:38` **不入库，只在本地**。

`timing/TimingService.kt` 是**计时状态的唯一真源**：秒表 + 倒计时两个独立 `TimerEngine`、
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
   一条不缺，22:50:16 重新绑定。（这条 TICK 日志 Task 10 起走 `debugLog` 门控，只在 debug 构建里打
   ——门禁 B 装的就是 debug APK，取证能力不变。）
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
tick 照跑（`foreground=true`），无崩溃。权限弹框在体积窗口内正常渲染，所以
`rememberLauncherForActivityResult` 在 `WindowContainer` 内可用，不需要单独的 `LaunchActivity` 路由。

被拒时通知是**被系统整条丢掉**的：`AppSettings: ... importance=NONE`，`NotificationRecord`
一条不剩（连 FGS 那条也不显示），但 `notify()` **不抛异常**（`ALERT-POSTED` /
`notify ongoing text=秒表 计时中` 两条都在 `granted=false` 下打出来，`AndroidRuntime:E` 零条），
计时/闹钟/补弹全部照常。所以被拒路径的兜底就是补弹。

### 陷阱：「只在有东西在跑时发布状态」会吞掉"跑完了"这一帧

`tickLoop` 里为了不让空闲期每秒白重组 5 次，只在 `anyRunning` 时 `publish()`。**这个条件单独用
是错的**：倒计时归零那一帧先把 `countdown.pause()` 掉，`anyRunning` 立刻变 false，于是那一帧
（也就是唯一带 `countdownFinished=true` 的一帧）永远发不出去。上机实测过这个 bug——
倒计时停在 `00:00`、按钮还写着「暂停」，「时间到」不出现。修法是加一个 `mutated` 标记，
本 tick 改过状态就必须发布（跨天归零同理）。阴性/阳性对照见
`task8-countdown-{red,green}-logcat.txt`（入库），green 里是「时间到」+「知道了」。

### 到点告警 / 补弹 / 落盘恢复（fix round 1）

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
  商店政策敏感，为已覆盖的场景引入不划算。**这条决定有一个必然后果，见下面「记账」的产品洞。**
- **补弹一律不重发通知**：`onCreate` 只回填界面上的提醒。实测 force-stop 后重开
  `RESURFACE countdownFinished=true`，同一时刻 `dumpsys notification` 里只有 id=1001、没有 2001。
- **秒表快照只在"暂停且有内容"时落盘**，正在跑就把磁盘上那份清掉（否则重开会恢复过期数据）。
  写入走 `tickLoop` 的边沿触发。实测
  `PERSIST stopwatchSnapshot=StopwatchSnapshot(elapsedMs=9002, laps=[3002, 3000])` → force-stop →
  新进程 `seededStopwatchElapsedMs=9002 seededLaps=2`，界面 `00:09.00` + 两行计次、按钮是暂停态。
  计次要恢复 `lastLapTotalMs = laps.sum()`（不是总用时），否则重开后的第一段会把前面所有段再算
  一遍——`TimerEngineTest` 用一个总用时≠分段和的快照钉死这条。
- **「只问一次」必须是持久标记**：`LaunchedEffect(Unit)` 只保证"每次挂载一次"，而重开窗口就会
  重挂载这棵树（`task8-logcat.txt` 里同一个 pid 18113 出现过两条请求日志）。标记落在
  `ClockStore.notificationPermissionAsked`，且在拉起弹框**之前**写盘。
  **证据强度（措辞已按复审降级）**：实测被拒的第一段弹框出现一次、force-stop 后的第二段不再弹。
  但"第二段不再弹"这个观测**与 Android 自身的重复拒绝抑制一致，没有排除它**——
  真正钉死这条修复的是**代码判据**：`!store.notificationPermissionAsked` 这个门 +
  落盘里的 `notif_asked=true`。设备观测只能说「与修复一致」，不能说「测的正是原先坏掉的属性」。
- **`onSnooze` 的语义已上机定论**：注入两条同一 hh:mm 的闹钟让它们落进同一个轮询窗口 →
  `ALARM-FIRED ids=[…001, …002] queue=[…001, …002]`；卡片渲染的是 `ALARM-CARD-SHOWN id=…001`，
  服务侧 `SNOOZE-APPLIED snoozedId=…001 until=+10min queueAfter=[…002]`，随后
  `ALARM-CARD-SHOWN id=…002` 接上卡片。**被显示的那个才是被 snooze 的那个**，
  `firingQueue.first()` 与卡片是同一条 `firingAlarm`。
- **UI 页面状态的播种要把"未确认的到点"算进来**。`TimerPage` 的 uiMode 原先只看
  `countdownRunning`：倒计时在别的页归零后回到本页，`countdownRunning` 已经是 false，uiMode 落到
  秒表，而「时间到」只在倒计时分支渲染 → StateFlow 里有 `countdownFinished=true`，界面上**零暴露面**。
  上机 RED/GREEN 对照：RED 是 `00:00.00` 的秒表，GREEN 是「时间到」+「知道了」。
  **证据强度（措辞已按复审更正）**：这组对照确实证明了修复消除了这个缺陷，但
  **不能声称"两轮只差那一个播种表达式"**——那不可能为真。RED 若带着 `TimerPage.kt` 那条
  `LaunchedEffect(countdownFinished)`，页面挂载时 key 已经是 true、效应会立刻把 uiMode 切到
  COUNTDOWN，RED 就根本拍不出秒表画面。**RED 实际是修复前的状态：播种表达式和反应式切换两处都没有。**
  所以这组对照的分辨率是「修复前 vs 修复后」，不是「隔离到单个表达式」。
  修复本身包含两处：播种，外加一条反应式切换（覆盖"用户正停在秒表标签上"和"本页在服务连上之前
  就挂载了"这两种播种碰不到的情况）。

### 已知取舍

- 服务只在「有任何计时在跑 或 有启用的闹钟」时驻前台。未确认的「时间到」**不**延长服务寿命——
  那样会让一条通知因为一个早已到点的提醒无限期挂着。改用「告警通知 + 落盘补弹」覆盖这个场景。
- **正在跑**的秒表/倒计时被强杀后仍然丢失（只有"暂停的秒表"落盘）。跑着的秒表由 FGS 保证存活，
  它的快照每 tick 都在变，落盘就变成每秒 5 次写 SharedPreferences。
- 告警通知的点击意图（`PendingIntent` → `LaunchActivity`）**只有代码路径证据**：模拟器上没有
  可靠的点通知手段。

## 天气结论（Task 9 + Task 10 字形实测）

- Open-Meteo，无 API Key；WMO code 经 `WmoCodes` 显式映射表消费，未覆盖的 code 落到
  `WeatherKind.UNKNOWN`（不崩）。缓存 30 分钟（`WEATHER_FRESH_WINDOW_MS`），失败用旧数据并标注
  「更新于 X 分钟前」，绝不因天气失败阻塞时钟。
- `beaufortScale` 的单位口径（Open-Meteo 默认 km/h）**是假设，未经项目内证据确认**——请求里没传
  `wind_speed_unit`，按官方文档默认解释。`WeatherRepositoryTest` 现在把 12 个阈值**每一个都用
  两侧夹住**（Task 10 补齐 index 3–10），整表平移或单点改动都至少让一条用例失败。
- **天气图标字形：模拟器实测 9 个 `WeatherKind` 全部有字形，零豆腐块**（Task 10 用临时钩子把
  `WeatherKind.entries` 的图标强制渲染一遍，钩子已删）。`☀ ⛅ ☁ 🌫 🌧 ❄ ⛈ 🧊` 全部渲染成**彩色
  emoji**，`UNKNOWN` 是 `—`。此前 `Weather.kt` 注释里"补充平面 emoji（🌫/🌧/🧊）字形覆盖未验证"
  的担心**不成立**（该注释已同步更新）。真机同款系统镜像待确认。
  副作用记一笔：彩色 emoji 与 §5.4.1 刻意的单色深空蓝配色不一致，属于观感取舍，未处理。

## 日志约定（Task 10 定的，新加日志按这个分类）

两类日志，判据不同：

- **产品事件**（`ALERT-POSTED` / `PERSIST …` / `PHASE-BOUNDARY` / `STATS-SAVED` /
  `startForeground OK` / `tap reached` 等）：低频、一次一件事，
  是排障与门禁 B 判定的依据，**不门控**。
- **周期性仪表**（`onGloballyPositioned` 坐标打点、秒表 16ms 插值循环的 tick、服务 5 秒一条的
  `TICK` 心跳）：按布局 pass / 定时器频率触发。SpaceClock 是**常驻挂件**，无门控地留着就是整个
  运行期每秒数行，既浪费也会把上面那类真正需要看的行淹掉。**一律走
  `platform/DebugLog.kt` 的 `debugLog(tag) { … }`**（`BuildConfig.DEBUG` 门控 + `inline`，
  release 下连字符串拼接都不发生；`app/build.gradle.kts` 里 `buildConfig = true` 就是为它开的）。

**一个有意保留的例外，别当成判据不自洽**：`WeatherRepository` 的 `WEATHER-SNAPSHOT` /
`HTTP-STATUS` 按**节奏**算是周期性的（`ClockVolume` 里 5 分钟一轮 × 已选城市数，4 城 ≈ 每小时
48 行，永久运行），但仍然**不门控**。理由：它们每一行的内容都是判别性的
（`source=cache-fresh|network|cache-stale|none` + HTTP 状态码），是"天气到底走了缓存还是网络、
为什么显示的是旧数据"唯一的线上依据，而这恰恰是最容易在真机上出问题、又最难复现的一类。
每小时 48 行的量级也远低于坐标打点（每秒数行）。**要改这个决定，先想清楚线上排障用什么替代。**

门控有效性的静态验证（release 与 debug 的 dex 常量池对照，`assembleRelease` 后 `strings`）：

```
literal                        release  debug
contentPanel w=        (门控)     0        1
TICK ticks=            (门控)     0        1
stopwatch tick count=  (门控)     0        1
navButton page=        (门控)     0        1
ALERT-POSTED id=     (不门控)     1        1
tap reached page=    (不门控)     1        1
```

门控的字面量在 release dex 里**整条消失**，同一批文件里不门控的产品事件字面量照样在——
这排除了"字面量只是留在常量池、分支其实还在打"这个反向假设。
**仍未做**：release 包没有真正装机跑过（未签名，装不上），所以这一项是「静态 + 代码路径」级证据。

## 已知待办 / 记账（14 条，其中 11/13 已由 issue ② 关闭，新增 15/16；交最终整支评审）

功能与数据一致性：

1. 告警通知 2002 的文案在队列被部分消费后不刷新（只在队列清空时 `cancelAlert`）→ 纯文案陈旧。
2. `snoozeUntil` 仍只在内存，而未确认的到点闹钟已能跨重启 → **持久性不对称**。
3. `firingQueue` 不因 UI 删除/停用闹钟而剪枝 → 卡片可能显示已被删掉的闹钟。
4. `ensureRunState` 的 `notify(NOTIFICATION_ID,…)` 未像 `postAlert` 那样 `runCatching` 包裹。
5. **正在跑**的秒表/倒计时强杀后仍丢（裁决只覆盖暂停态：持久化运行态要每 200ms 写一次
   SharedPreferences，代价不划算）。
6. **产品洞（不引入 AlarmManager 的必然后果）**：应用自安装/重启后**从未打开过**时，闹钟根本
   不会开火——补弹也补不出没发生过的事件。这不是 bug，是那条决定的边界，要不要改属于产品决策。

证据强度类与平台事实（都不是 bug，是"别把它当已验证/别按直觉推理"）：

7. 无钩子构建只验到 HOME 渲染：告警 / 落盘 / 补弹全部在**带临时钩子**的构建上取证。
8. `FGS_AVAILABLE=true` 与告警"确实会响"仍是模拟器 / `dumpsys` 层证据，**真机待办**；
   通知点击意图只有代码路径证据。
9. ~~右侧面板在亮环境下发白（issue ②）~~ → **已关闭**。用户 2026-08-20 裁决改不透明面板，
   已实施并上机验过（面板/窗口底亮度比 11.0× → 1.11×，输入未回归）。
   见「正面布局结论」节的「issue ② 的最终裁决与实施」。
10. **提醒卡遮罩在视觉上盖不住右侧玻璃面板**（输入不受影响）。**issue ② 之后预期已解决但未验证**
    ——面板不再是合成器层，遮罩应该盖得住了；验它需要闹钟到点态，改动那轮没制造该状态。
    以下是改动前的事实记录：`AlarmCard` 的 0.72 遮罩是
    Compose 的 `background`，而玻璃面板是合成器层、不参与 Compose 绘制顺序。既有证据
    `task7-3-alarm-card-fired.png`：左栏被压暗（亮度≈75），右侧面板连同整页内容
    （新建闹钟编辑器、保存/取消）**一点没被压暗**（亮度≈169，与无遮罩轮次量到的面板亮度一致）。
    **输入是安全的**——命中测试走 Compose 兄弟节点顺序，`AlarmCard` 是最后一个兄弟，
    指针照样被它吞掉（Task 7 有阴性/阳性对照）。不是本任务引入的回归，修复前后同样成立；
    是本任务发现的"合成器层"这条平台事实的直接后果。要真正盖住得让遮罩也走玻璃层，或者
    在面板内部再叠一层——属于 issue ② 那个后续项的一部分。

设计一致性类（Task 10 逐项比对 `layout-v2.html` C 方案后记的，均未改）：

11. ~~**面板缺描边。**~~ → **已关闭**（issue ② 那轮加上了 `border(1dp, dividerLine, 16dp)`，上机可见）。原始记录： 设计 §5.4.1 的「描边」= `rgba(120,170,230,0.20)` = 工程里的
    `dividerLine` `0x3378AAE6`（alpha `0x33`=51/255=0.20，`#78AAE6`=rgb(120,170,230)，逐值对上），
    用途写着「面板边框、列表分隔线」；实现里面板**没有描边**。
    （取 spec 的 token 值，不要取 `layout-v2.html` mock 里那个 `rgba(255,255,255,.11)`——
    HTML 是示意稿，§5.4.1 才是配色裁决表。）
12. **左/中/右横向权重差在中缝，不在左栏。** 实现 `0.40 / 0.04 / 0.56`。设计侧按
    `layout-v2.html` C 方案的百分比推导（globe `5%→41%`、pane `47%→96%`，在内容区 `5%–96%`
    即跨度 91% 内归一化）：左栏 `(41−5)/91 = 0.396`、中缝 `(47−41)/91 = 0.066`、
    面板 `(96−47)/91 = 0.538` → **`0.40 / 0.066 / 0.54`**（三项和为 1.000）。
    所以**左栏是精确吻合的**，偏差是中缝偏窄（0.04 vs 0.066）、面板偏宽（0.56 vs 0.54），
    差 2–3 个百分点，属于检查清单的「不可判定/次要」档。
13. ~~**面板填充 token `fillLight` 从未被用在任何 UI 上。**~~ → **已关闭**（issue ② 那轮用在了面板，正是 §5.4.1 指派的用途）。原始记录： `theme/SpaceClockTheme.kt` 定义了
    `fillLight = Color(0x175A96DC)`，设计 §5.4.1 明确把 `rgba(90,150,220,0.09)` 指派给
    「右侧玻璃面板、按钮底」，但全工程 grep 下来它一次都没被消费，面板的 modifier 链里没有它。
    **这条有明确的设计裁决值**（不像"额外压暗多少"那样需要人定），但单独应用它
    **改不动可读性**（实测 1.01:1 → 1.01:1，见「正面布局结论」那节）。
    它和记账 11（描边）、issue ②（待定的 scrim）是**同一段 modifier 链上的同一个视觉决策**，
    所以**打包成一个后续项一起改**：等真机上把压暗量定下来，一次改完填充 + 描边 + scrim，
    而不是现在先单独动一次、真机定完再动第二次（而且新的视觉输出眼下没有设备证据）。
14. 外边距：实现 20.dp（=40px，占窗口高 3.6%），设计稿上下留白 9%、底部 5%。
    Task 1 定的 20.dp 沿用未动。

15. **按钮底与面板撞色（issue ② 引入，用户 2026-08-20 明确裁决"不改"）。** 面板合成色
    `#0F1A29` 几乎正好落在 `fillTertiary` `#0E1626` 上，每通道差仅 **(1,4,3)** → 面板内
    17 处用 `fillTertiary` 当 `containerColor` 的次要按钮**底看不见，只剩文字**
    （`issue2-2`/`issue2-3` 实拍：「＋新建闹钟」「取消」「工作日/周末/自定义」；
    选中态用 `fillPrimary`/`interaction`，不受影响）。
    **不是选错 token**：换成 §5.4.1 指派的 `fillLight` 只得 1.13:1，换成设计意图面板色
    `#121A28` 配 `fillTertiary` 是 1.03:1 —— 深调色板里拿不到 WCAG 1.4.11 的 3:1 非文本对比度，
    出路只有亮描边 / 提升为强调色实心 / 接受"文字按钮"样式三条。已提交用户，**裁决为接受**。
16. **第 5 条设计偏差：面板用不透明色块而非 `backgroundMaterial`**，偏离设计 §5.4
    「用毛玻璃做质感、不要自己叠半透明色块」。用户 2026-08-20 明确接受，换来的是对比度
    不再随环境漂移。四档毛玻璃（`Thin`/`Regular`/`Thick`/`Thickest`，见「已知陷阱」）
    **从未被实测过**，如果日后想回到玻璃方案，那是第一个该试的方向。

## 已知陷阱

- **`local.properties` 已被 gitignore**，内含 `sdk.dir` / `spatial.tools.dir`，换机需重建。
- **模拟器是多会话共享的**：截图里可能出现别的会话的窗口。判定前先确认画面里那个窗口是自己的。
- **模拟器截图相机固定且视角窄**：只能验渲染管线、相对位置与色值采样，**不能**用来判断"整体
  摆放好不好看 / 构图对不对"。凡是靠整体观感才能判的项，标「待真机」而不是「已验证」。
  背景房间是亮白色卧室，会直接影响所有 `backgroundMaterial` 玻璃层的观感。
- **`pico-cli capture screenshot` 会给出落后 6–8 秒的旧帧**：不能拿单张截图的时刻去推断当时的
  UI 状态；关键时刻要么在日志里留标记，要么把等待放宽到 ≥12s。
- **`pico-cli app stop` 是 force-stop**：`onDispose` / `DisposableEffect` 清理路径一行都不跑。
  要验清理得用 `KEYCODE_BACK` 优雅关闭。
- **禁止用 `withFrameNanos` 驱动任何循环**：它在 `WindowContainer` 里只触发一次，游戏循环一帧
  都不跑。用 `while (true) { …; delay(n) }`。
- **`SpatialView` 的 `initial` 是 suspend lambda，跑得比 `LaunchedEffect` 晚**，而且异常会被吞掉。
  场景内容不要在 `initial` 里写完再由 effect 覆写一遍——`initial` 只建场景 + 翻开关，
  实际内容由带该开关 key 的 effect 统一施加，全程只有一处写入点。
- **`backgroundMaterial` 是合成器层**：不吃 Compose 的兄弟节点绘制顺序（会盖住后加的兄弟节点），
  也不采样应用画在它后面的背景（采环境）。做遮挡/背景判断时别按普通 `background` 推理。
  三个已实测的后果：① NavBar 加在后面照样被面板遮住（见「正面布局结论」）；
  ② 根容器铺 `background` 改不动面板色（issue ②）；
  ③ **`AlarmCard` 的全屏遮罩视觉上盖不住面板**（记账 10）。
  **"后添加就画在上面"只对普通 Compose 绘制成立；"最后一个兄弟先接指针"对输入始终成立。**
  这两件事要分开说——本工程的注释里就写错过一次。
- **SpatialUI `Button` 没有 `style` 参数**（样式由 `size` 推导）；禁用只传 `enabled = false`；
  **不要自定义 `ButtonSize`，会静默丢圆角**。选中态靠 `colors` 区分。
- **`ListItemColors` 未高亮时必须显式传 `Color.Transparent`**，不能留 `Unspecified`——
  `copy()` 内部 `takeOrElse{}` 会回落到默认 15% 白，导致所有行看起来都是"选中"底色。
- **提醒卡片的遮罩必须消费指针事件**，否则点击穿透点亮下层按钮；且 SpatialUI 的颜色角色不透明，
  要自己 `copy(alpha = …)`。
- **时间基准用 `SystemClock.elapsedRealtime()`**（改系统时间/时区不会带偏计时）；
  **墙上时间显示**才用 `ZonedDateTime.now()`。这是两件事。
- `androidx.compose.ui` / `foundation` 等构件在 `configurations.all` 里被 `exclude`，
  但由 Spatial SDK 传递提供，`background` / `clickable` / `LazyColumn` 照样能用。
  `ui-tooling` 与 `ui-tooling-preview` **已经是 `debugImplementation`**（Task 10 复核：
  `app/build.gradle.kts:56,65`，release 不会被拖进 Material）。
  行号会漂——本提交自己就在上面插了 `buildConfig = true` 的注释块把 52/61 顶成了 56/65。
  **引用行号时顺手 `grep -n` 复核一次**，别照抄旧值。
