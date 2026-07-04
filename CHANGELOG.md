# Changelog

## 2.1 - 2026-07-04

### Android native night mode

- Added an Appearance card in Settings with System, Light, and Dark options.
- Persisted the selected night mode and applied it through `AppCompatDelegate.setDefaultNightMode(...)`, so Compose colors, system bars, and `values-night` launch resources stay aligned with Android's native night-mode state.
- Kept System as the default so existing installs continue to follow the device setting.
- Updated the debug APK version to `2.1`, keeping the `VADRecorder-v{version}.apk` output naming.

### Recording save fix

- Fixed a case where a confirmed active clip could be discarded after the 30-second silence auto-finish instead of being saved and shown in the recordings list.

## 2.0 - 2026-07-02

### Stability and recorder internals

- Rebased the 2.0 stability work on top of the 1.2 recorder fixes, keeping the 30-second silence finalization behavior.
- Split recording close reasons into `EndSilence`, `ManualStop`, `ServiceStop`, `ReadError`, and `Destroy`.
- Writes active recordings to `.wav.part` first, finalizes the WAV header, then moves the file into place as `.wav`.
- Cleans stale `.wav.part` files on listener startup.
- Added a pluggable `VadEngine` interface with the existing rule-based detector as the fallback implementation.
- Added hidden environment calibration to the rule-based VAD while keeping the existing sensitivity UI unchanged.
- Added JSON sidecar metadata for recordings while preserving legacy WAV compatibility.
- Kept APK output naming as `VADRecorder-v{version}.apk`.

## 1.2 - 2026-07-01

### 录音保存修复

- 修复静音 30 秒收尾倒计时结束后，当前录音片段可能没有自动保存的问题。
- 30 秒自动结束现在与手动停止监听保持一致：只要当前片段已经写入有效音频数据，就会关闭 WAV 文件并保存到录音列表。
- 保留录音收尾倒计时、当前片段提示和最近保存状态，用户不需要手动等待或重复停止来触发保存。

## 1.1 - 2026-07-01

### UI 与主题

- 新增自定义 Material 3 配色方案（浅色：夜空蓝 / 月影青 / 暖沙底；深色：对应暗色变体），替代系统默认色板。
- 新增深色模式支持：`values-night/themes.xml` 定义暗色启动背景；`VoiceRecorderTheme` 跟随系统深浅自动切换。
- Android 12+ 设备优先使用动态取色（Material You），低于 12 回退为自定义配色。
- 状态栏和导航栏跟随当前主题配色自适应（浅色/深色图标），`enableEdgeToEdge` 启用边到边渲染。
- 启动背景色从纯蓝改为暖沙色（浅色）/ 深墨色（暗色），避免启动闪白。
- 中英文字符串大量润色："立刻" → "立即"、"抓到了" → "捕获了"、分隔符 `·` → `/`，语气更自然一致。

### 首页体验增强

- **录音进行中醒目提示**：新增 `ActiveRecordingCard`，录音时显示高亮主色卡片、收尾时显示次色卡片，包含文件名和剩余倒计时。
- **今夜准备检查**：新增 `ReadinessCard`，实时检查麦克风权限、通知可见性、电池优化三项，全部就绪显示"已就绪"，否则引导跳转设置。
- 主操作区提示文案按录音阶段细分：等待中 → 监听中 → 录音中 → 收尾中，描述更精确。
- 录音列表加载增加异常容错，存储挂载或权限异常时显示错误警告而非崩溃。
- 缺少权限时主按钮下方增加"去检查设置"快捷入口。

### 前台通知增强

- 通知标题和正文现在跟随录音阶段动态切换：
  - 待命中："正在等待人声"
  - 录音中："正在录音"
  - 收尾中："正在收尾保存"
  - 错误："录音器错误"
- 启用 `BigTextStyle`，长文本可展开查看详情。

### 录音数据

- 录音文件时长优先从 WAV 头部解析真实采样率/声道/位深，不再固定按 16kHz mono 估算。
- 内部存储回退：`getExternalFilesDir(MUSIC)` 返回 null 时自动切到 `filesDir/music`，防止部分设备/ROM 外部存储行为异常。
- 旧录音保留兼容兜底：头部解析失败时回退到 16kHz 估算。

### 构建与工程

- AGP 8.7.3 → 8.13.2，Gradle 8.9 → 8.13。
- APK 输出重命名为 `VADRecorder-v{version}.apk`，版本号自动跟随 `versionName`。

## 1.0 - 2026-07-01

首个公开发布版本。

### 核心录音

- 基于 `AudioRecord` 的实时音频采集，16kHz / mono / 16-bit PCM，20ms 帧长。
- 音频源自动降级回退：优先 `VOICE_RECOGNITION`，失败时回退 `MIC`；采样率优先 16kHz，其次 44.1kHz。
- 在支持的设备上启用 `NoiseSuppressor`。
- 轻量级 VAD：基于 RMS、ZCR 和自适应底噪阈值的人声检测。
- 三段式录音状态机：`LISTENING` -> `RECORDING` -> `HANGOVER`。
- 支持预滚动、起录确认、收尾缓冲、静音超时和最短片段过滤。
- 录音保存为 `voice_YYYYMMDD_HHmmss.wav`。

### 前台服务

- 录音期间以前台服务常驻运行，`foregroundServiceType="microphone"`。
- 持续显示通知，并提供“停止监听”快捷操作。
- 面向夜间长时间运行场景设计。

### 首页与设置

- 首页显示实时状态、语音检测、倒计时、自动停止安排和录音列表。
- 录音列表按“睡眠夜”分组，并提供播放与删除。
- 设置页支持语言切换、自动停止时长和灵敏度预设。
- 中文本地化覆盖首页、设置页、状态文案和通知文本。
