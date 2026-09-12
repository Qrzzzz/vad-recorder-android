# Changelog

## 2.4 - 2026-09-12

- 片段“更多”菜单新增“分享录音”和“另存为文件”；删除操作收进同一菜单并保留确认。
- 系统分享面板使用 WAV 临时副本和只读权限，接收应用无法修改原录音或读取元数据。
- 系统文件选择器支持选择保存位置和文件名；后台复制完整 WAV，取消、文件消失或写入失败均保留原件，失败时尝试清理未完成的目标文件。
- 导出请求可跨界面重建保留，文件准备期间阻止重复操作；补齐中英文反馈和更多菜单无障碍描述。
- 新增文件完整性、权限、失败清理、旧 WAV 和真机系统交互验证，保持 2.3 回听功能。

## 2.3 - 2026-09-12

- 回听支持暂停和继续播放，暂停后保留当前片段与播放位置。
- 选中片段展开进度条和已播放／总时长，播放或暂停时均可拖动定位；播放结束后可重新播放。
- 播放器异步加载文件，切换片段、删除选中录音和界面销毁时释放资源；离开首页或应用进入后台时暂停回听。
- 补齐中英文播放文案和进度条无障碍描述，兼容已有 WAV 录音。

## 2.2 - 2026-09-12

- 修复 #4：录音创建、写入、同步或提交失败后停止采集，保留中英文错误提示；手动停止与服务销毁不再清空该错误。
- 修复 #5：首次使用正常弹出麦克风权限申请，普通拒绝后可重试，只有权限申请返回不可再提示时才引导系统设置。
- 修复 #6：每段文件名增加 UUID；临时文件以独占方式创建，正式保存拒绝覆盖既有 WAV。
- 修复 #7：保留元数据中已有的正数 `endedAt`，仅在缺失或无效时从 WAV 修改时间推断。
- 修复 #8：在采集入口明确检查麦克风权限，API 27 导航栏属性移入版本限定主题，保留 Android 8.0 / API 26 支持。
- 使用 ImageGen 生成月牙与声波图标，接入 Android 自适应启动图标。
- 增加单任务 Release workflow：运行单元测试和 Lint，构建 APK；推送匹配版本标签时验证签名并发布附件及 SHA-256 校验文件。
- 增加独立包名的真机验收入口，避免首次安装测试影响已有录音。

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
