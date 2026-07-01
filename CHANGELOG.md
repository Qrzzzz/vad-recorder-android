# Changelog

## 2.0 - 2026-07-02

- 稳定性升级：录音先写入 `.wav.part`，WAV header finalize 成功后再落为正式 `.wav`。
- 新增明确关闭原因：`EndSilence`、`ManualStop`、`ServiceStop`、`ReadError`、`Destroy`。
- 短促误触发会被过滤，不再保存为正式 WAV；手动停止和读取错误会尽量保存有效片段。
- 抽象 `VadEngine` / `VadResult` / `VadEngineFactory`，现有规则 VAD 迁移为 `RuleBasedVadEngine`，并加入隐藏环境校准。
- 重构前台服务启动、停止、读取失败、销毁收尾路径，失败时不再让 UI 继续显示“正在监听”。
- 新增 WAV JSON sidecar 元数据，并兼容旧 WAV 文件。
- 补充核心状态机、WAV finalize、录音库元数据的 JVM/Robolectric 单元测试。
- 本版本不包含导出、分享、压缩、云同步，录音格式仍为 WAV。

## 1.0 - 2026-07-01

- 首次整理为 `1.0` 发布版本。
- 设置页新增语言切换，支持跟随系统、English、简体中文。
- 新增完整简体中文本地化，并将首页、设置页、状态文案、前台通知统一切到资源文件。
- 调整录音状态模型，修复切换语言后状态文案仍停留旧语言的问题。
- 保留并整合自动停止、灵敏度预设、录音播放、误触发删除等已有能力。
- 更新 README、补充 `.gitignore` 和发布所需基础仓库文件。
