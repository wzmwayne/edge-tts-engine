# 项目约定（AGENTS.md）

## 编译方式（强制）
- **禁止本地 gradle 编译**。编译必须通过 GitHub Actions 工作流（`.github/workflows/build.yml`）：提交并推送 `main` 分支触发 CI，用 `gh run watch` 查看结果，成功后下载 APK 到 `/sdcard/Download/edge-tts-engine.apk`。
- 本地只有 `/home/wayne/Android/Sdk` 和 JDK21，但用户明确要求只用工作流编译，不要跑 `./gradlew` 本地编译。

## 设备与兼容性
- 用户设备是 Android 7（API 24/25）。任何 API 26+ 的 API 会运行时崩溃（已踩坑：`ByteArrayOutputStream.toString(Charset)`）。写代码必须兼容 API 24。

## 签名
- release 使用仓库内固定 `android/app/debug.keystore`（通用 Android debug key：alias `androiddebugkey`，密码 `android`，指纹 `D4:E6:AB:1F:92:AE:9C:DA:8C:DA:3A:D5:81:12:AE:BC:4B:7C:59:91`），由 `android/app/build.gradle.kts` 的 `signingConfigs.release` 显式引用，每次构建签名一致、可直接覆盖安装。不要改签名配置，也不要新增其它 keystore 文件。

## 日志
- 日志统一写单个文件 `/sdcard/Download/edge-tts-logs/edge-tts.log.txt`（Android 10+ 走 MediaStore），始终追加到同一文件，每条带完整时间戳 `yyyy-MM-dd HH:mm:ss.SSS`。注意：文件名必须与 MIME 匹配（`.log.txt` + text/plain），否则 MediaProvider 会改名并产生 `(2)` 后缀副本。
- 启用方式：右上角设置页「调试日志」开关（不再是连点使用说明卡片 7 次）。`EdgeLog.enable/disable/syncFromSettings`。

## 设置页
- 右上角设置页（`lib/main.dart` 的 `SettingsPage`）提供：流式合成开关（beta，仅开关+存储，实际合成仍走全量缓冲）、默认音色语言过滤（all/system/具体语言）、调试日志开关。
- 存储键在 `VoiceStore`：`streaming`、`lang_filter`、`debug_log`、`usage_hidden`；channel 方法 `getSettings`/`setSettings`。
- 使用说明卡片可关闭，`usage_hidden=true` 后首页不再展示。

## 关键业务规则
- 系统 TTS 语言判定：`onIsLanguageAvailable` 必须按 `getExpectedLanguageAvailableStatus` 逻辑精确返回（variant 空+country 空→LANG_AVAILABLE；variant 空→LANG_COUNTRY_AVAILABLE；否则→VAR_AVAILABLE），不能无条件返回 VAR_AVAILABLE，否则系统显示"不支持"。
- `onIsValidVoiceName`/`onLoadVoice` 必须按音色名（shortName）匹配音色列表，不能用默认 BCP-47 解析（音色名如 `zh-CN-XiaoxiaoNeural` 不是合法 language tag）。
- 国家码比较：系统传 ISO-3（如 `CHN`），音色 locale 是 ISO-2（如 `CN`），`defaultVoiceFor` 需先转换再匹配，且 zh 应优先简体（zh-CN）。