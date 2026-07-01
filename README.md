# Dream Talk Recorder（记录梦话）

一个面向夜间场景的 Android 前台录音应用。开始监听后，应用会持续读取麦克风，在检测到人声时自动保存 WAV 片段；连续静音 30 秒后自动结束当前片段。手动停止监听时，也会立刻保存当前片段。

## 当前能力

- 人声触发录音，适合放在床边整晚监听
- 前台服务常驻通知，避免系统轻易回收
- 首页可查看实时状态、最后一段录音和昨夜摘要
- 支持应用内播放、删除误触发片段
- 设置页支持灵敏度预设、自动停止时长、语言切换
- 语言支持：跟随系统、English、简体中文

## 构建

推荐直接用 Android Studio 打开项目并同步。

命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

如果命令行提示 `JAVA_HOME` 未设置，请直接使用 Android Studio 自带的 JBR/JDK，或先在当前终端里设置好 JDK 路径。

## 安装

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

如果设备上已经有同包名但签名不同的版本，先卸载：

```powershell
adb uninstall com.qrz.voicetriggerrecorder
```

## 使用

1. 打开 App。
2. 如需切换语言，先到「设置」页选择 `跟随系统 / English / 简体中文`。
3. 点击「开始监听」。
4. 授予麦克风权限；Android 13 及以上可能还会请求通知权限。
5. 保持手机靠近床边。检测到人声后，应用会开始录制当前片段。
6. 停止说话后进入 30 秒收尾倒计时；倒计时内再次检测到人声，会继续写入同一片段。
7. 点击「停止监听」会立刻结束并保存当前片段。

## 录音保存路径

```text
/sdcard/Android/data/com.qrz.voicetriggerrecorder/files/Music/voice-recordings/
```

可通过 adb 查看：

```powershell
adb shell ls /sdcard/Android/data/com.qrz.voicetriggerrecorder/files/Music/voice-recordings/
```

## 技术方案

- 音频采集：`AudioRecord`，16kHz / mono / 16-bit PCM，20ms 帧长
- 人声检测：`SimpleVoiceActivityDetector`
- 文件格式：WAV（PCM）
- UI：Jetpack Compose + Material 3
- 后台执行：`foregroundServiceType="microphone"`
- `minSdk 26` / `targetSdk 35`

## 当前限制

- 仍然使用轻量级 VAD，不保证完全排除电视声、音乐人声或强背景噪声。
- 录音文件仍为 WAV，体积较大。
- 文件保存在应用私有目录，不提供系统级导出或分享流程。
- 某些 ROM 的省电策略仍可能影响灭屏后的前台服务稳定性。

## 近期更新

详见 [CHANGELOG.md](./CHANGELOG.md)。
