# SpaceClock 设计契约 —— 增量 v1（issue ② 右侧面板不透明化）

**基线不在本文件里。** 完整设计锚点（`layout-v2.html` C 方案正面几何百分比表、§5.4.1 的 16 个
配色 token、被否掉的方案清单）已内联在 `AGENTS.md` 的「设计与计划文档」「被明确否掉的方案清单」
两节。本文件只写**本次改动**，其余一切以 AGENTS.md 为准。

## 0. 元信息

- 应用名 / applicationId：SpaceClock / `tech.illusion.spaceclock`
- 设计源类型：用户口头裁决（2026-08-20 会话）+ 设计文档 §5.4.1 既有 token
- 设计源位置：无新增视觉稿；基线见 `AGENTS.md`「设计与计划文档」节
- 契约版本：v1（本项目首个契约文件，仅覆盖右侧面板背景）
- 用户确认时间：待填（门禁 A3）

## 1. 面板清单

| 面板 id | 容器类型 | 面板尺寸 | 层级/父面板 | 出现时机 |
|---|---|---|---|---|
| SpaceClockVolume | WindowContainer | `Form.Volumetric` 0.60×0.45×0.30 m | 根 | 启动即显示 |
| contentPanel | 同容器内 Compose 区 | `weight(0.56)` × `fillMaxHeight` | SpaceClockVolume → Column → Row | 启动即显示，五页共用 |

容器类型**不变**（架构决定）。本次改动不碰容器、不碰布局权重、不碰外边距。

## 2. 元素表（仅本次变更的两行）

| id | 类型 | 父容器 | 锚点 | 偏移 | 尺寸 | 颜色角色 | 圆角/形状 |
|---|---|---|---|---|---|---|---|
| contentPanel | Box | Row | 右 | 由 weight 决定 | `weight(0.56)`×fill | 底：`fillSecondary` + 其上 `fillLight`（**不透明合成**，不再用 `backgroundMaterial`） | 16dp 圆角 |
| contentPanelBorder | Border | contentPanel | 描边 | - | 1dp | `dividerLine` | 16dp 圆角 |

**改动的实质**：`backgroundMaterial(enable = true, style = Material.Thin)` 整条移除，换成
`clip(16dp)` → `background(fillSecondary)` → `background(fillLight)` → `border(1dp, dividerLine, 16dp)`。
`fillSecondary` 那一层是**保证不透明**用的（`fillLight` 只有 9% alpha，单用会透出 3D 视图）；
`fillLight` 是设计 §5.4.1 明确指派给「右侧玻璃面板、按钮底」的 token。

## 3. 状态清单

| 元素 id | 状态 | 视觉变化 |
|---|---|---|
| contentPanel | 五页切换 | 背景色**不随页面变化**（HOME/WORLD/ALARM/TIMER/FOCUS 采样值一致） |
| contentPanel | 环境亮度变化 | 背景色**不再随房间亮度变化**（这正是本次改动的目的） |

## 4. 文案清单

无新增文案。

## 5. 不做清单

- 不改横向权重 `0.40/0.04/0.56`（记账项，非本次范围）
- 不改外边距 `20.dp`（记账项，非本次范围）
- 不改窗口底色 `fillSecondary`（Task 10 已定稿）
- 不加 scrim / 不改任何 label 角色色值（本次靠面板压暗解决对比度，不动文字）
- 不碰 `manifest windowcontainer.materialbackground=0`

## 6. 截图验证计划

| 序号 | 状态描述 | 到达方式 | 本图核对的元素 id |
|---|---|---|---|
| 1 | 启动默认态（HOME） | 启动后等 ≥12s（AGENTS.md：截图管线返回 6–8s 陈旧帧） | contentPanel 背景采样、contentPanelBorder、labelPrimary/labelSecondary 是否可读 |
| 2 | ALARM 页 | 按实测坐标点 NavBar「闹钟」后再等 ≥12s | contentPanel 背景采样（跨页一致性）、页内「＋新建闹钟」按钮可见且**点得到**（验证移除合成器层没影响输入） |

### 通过判据（定量，可被截图核对）

| 判据 | 期望值 | 依据 |
|---|---|---|
| contentPanel 区域采样色 | ≈ `#0F1A29`（容 ±2/通道，PNG 与合成器取整） | `fillLight` 0x17=23/255=0.0902 over `fillSecondary` #080E17 → rgb(15,26,41) |
| 跨页采样一致性 | 图 1 与图 2 的面板采样值相同 | 不透明色不受页面内容与环境影响 |
| `labelSecondary` 对比度 | **7.12:1**（改前 1.01:1） | WCAG，#8BA8C9 on #0F1A29 |
| `labelPrimary` 对比度 | **15.48:1**（改前 2.16:1） | WCAG，#E8F2FF on #0F1A29 |
| `labelTertiary` 对比度 | 3.38:1（低强调角色，**如实记录不达 4.5**） | 60% #8BA8C9 合成为 #596F89 |
| 描边可见 | 面板四边有 1dp 亮边，合成色 ≈ `#24374F` | `dividerLine` 0x33=20% #78AAE6 over #0F1A29 |

**不可判定项（本轮不假装能判）**：整体构图与留白比例（模拟器相机固定且窄）；
提醒卡遮罩现在能否视觉盖住面板（需要闹钟到点态，本轮不制造该状态）。
