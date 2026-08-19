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

地球实体本身也要沿 **Y** 平移 `−CZ` 才能把球心放到视口原点（探针里用实测 `boundsCenter` 动态做的，
见 `ProbeScene.buildProbe`）。

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

## 探针遗留物（Task 4 / Task 8 要清掉）

- `app/src/main/java/tech/illusion/spaceclock/earth/ProbeScene.kt` — Task 4 删除，换成真正的地球场景 + `GeoMath`
  - 但**保留它的生命周期形状**：`ProbeHandle` 演示了 `SpatialView` 3D 内容该怎么清理——`initial`
    （异步 suspend）里 `attach(content, root, earth)`，Compose `onDispose`（同步、主线程）里
    `dispose()`，内部 `content.removeEntity(root)` 后 `Entity.destroy(recursively = true)`。
    `Entity.destroy` 是 `@MainThread`，只能在 `onDispose` 这类主线程回调里调。句柄还处理了
    「窗口在 `loadSuspend` 未完成时就被关掉」的竞态（`attach` 发现已 dispose 就立刻销毁）。
    Task 4 的 `EarthScene` 直接沿用这个形状。
- `app/src/main/java/tech/illusion/spaceclock/timing/ProbeService.kt` — Task 8 换成真正的计时服务
- `content/HomeVolume.kt` 当前只挂探针，没有任何 UI —— Task 1 起替换
- manifest `windowcontainer.defaultsize = 0.4x0.4x0.4`（meters）是探针尺寸，Task 1 换成设计尺寸

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
