# SpaceClock — Agent Guide

PICO OS 6 体积窗口时钟摆件。Spatial SDK 6.0.0 / Kotlin 2.1.20 / SpatialUI。

- 包名 / applicationId：`tech.illusion.spaceclock`
- 容器：`Form.Volumetric`（`DefaultWindowContainer`，manifest `windowcontainer.style=2`）。**架构决定，不许换成 `Form.Planar` 或 `Stage`。**
- WindowContainer id：`SpaceClockVolume`
- compileSdk / targetSdk / minSdk = 35，AGP 8.13.2
- 构建：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`，然后 `./gradlew :app:assembleDebug`（`JAVA_HOME` / `PICO_HOME` 不跨 Bash 调用持续，每条命令自己 export）
- 设备验证：`./.spatialsdk/tools/verify-probe.sh emulator-5554`（门禁 B：锁内装机→启动→截图→停应用→释放锁；分析全在锁外）

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

## 探针遗留物（Task 8 要清掉）

- `app/src/main/java/tech/illusion/spaceclock/timing/ProbeService.kt` — Task 8 换成真正的计时服务
- ~~`earth/ProbeScene.kt`~~ — Task 4 已删除，换成 `earth/EarthScene.kt` + `earth/EarthSpinSystem.kt`
- ~~`content/HomeVolume.kt`~~ / ~~manifest 0.4x0.4x0.4~~ — Task 1 已替换

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
