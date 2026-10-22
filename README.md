# OpenMinis · System-Level Assistant（feature/system-assist）

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-iOS%20%7C%20Android-lightgrey.svg)](#beta-programme)

本仓库是 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 的 fork，
**默认分支 `feature/system-assist`** 在上游代码之上增加了 **Android 系统级数字助理**能力：
把 Minis 注册为系统默认助理（VoiceInteractionService），可被手势/按键/标准
`ASSIST` 意图唤起，读取当前屏幕上下文并交给 agent 处理。

> 本分支**不合并上游**，定期 rebase 到上游 `main` 保持同步。
> 上游项目：<https://github.com/OpenMinis/OpenMinis>（GPL-3.0，本分支沿用）

---

## 这个分支改动了什么

上游 `main` 之上 10 个提交、19 个文件（+958/−182，删除主要为 README 整体替换）。
全部为新增文件与少量插入式改动，不触碰上游既有功能。

### 新增 `assist/` 包（系统助理核心）

| 文件 | 作用 |
|---|---|
| `AssistService.kt` | `VoiceInteractionService` 入口，系统默认助理的绑定点 |
| `AssistSessionService.kt` | `VoiceInteractionSessionService`，每次唤起创建会话 |
| `AssistSession.kt` | 核心：`onHandleAssist` 采集屏幕上下文、`onHandleScreenshot` 接收系统推送的屏幕位图 → 深链打开 Minis 聊天并注入 |
| `AssistContext.kt` | 把 `AssistStructure`/`AssistContent`（视图树 + 网页内容）展平为可读文本，深度/数量/长度三重防爆 |
| `AssistCapture.kt` | 唤起截图协调器：标准路线落盘框架位图；HyperOS 路线在窗口上屏前用无障碍服务截屏（绑定竞态重试 / 去重 / 超时降级） |
| `MinisRecognitionService.kt` | 框架硬性要求的 `RecognitionService` 声明（否则助理角色校验失败） |
| `AssistTriggerService.kt` | 导出的触发服务，承接 OEM 私有手势配置的 startService |

### 入口与接线

- `res/xml/voice_interaction_service.xml`：`supportsAssist` + `sessionService` + `recognitionService` 完整声明
- `AndroidManifest.xml`：注册上述服务（`BIND_VOICE_INTERACTION` 等），新增
  `MainActivityVoiceAssist` activity-alias 响应 `ACTION_VOICE_ASSIST` / `ACTION_ASSIST`
- 深链 `minis://assist`：`DeepLinkHandler` 路由 + `DeepLinkCoordinator.pendingAssist` 暂存
  （可携带截图路径），`ChatScreen` 首次 compose 时消费 → **屏幕上下文成为发给 agent 的第一条消息**
- 唤起截图：新会话首条消息自动附带"唤起时刻的屏幕"截图（长边缩至 1600 的 JPEG）。
  设置 → 权限 → 系统权限 中可用开关「助理唤起时附带当前屏幕」关闭（默认开）。
  顺带修复：系统权限页路由此前无入口（孤儿页），设置主页已补上入口行

### 数据流

```
手势 / 按键 / ACTION_ASSIST
   │
   ├─ 标准路线（角色解析的 ROM）
   │    系统 → AssistSession.onHandleAssist(结构/网页) + onHandleScreenshot(位图)
   │
   ├─ HyperOS 路线（hook 改道 / OEM startService）
   │    AssistCapture：入口用无障碍服务抢在窗口上屏前截一帧
   │
   ▼
① DeepLinkCoordinator.pendingAssist（文本 + 截图路径）
② 打开新 chat 会话 → 截图作为附件 + 上下文文本作为首条消息
   ▼
agent 循环正常处理（多模态读图 / shell / 技能 / 无障碍读屏…）
```

---

## 拉取并编译带 VoiceInteractionService 的 APK

### 前置

- arm64 Android 设备（minSdk 26，已在 Android 15 真机验证）
- JDK 17、Android SDK（compileSdk 36）、NDK r27+（BUILDING.md 建议 r28）、CMake 3.22.1

### 步骤

```bash
git clone https://github.com/xiki45/OpenMinis.git
cd OpenMinis          # 默认分支即 feature/system-assist

# ⚠️ 两个准备步骤必须执行，跳过会得到缺失 Alpine 沙盒的 APK
#    （应用内表现为 installation failed: alpine-minirootfs.tar）
git submodule update --init deps/proot
export ANDROID_NDK_HOME=<你的NDK路径>
./deps/build_proot.sh                 # → jniLibs/arm64-v8a/libproot.so + loaders
./scripts/prepare_android_sandbox.sh  # → assets/alpine-minirootfs.tar.gz（联网下载 ~3MB）

cd src/android
./gradlew :app:assembleDebug
# 产物：src/android/app/build/outputs/apk/debug/app-debug.apk（~49MB，debug 签名）
```

产物自检：

```bash
unzip -l app-debug.apk | grep -E "alpine-minirootfs|libproot"
# 应看到 assets/alpine-minirootfs.tar 与 lib/arm64-v8a/libproot.so、libproot-loader*.so
```

其余构建细节（依赖版本、NDK 说明）见 [`BUILDING.md`](BUILDING.md)。

---

## 使用

1. 安装 APK（`adb install -r app-debug.apk` 或手动安装）
2. **设置 → 应用 → 默认应用 → 数字助理** 选择 Minis（系统要求手动确认）
3. 标准通道（`ACTION_ASSIST` / `ACTION_VOICE_ASSIST`、Pixel 类原生手势）即可唤起
4. 可选：在 Minis **无障碍服务**（设置 → 无障碍）中启用 *Minis Accessibility Service*，
   HyperOS 路线的唤起截图依赖它

**唤起截图**：唤起时自动把"你正在看的屏幕"截图附进新对话（需 agent 模型支持视觉）。
在 Minis **设置 → 权限 → 系统权限** 中可用「助理唤起时附带当前屏幕」开关控制；
安全界面（银行类应用等）无法截取，会自动静默降级为无图会话。

**HyperOS（小米/红米）注意**：
- 手势派发被硬编码到小爱私有通道，改默认助理无效。
  需配套 LSPosed 模块 **<https://github.com/xiki45/minis-assist-hook>**（v2.1+）：
  在系统手势唤起小爱时重定向到 Minis（双击小白条 / 长按电源键直接全屏打开）。
- **触发源差异化取屏**（hook v2.1 起）：双击小白条 = 屏幕即现场 → 自动附截图；
  长按电源键 = 快速提问 → 不附截图。hook 经 `com.openminis.hook.attach_screen`
  extra 传递判定，缺失时默认附截图（兼容旧模块 / 标准路线）。
- **每次更新/重装/强行停止（force-stop）本应用后，HyperOS（及 Android 12+ 通用行为）
  会解除无障碍服务绑定**（设置项可能还在但实际未绑定，
  表现为唤起不再附带截图）。修复：系统无障碍设置里把 Minis 的开关关一次再打开
  （应用内 系统权限 页会显示状态与修复指引）。

---

## 跟上游同步

```bash
git fetch upstream      # upstream = https://github.com/OpenMinis/OpenMinis
git rebase upstream/main
git push --force-with-lease
```

改动集中在 19 个文件、以新增为主，rebase 冲突概率低。

---

## 系统提示词可编辑（Settings → System Prompt）

在上游基础上，本分支把 agent **系统提示词的静态 prose 部分**开放为可编辑，方便按需调整 agent 的运行时行为指引。

**入口**：设置 → Agent Runtime → **System Prompt**（Soul 之后、Memory 之前）。
也可经 `minis-config get/set system_prompt.prose` 读写，写入值等于内置默认文本（或空串）即清除自定义、回落默认。

### 可编辑 vs 由 app 管理

| 部分 | 是否可编辑 | 来源 |
|---|---|---|
| **静态 prose**（运行时指引、`/var/minis` 目录、minis:// URL scheme、文件创建规范、tool-call style、语气、Android framework commands、环境变量规则、定时任务段落） | ✅ 可编辑 | `SystemPromptProseStore`（`SYSTEM_PROMPT.md`） |
| 工具 bullet 列表（`shell_execute` / `file_*` / `browser_use` / `memory_*` 等） | ❌ app 管理 | 按工具/记忆开关硬编码派生，每次请求强制注入 |
| MCP 服务器元数据（Top-20） | ❌ app 管理 | `MCPRepository.mcpPromptFragment()` |
| Skill 元数据（Top-20 `<available_skills>`） | ❌ app 管理 | `SkillRepository.skillPromptFragment()` |
| SOUL 身份 / Personality | ❌ 单独编辑 | Settings → Soul（`SOUL.md`） |
| Runtime context（日期/时区/语言） | ❌ 每次生成 | 请求时拼装 |

工具 / MCP / Skill 由 app 自动生成而非用户编辑，是为了避免 **prompt/schema drift**：工具 bullet 必须与工具 schema 同步开关，否则模型会被告知有某个工具但 schema 里没有，产生幻觉调用；MCP / Skill 元数据每次请求从配置动态生成，做成可编辑会出现两份真相。

### 约束

- **语言感知字数上限**（同 Soul personality）：中文 ≤ 1600 字符 **或** 英文 ≤ 1000 词，按主要语言择一判定；超限拒绝保存。
- **注入清洗**：拒绝含 "ignore/disregard/forget … previous instructions" 等提示注入模式的文本。
- **Reset to Default** 删除 `SYSTEM_PROMPT.md`，内置默认 prose 立即恢复。
- 工具 bullet 列表**始终注入**——即使编辑后的 prose 删掉了 `Available tools:` 标题，bullets 也会被补到开头，保证 schema 里每个工具都被广告。

### 涉及文件

| 文件 | 作用 |
|---|---|
| `src/android/.../agent/SystemPromptProseStore.kt` | 持久化 store（默认文本 + 读写/重置/上限/清洗） |
| `src/android/.../ui/settings/SystemPromptSettingsScreen.kt` | 设置页编辑器（计数、超限提示、保存、重置、未保存守卫） |
| `src/android/.../config/ConfigBuiltins.kt` | `system_prompt.prose`（`minis-config` 字段） |
| `src/android/.../ui/chat/ChatViewModel.kt` | `buildSystemPrompt()` 从 store 取 prose 并拼接工具 bullet |
| `src/android/.../ui/navigation/AppNavigation.kt` / `ui/settings/SettingsScreen.kt` | 路由与设置入口 |

> iOS 侧暂未实现，本功能当前仅 Android。
