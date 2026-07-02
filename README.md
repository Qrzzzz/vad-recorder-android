# Dream Talk Recorder（记录梦话）

一个面向夜间场景的 Android 前台录音应用。开始监听后，应用会持续读取麦克风，在检测到人声时保存 WAV 片段；连续静音 30 秒后自动结束当前片段。手动停止监听时，也会立即收尾当前有效片段。

## 当前能力

- 人声触发录音，适合放在床边整晚监听。
- 前台服务常驻通知，减少系统回收导致的中断。
- 首页可查看实时状态、最后一段录音和昨夜摘要。
- 支持应用内播放、删除误触发片段。
- 设置页支持灵敏度预设、自动停止时长、语言切换。
- 录音先写入 `.wav.part`，完成 WAV header 后再保存为正式 `.wav`。
- 每段新录音会生成 JSON sidecar 元数据；旧 WAV 仍可通过 header 兼容读取。

## 构建

推荐直接用 Android Studio 打开项目并同步。

命令行构建：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

如果命令行提示 `JAVA_HOME` 未设置，请直接使用 Android Studio 自带的 JBR/JDK，或先在当前终端设置好 JDK 路径。

## 安装

```powershell
adb install -r .\app\build\outputs\apk\debug\VADRecorder-v2.0.apk
```

如果设备上已有同包名但签名不同的版本，先卸载：

```powershell
adb uninstall com.qrz.voicetriggerrecorder
```

## 使用

1. 打开 App。
2. 如需切换语言，先到“设置”页选择 `跟随系统 / English / 简体中文`。
3. 点击“开始监听”。
4. 授予麦克风权限；Android 13 及以上可能还会请求通知权限。
5. 保持手机靠近床边。检测到人声后，应用会开始录制当前片段。
6. 停止说话后进入 30 秒收尾倒计时；倒计时内再次检测到人声，会继续写入同一片段。
7. 点击“停止监听”会立即结束并保存当前有效片段。

## 录音保存路径

```text
/sdcard/Android/data/com.qrz.voicetriggerrecorder/files/Music/voice-recordings/
```

可通过 adb 查看：

```powershell
adb shell ls /sdcard/Android/data/com.qrz.voicetriggerrecorder/files/Music/voice-recordings/
```

## 技术方案

- 音频采集：`AudioRecord`，优先 16 kHz / mono / 16-bit PCM，20 ms 帧长。
- 人声检测：`VadEngine` 抽象，当前实现为 `RuleBasedVadEngine`，保留 `SimpleVoiceActivityDetector` 兼容包装。
- 环境校准：监听开始后隐藏校准环境噪声底，按灵敏度预设映射到底层阈值。
- 状态机：`RecordingStateMachine` 使用明确关闭原因收尾，并过滤短促误触发。
- 文件安全：先写 `.wav.part`，finalize 成功后再移动为 `.wav`，启动时可清理陈旧 partial 文件。
- 元数据：每个新 WAV 搭配 JSON sidecar，记录时间、时长、大小、采样率、关闭原因、VAD 引擎、finalize 状态等。
- UI：Jetpack Compose + Material 3。
- 后台执行：`foregroundServiceType="microphone"`。
- `minSdk 26` / `targetSdk 35`。

## 当前限制

- 当前仍使用轻量规则 VAD，不保证完全排除电视声、音乐人声或强背景噪声。
- 录音文件仍为 WAV，体积较大。
- 文件保存在应用私有目录，本轮不提供系统级导出、分享、外部文件选择器或云同步。
- 本轮不实现 M4A/AAC/FLAC 压缩。
- 某些 ROM 的省电策略仍可能影响熄屏后的前台服务稳定性。

## 手动测试清单

- 首次启动后授予麦克风权限，再点击开始监听。
- 短促噪声触发后确认不会留下正式 WAV。
- 正常说话后停止说话，等待 30 秒确认保存。
- 录制中点击停止监听，确认当前有效片段可播放且没有 `.wav.part` 残留。
- 删除正在播放或未播放的录音，确认 WAV 和 JSON 元数据同时删除。
- 修改灵敏度和自动停止设置，确认 UI 视觉结构不变。
- 模拟麦克风占用或初始化失败，确认首页不显示“仍在监听”。

## 近期更新

详见 [CHANGELOG.md](./CHANGELOG.md)。
