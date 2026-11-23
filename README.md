# DSH-Let's go

在 Android 手机上原生运行的 DeepSeek——开源 AI 助手 [DeepSeek](https://github.com/deepseek-ai/deepseek-harness) 的移动版。助手引擎完整运行在你的手机本地：对话、文件、任务都在设备上处理。

> DSH-Let's go runs the open-source DeepSeek Harness (MIT) natively on your Android phone, fully on-device.

## 特性

- **完全本地运行**：引擎（Node.js + DeepSeek 运行时）内嵌于 APK，会话与文件都保存在你手机的应用私有目录中
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

- **保持后台**：建议在系统设置中将电池策略设为「不限制」并允许自启动（等待页的「后台保活设置」按钮会带你直达对应开关）
- 切出应用再回来不重载；即使进程被系统清理，重新打开也会自动恢复引擎与界面
- 会话历史保存在本地，升级或重装后清除应用数据会一并清除

## 已知限制（当前版本）

- 命令执行类工具暂不可用（bash 工具链正在适配中）
- 部分桌面功能暂未提供：Office 文档处理、语音输入、交互终端面板
- debug 签名包，正式签名版后续提供

## 许可证

| 内容 | 许可证 |
|---|---|
| 本仓库的移植壳代码 | [Apache License 2.0](LICENSE) |
| 内含的 DeepSeek 运行时 | **MIT License，Copyright (c) 2026 DeepSeek**（全文见 [LICENSE-DeepSeek-Harness.txt](LICENSE-DeepSeek-Harness.txt)，APK 分发时随包附带） |
| Node.js 24.22.3 与依赖库 | MIT / ICU / Apache-2.0 / NCSA / zlib / 公有领域（声明见仓库根目录各文件） |

DeepSeek Harness 处于开发者预览期，功能与界面可能随上游更新变化。
