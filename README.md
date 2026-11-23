# DSH-Let's go

**DeepSeek Harness（0.2.2-rc.5）的原生 Android 移植**：APK 内嵌 Android 原生 Node.js 24.22.3（Termux bionic 构建），执行完整的 DSH 插件体系，WebView 承载官方 Web UI，全程在设备本地运行。不依赖 PC、不依赖 Termux、不依赖云容器。

> EN: A native Android port of DeepSeek Harness (MIT). The APK embeds a bionic-built Node.js 24.22.3 runtime, boots `dsh --profile android` fully on-device, and serves the official Web UI in a WebView.

## 声明：基于官方修改版本

本项目是基于 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（MIT License，**Copyright (c) 2026 DeepSeek**）的**修改版本**。原始许可证全文见 [LICENSE-DeepSeek-Harness.txt](LICENSE-DeepSeek-Harness.txt)（一字未改）。上游基线 `0.2.2-rc.5`。

本仓库的适配（修改）内容：

1. **Android 原生运行时集成**：Termux 构建的 Node.js 24.22.3 嵌入 APK（jniLibs + ProcessBuilder 启动，无 NDK），并修补 Android 平台的 TLS 信任链、flock、SELinux 硬链接、时区等运行时差异
2. **Android 应用壳**：Java 前台服务 + 沉浸式 WebView 承载官方 Web UI，token 自动认证与运行时解压
3. **移动端 profile 裁剪**：基于 DSH 自身的 patch 机制禁用桌面专属组件，新增 `--profile android`
4. **凭证模型**：零内置凭证，用户在应用内 Sign in（账号授权）或 Add API Key（自带密钥），凭据仅存于设备应用私有目录

## 构建与运行

运行时载荷已随仓库附带（`android-app/app/src/main/assets/` 与 `jniLibs/` 下的 zip 与 .so），克隆后直接编译：

```bash
cd android-app
gradle assembleDebug --no-daemon   # JAVA_HOME 指向 JDK 21；Android SDK platform 35
# 产物 app/build/outputs/apk/debug/app-debug.apk（双架构 universal）
```

- 载荷均为构建产物：DSH 0.2.2-rc.5 运行时闭包（含 Android 修补）、Node 24.22.3 双架构二进制（Termux 构建）、依赖库、android profile
- APK 首次启动约 10 秒解压属正常；无内置凭证，启动后在应用内 Sign in 或 Add API Key

## 已知限制（移动端 profile 已裁剪）

- bash/子进程类工具链暂不可用（Android 无 bash）
- 交互终端面板、插件安装（设备端无 pnpm）暂不可用
- workspace 仅应用私有目录（SAF 真实存储桥未实现）
- Office 处理与语音输入已禁用
- debug 签名；前台服务在 Android 15 有每日运行配额

## 许可证

| 文件 | 覆盖 |
|---|---|
| [LICENSE](LICENSE) | 本仓库的移植壳代码（Apache License 2.0） |
| [LICENSE-DeepSeek-Harness.txt](LICENSE-DeepSeek-Harness.txt) | DeepSeek Harness 运行时（MIT，Copyright (c) 2026 DeepSeek，APK 分发时原文另随 `@deepseek-ai/*` 各包附带） |
| `nodejs-lts-copyright.txt`、`libc++-LICENSE-NCSA.txt` 等 7 份（仓库根目录） | Node.js 24.22.3 及捆绑依赖库（MIT/ICU/Apache-2.0/NCSA/zlib/公有领域） |

上游 DSH 处于开发者预览期，API 可能存在破坏性变更；本移植基线为 `0.2.2-rc.5`。
