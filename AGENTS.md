# 项目约定（AGENTS.md）

## 编译方式（强制）
- **禁止本地 gradle 编译**。编译必须通过 GitHub Actions 工作流（`.github/workflows/build.yml`）：提交并推送 `main` 分支触发 CI，用 `gh run watch` 查看结果，成功后下载 APK 到 `/sdcard/Download/edge-tts-engine.apk`。
- 本地只有 `/home/wayne/Android/Sdk` 和 JDK21，但用户明确要求只用工作流编译，不要跑 `./gradlew` 本地编译。

## 设备与兼容性
- 用户设备是 Android 7（API 24/25）。任何 API 26+ 的 API 会运行时崩溃（已踩坑：`ByteArrayOutputStream.toString(Charset)`）。写代码必须兼容 API 24。

## 签名
- 用户选择方案 B：debug keystore 已通过 `actions/cache` 缓存（`~/.android/debug.keystore`，key `debug-keystore-v1`），保证每次 APK 签名一致。不要提交 keystore 文件。

## 日志
- 日志统一写单个文件 `/sdcard/Download/edge-tts-logs/edge-tts.log`（Android 10+ 走 MediaStore），每条带完整时间戳 `yyyy-MM-dd HH:mm:ss.SSS`。
- 启用方式：连点「使用说明」卡片 7 次，状态持久化。

## 关键业务规则
- 系统 TTS 语言判定：`onIsLanguageAvailable` 必须按 `getExpectedLanguageAvailableStatus` 逻辑精确返回（variant 空+country 空→LANG_AVAILABLE；variant 空→LANG_COUNTRY_AVAILABLE；否则→VAR_AVAILABLE），不能无条件返回 VAR_AVAILABLE，否则系统显示"不支持"。
- `onIsValidVoiceName`/`onLoadVoice` 必须按音色名（shortName）匹配音色列表，不能用默认 BCP-47 解析（音色名如 `zh-CN-XiaoxiaoNeural` 不是合法 language tag）。
- 国家码比较：系统传 ISO-3（如 `CHN`），音色 locale 是 ISO-2（如 `CN`），`defaultVoiceFor` 需先转换再匹配，且 zh 应优先简体（zh-CN）。