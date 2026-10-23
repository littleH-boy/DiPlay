# 长安逸动 2025 款三代大屏流畅度改进方案

## 摘要

针对 DiPlay 在长安逸动 2025 款三代车机大屏上"CarPlay 画面卡顿/掉帧、触摸响应慢"的问题（无线·车热点连接），制定两层改进：

- **方案 A（立即可用）**：新增 `docs/PERFORMANCE.md` 设置调优指南，给出针对低速车机的、有明确验证手段的设置调整顺序（不改代码行为）。
- **方案 B（代码级）**：新增会话内"卡顿监测 + 一键优化建议"。利用已有的 `liveVideoCounters()` 遥测，在检测到持续掉帧/解码慢时弹出对话框，一键"保存并重连"降档（先降帧率、再降分辨率），把现有实验性开关的调优入口自动化地送到用户面前。

不做静默自动降档：项目原则（AGENTS.md 规则 5）要求重连必须经用户同意，方案 B 以对话框确认形式遵守该原则。

## 现状分析

### 渲染管线

iPhone 编码（默认 H.264，HEVC 可选但默认关）→ 车热点 Wi-Fi → `VideoDecodeQueue`（[VideoDecodeQueue.kt](file:///e:/DiPlay/shared/src/main/java/com/shilapi/xcertplay/media/VideoDecodeQueue.kt)，60 帧/8MB 上限，溢出触发 Resync）→ `VideoDecoder`（MediaCodec 硬解，[AndroidMediaSink.kt](file:///e:/DiPlay/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt)）→ Surface 输出。

输出路径由 `carPlayVideoSurfaceMode()` 决定（[CarPlayVideoSurface.kt:15-22](file:///e:/DiPlay/common/src/main/java/com/shilapi/xcertplay/CarPlayVideoSurface.kt#L15-L22)）：
- **默认 TextureView**：帧经应用自身绘制管线再上屏，弱 GPU 车机多一跳开销。
- **SurfaceView**（Smooth video 或 Direct video output 开启时）：帧直接进系统合成器，Direct video output 约省一次刷新。

### 已有性能开关（全部可在设置中调，无需改码）

| 开关 | 位置 | 现状 |
|---|---|---|
| 分辨率 30%–160% | Display | 默认 100%（[CarPlayDisplayScale.kt](file:///e:/DiPlay/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayDisplayScale.kt)） |
| 帧率 30/60 fps | Display | 默认 30（`AirPlayPersistence.loadFps/saveFps`） |
| Efficient video (HEVC) | Advanced → Video and audio | 默认关（低速车机应保持关） |
| Direct video output | 同上 | 默认关，SurfaceView 直通 |
| Smooth video | 同上 | 默认关，节奏化上屏更稳但增加约 90–140ms 触控延迟（[SMOOTH_WIRELESS.md](file:///e:/DiPlay/docs/SMOOTH_WIRELESS.md) 实测 presented fps 42→51） |
| Low-latency decoding (QTI) / MTK tuning | 同上 | 默认关，按解码器型号选用 |

### 诊断手段（已存在）

- FPS 计数器悬浮层（[FpsCounterOverlay.kt](file:///e:/DiPlay/common/src/main/java/com/shilapi/xcertplay/FpsCounterOverlay.kt)），数据源 `sink.liveVideoCounters()` → `LiveVideoMeter` 产出 `LiveVideoReading(shownFps, receivedFps, decodeMillis)`（[LiveVideoMeter.kt](file:///e:/DiPlay/shared/src/main/java/com/shilapi/xcertplay/media/LiveVideoMeter.kt)）。
- 每 5 秒一条 `DiPlay-VideoStats` 日志（rx/shown/decode p50/p90/late/delay/maxGap），诊断报告会收录。

### 本车卡顿根因假设（按可能性排序）

1. 车机 SoC/解码器弱：decode p50/p90 高、解码器持帧（唐上 QTI 解码器实测压约 2 帧）。
2. 默认 TextureView 自绘路径在弱 GPU 上掉帧。
3. 车热点链路抖动（车热点已是无线最优路径；信道问题主要影响 Wi-Fi Direct，不适用车热点）。
4. 触摸慢 = 新帧需经 网络→解码→上屏 全链路，叠加 2 的延迟。

具体根因需真机诊断报告确认（指南第 1 步），但无论根因为何，降分辨率/帧率与直通输出都能缓解。

## 方案 A：设置调优指南（文档）

### 新建 `docs/PERFORMANCE.md`（英文，随仓库惯例）

内容为针对低速车机（含本车）的顺序化调优步骤，每步附"如何用 FPS 计数器验证是否有效"：

1. **先取证**：Settings → Diagnostics → 开启 FPS counter；复现卡顿（滚动列表/地图），记录 shown/rx/decode ms；导出诊断报告（含 VideoStats 日志与解码器名，用于判断选 Low-latency 还是 MTK tuning）。
2. **降帧率**：Settings → Display → 30 fps（默认已是 30；若被改过 60 先改回）→ Apply and reconnect。
3. **降分辨率**：80% 仍卡则 60%（INSTALL.md 已有此建议，指南细化验证方法）。
4. **HEVC 保持关闭**。
5. **Direct video output（实验性）**：跳过应用自绘省一次刷新；代价：图像调节不可用；出现伪影/冻结则关。
6. **按解码器选低延迟选项**：报告解码器为 QTI/Qualcomm → 试 Low-latency decoding（Android 11+）；MTK → 试 MTK decoder tuning。有伪影/冻结即关。
7. **Smooth video 最后再试**：上屏节奏最稳，但增加触控延迟——与本诉求"触摸响应慢"冲突，明确标注取舍。
8. **连接层**：车热点优先 5GHz；终极方案为 USB 数据线直连（最稳延迟）。
9. 每步后用 FPS counter 对比 shown 与 rx 是否收敛，decode ms 是否下降。

### 修改 `README.md`

在 Documentation 列表（README.md:48-54 附近）加 `docs/PERFORMANCE.md` 链接。

（中文速查版在对话中直接交付给用户，不入库。）

## 方案 B：会话内卡顿监测 + 一键优化建议（代码）

### B1 新建 `common/src/main/java/com/shilapi/xcertplay/VideoStutterMonitor.kt`

纯逻辑类（可单测），仿照本仓库"纯函数 + 数据类"风格：

- 输入：`LiveVideoReading`（复用 shared 模块已有类）；内部维护最近 20 个样本的滑动窗口（1 秒采样一次）。
- **建议目标（确定性降档，先算可用建议，无可建议则不监测）**：
  1. fps == 60 → 建议 fps 30；
  2. 否则 displayScalePercent > 80 → 建议 80%；
  3. 否则 displayScalePercent > 60 → 建议 60%；
  4. 否则 → 无建议（已到底档，不监测）。
- **触发条件（满足其一，窗口内 ≥15/20 样本成立）**：
  a. 持续掉帧：`receivedFps >= 24 && shownFps < 0.8 * receivedFps`；
  b. 持续慢解码：`receivedFps >= 15 && decodeMillis >= 33`（30fps 一帧预算）。
- 每会话最多触发一次；`reset()` 在新会话/新解码器时清窗口。
- smoothVideo 开启时不监测（v1：节奏化上屏语义不同）。

### B2 集成到 `CarPlayHostActivity.kt`

- 会话启动时创建 monitor，用 `mainHandler` 1 秒节拍采样（与 [FpsCounterOverlay](file:///e:/DiPlay/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt#L2098) 同源：`sink?.liveVideoCounters()` + `LiveVideoMeter`），会话结束/decoder 重建时 `reset()`；FPS counter 设置开关不影响监测。
- 触发时弹 `AlertDialog`：
  - 正文引用新字符串，含具体建议（如"将帧率降至 30 fps"或"将分辨率降至 80%"，文案由现有 `frame_rate`/分辨率字符串拼装）；
  - 肯定按钮复用现有 `R.string.save_and_reconnect`（CarPlayHostActivity.kt:6274 同款）；
  - 否定按钮"本次忽略"，忽略后本会话不再提醒。
- **应用动作**：`AirPlayPersistence.saveFps(...)` 或 `saveDisplayScalePercent(...)` 写入后，直接调 `restartCarPlay(reason)`（CarPlayHostActivity.kt:6319 附近已有），不走 `saveSettingsAndReconnect()`（避免连带保存设置菜单里未应用的改动）。此即 AGENTS.md 规则 5 的"对话框按钮 + 立即重连"合规路径。

### B3 新增字符串（3 条，`settings_` 前缀）

- `settings_stutter_detected_title` — 检测到画面掉帧
- `settings_stutter_detected_message` — 带一个 `%1$s` 占位（建议内容）
- `settings_stutter_ignore` — 本次忽略

加入 `common/src/main/res/values/strings.xml` 及全部 9 个 locale（values-ar、values-es、values-it、values-ja、values-ru、values-uk、values-zh-rCN、values-zh-rTW），满足 `SettingsTranslationsTest`。

### B4 单元测试

新建 `common/src/test/java/com/shilapi/xcertplay/VideoStutterMonitorTest.kt`：

- 健康流（shown≈rx）不触发；
- 持续掉帧触发（构造 rx 高 shown 低序列）；
- 持续慢解码触发（decodeMillis 高）；
- 窗口不足 20 样本但满足 15 阈值前不触发；
- 每会话仅触发一次；reset 后可再次触发；
- 无可降档建议时不触发。

## 假设与决策

- **不做静默自动降档、不改默认渲染路径、不改解码器选择逻辑**：风险与项目原则（重连需用户同意、实验项默认关）均不允许；本方案让用户一键知情降档。
- 阈值为首版推断值（来自 SMOOTH_WIRELESS.md 唐的实测：健康时 shown≈rx、decode p90 约 70–77ms 属解码器持帧而非车机慢），**待真机诊断报告校准**；阈值常量集中在 monitor 顶部便于调整。
- 触摸慢与 Smooth video 的取舍在指南中显式标注，不在代码里互斥。
- 文档英文入库（仓库惯例），中文速查版只在对话交付。

## 验证

1. **CI 命令**（AGENTS.md 要求，全部通过后才算完成）：

   ```sh
   ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
     :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
     :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
   ```

2. **真机验证**（长安逸动 2025 款三代 + iPhone，车热点）：
   - 按指南第 1 步开 FPS counter 复现卡顿，确认 monitor 弹窗出现且建议合理；
   - 点"保存并重连"后重连成功，FPS counter 上 shown 收敛到 rx；
   - 点"忽略"后本会话不再弹；重连后可再次弹；
   - 分辨率已 60% + 30fps 的极限档下确认不弹窗；
   - 设置界面截图（compact/full + 大字号；若动到行对齐加阿语 RTL 截图）——本方案仅在会话内弹窗，不动 Settings 布局，预计可豁免 `SettingsLayoutPolicyTest` 之外的截图要求，但 lint 必须过。
3. **交付物**：新 APK 路径（mobile 模块 `build/outputs/apk/debug/`）。
