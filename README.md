# DSH-Let's go

在 Android 手机上原生运行的 DeepSeek——开源 AI 助手 [DeepSeek](https://github.com/deepseek-ai/deepseek-harness) 的移动版。助手引擎完整运行在你的手机本地：对话、文件、任务都在设备上处理。

> DSH-Let's go runs the open-source DeepSeek Harness (MIT) natively on your Android phone, fully on-device.

## 特性

- **完全本地运行**：引擎（Node.js + DeepSeek 运行时）内嵌于 APK，会话与文件都保存在你手机的应用私有目录中
- **命令执行**：内置 bash 5.3 与 coreutils、git 等 140+ 命令行工具，AI 助手可直接执行命令、处理文件、运行构建
- **官方界面**：与桌面版一致的完整 Web UI，本地服务 + 沉浸式全屏显示
- **后台常驻**：前台服务保活，切出应用再回来不重载；内置电池优化白名单与主流国产 ROM（小米/OPPO/vivo/华为/三星）自启动设置的直达入口

## 系统要求

- Android 10 或更高
- arm64-v8a 或 x86_64 设备，建议内存 4GB 以上

## 安装

1. 从 [Releases](../../releases) 下载 `DSH Let's go.apk` 并安装（需允许「安装未知应用」）
2. 首次启动约 10 秒：应用正在把运行时解压到私有目录，之后启动都是秒开
3. 首次使用会引导：通知权限 → 电池优化白名单 → 登录或填入 API Key

### 从源码构建

运行时与引擎二进制已随仓库附带，克隆后无需额外准备：

```bash
cd android-app
gradle assembleDebug --no-daemon   # 需要 JDK 21 与 Android SDK (platform 35)
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

## 使用提示

1. **首次启动**：应用会依次请求通知权限和电池优化白名单，建议全部允许——这是后台稳定运行的前提
2. **配置模型**：登录你的 DeepSeek 账号，或在设置中填入 API Key；不配置就无法开始对话
3. **选择工作目录**：开始对话前先选择或创建一个工作目录，之后的项目文件都保存在这里
4. **后台使用**：切出应用后引擎会继续在后台运行；如果被系统清理，重新打开应用即可自动恢复
5. **数据安全**：会话记录、项目文件、配置全部保存在手机本地；卸载应用或清除数据会一并删除

## 已知限制（当前版本）

- 部分桌面功能暂未提供：Office 文档处理、语音输入、交互终端面板
- 插件安装正在适配中
- debug 签名包，正式签名版后续提供

## 许可证

| 内容 | 许可证 |
|---|---|
| 本仓库的移植壳代码 | [Apache License 2.0](LICENSE) |
| 内含的 DeepSeek 运行时 | **MIT License，Copyright (c) 2026 DeepSeek**（全文见 [LICENSE-DeepSeek-Harness.txt](LICENSE-DeepSeek-Harness.txt)，APK 分发时随包附带） |
| Node.js 24.22.3 与依赖库 | MIT / ICU / Apache-2.0 / NCSA / zlib / 公有领域（声明见仓库根目录各文件） |

DeepSeek Harness 处于开发者预览期，功能与界面可能随上游更新变化。
