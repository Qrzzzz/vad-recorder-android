# 构建与发布

`.github/workflows/release.yml` 只有一个 job，无设备矩阵或夜间任务：

1. PR、`workflow_dispatch` 或 `v*` 标签触发，使用 JDK 17 和 Android SDK。
2. 运行 `testDebugUnitTest lintDebug`；标签必须与 Gradle 的 `versionName` 一致。
3. 构建 Release APK 并上传 Actions artifact。
4. 仅标签触发时校验 APK 签名，创建 GitHub Release，上传 APK 和 `SHA256SUMS.txt`。

PR 不读取签名 secrets，仅生成标有 `unsigned` 的构建 artifact；手动运行没有签名配置时也只构建，不发布 Release。标签发布必须设置以下 GitHub Actions secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 alias |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

必须使用与现有用户版本兼容的签名密钥。密钥不可提交到仓库。配置签名不会自动创建标签或发布；核对验收结果后，由发布者执行该步骤。重复标签发布会由 GitHub 拒绝，避免静默替换已发布附件。

2.2 沿用已发布 2.1 的证书（SHA-256 `b2b0f013dcfa428bb6f4c3563faa204c7890f72abd6062d1cbbb155222d493c9`），以保留覆盖升级兼容性。该历史证书的主体为 Android Debug；2.2 APK 使用 Release 构建类型，未开启 debuggable。签名不代表应用商店分发或更换为新的生产证书。

本地签名使用 `ANDROID_KEYSTORE_PATH` 指向 keystore，并提供上述三个密码/alias 环境变量。无配置时 `assembleRelease` 生成未签名 APK，`assembleDebug` 使用本机 Android 调试签名。

## Windows 验证

推荐使用 Android Studio 自带 JBR：

```powershell
$env:JAVA_HOME = 'D:\Programme Files\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain
```

如中文目录下出现所有测试类均 `ClassNotFoundException`，将当前源码（包括未提交的新增文件）复制到英文目录，并复制本机 `local.properties` 后执行相同命令。不要把这种宿主路径故障当作测试通过。源码副本应与当前改动逐文件核对；Android Studio 和 CI 仍使用正常项目结构。

## 真机测试

`-PdeviceAcceptance` 使用 `com.qrz.voicetriggerrecorder.acceptance` 包名。只在验收命令中传此参数，正式构建不得传入。

```powershell
.\gradlew.bat -PdeviceAcceptance assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/VADRecorder-v2.5.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.qrz.voicetriggerrecorder.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

首次权限测试要求测试包尚未授权麦克风。仅对 `.acceptance` 包重置测试状态，保留正式包的数据。设备需要开启 USB 安装并允许安装提示。权限测试操作实际系统弹窗；存储测试在隔离目录关闭真实文件描述符注入 IOException，不会填满手机磁盘。

2.4 的 `TransferAcceptanceTest` 使用系统文件选择器及真实分享面板，将自动生成的静音 WAV 交给独立测试 APK 的接收 Activity，验证跨 UID 只读授权和文件 SHA-256。该接收 Activity 仅存在于 `androidTest` APK。测试结束清理自己的 UUID 夹具与 Download 目标文件，保留正式包录音。

发布顺序沿用 2.3：完成本地与真机验收并记录结论；将本轮临时构建副本、日志和截图移入回收站；提交并推送功能分支；创建 PR、等待 CI 通过后合并；在合并提交上创建匹配版本的附注标签并推送；等待标签工作流完成；核对 Release 附件、版本、正式包名、签名连续性与 SHA-256；最后清理验证下载和中间分支，回到干净的 `main`。
