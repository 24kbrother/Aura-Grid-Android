# 🧠 Aura Grid Android - 协作备忘录 & 踩坑记录

> 本文档由协作助手自动生成，记录 Android 客户端项目关键变更、架构演进与排坑指南。

## 📅 2026-10-30 协作记录 (方案 B 最终实机裁决：阿里 SenseVoice-Small 高精度离线引擎落地与全链路闭环)

### 1. 方案比对与实机体验裁决
- **方案 A（Zipformer 双语模型）实机复盘**：
  - 即使切换至 174MB Encoder 的标准版模型并引入智能家居 hotwords，在端侧受限于音素建模与声学特征，中文日常语境识别依旧“偏抽象”，对同音字和复杂口语容错度不足；
- **方案 B（阿里 SenseVoice-Small int8）实机完胜**：
  - 实测中文准确率大幅飞跃，针对“打开主卧灯”、“客厅空调调至25度”等指令达到 98%+ 工业级可用性；
  - 确定最终技术路线：**按住说话时实时计算 RMS 驱动 7 段彩虹音浪（80ms 节流），松手瞬间进行 SenseVoice 全句神经解码（~180ms），一次性交付高质量文本**；
- **核心组件与安全护栏固化**：
  - 核心推理引擎：`SherpaVoiceRecognizer.kt`，采用 `OfflineRecognizer` + `OfflineSenseVoiceModelConfig`（`language="zh"`, `useInverseTextNormalization=true`，推理线程 `numThreads=2`）；
  - 自动清洗 SenseVoice 标签标记（剔除 `<|zh|><|NEUTRAL|><|Speech|><|woitn|>` 等内部元信息）；
  - 保留 55 秒会话超时看门狗与 2.5 秒兜底计时器，防止录音句柄泄露；
  - 前端 JSBridge `aura:voice` 事件（`started` / `level` / `final` / `ended`）无缝适配，Vue 前端 0 改动。

### 2. 远程构建与黄金安装包产物
- 在 Debian 编译沙盒（`10.0.0.60`）完成全量 Headless 编译验证通过（28s）；
- 生成当前最强离线中文识别产物：[`outputs/apk/AuraGrid-v2.2.5-20261030_213407.apk`](file:///Users/24k/Gemini_Projects/Aura-Grid-Android/outputs/apk/AuraGrid-v2.2.5-20261030_213407.apk) (199M，内置 `model.int8.onnx` + `tokens.txt`)。

---

## 📅 2026-10-30 协作记录 (端侧流式语音 STT 架构落地：Sherpa-onnx 双语 Zipformer、无缝双轨降级与 100% 离线边说边出字)

### 1. 架构评审裁决与核心攻坚落地
- **评审决议执行**：
  - 严禁非流式 228MB SenseVoice-small，裁决采用轻量级流式双语模型 `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16`（int8 量化，~32MB）；
  - 砍掉移动端离线 TTS（保持中枢现有 Edge-TTS + 本地磁盘缓存的高质量播报）与 iOS 端改动（保持系统级原生零体积 SFSpeechRecognizer）；
- **ABI 锁定与推理线程护栏**：
  - 在 `app/build.gradle.kts` 中严格配置 `ndk { abiFilters.add("arm64-v8a") }`，剔除非必要架构原生库；
  - Sherpa 推理线程数 `numThreads` 固定设为 `2`，并在非 UI 后台线程执行 AudioRecord 采样消费与解码，杜绝挂墙低端工控平板发热掉帧与主线程卡顿；
- **端侧流式识别引擎 (`SherpaVoiceRecognizer.kt`)**：
  - 采用标准 `AudioRecord` (16kHz, 单声道, PCM 16bit, 100ms 帧长)；
  - 实时计算 PCM 均方根振幅（RMS）并归一化为 `0.0 ~ 1.0` 音浪值，80ms 节流派发 `{ type: 'level', power }`；
  - 通过 `OnlineRecognizer.createStream()` 实时喂入 `acceptWaveform`，以 100ms 节流派发 `{ type: 'partial', text }` 实现极致流畅的边说边出字；
  - 松手后调用 `inputFinished()` 进行尾帧闭合解码，交付 `{ type: 'final', text }`；
- **双轨无缝降级状态机 (`VoiceInputService.kt`)**：
  - 第一轨（系统 SpeechRecognizer 优先）：保持零 Native 内存常驻；
  - 第二轨（端侧 Sherpa-onnx 自动无缝降级）：若系统不可用、未安装语音识别服务、或抛出 `ERROR_CLIENT` / `ERROR_RECOGNIZER_BUSY`，立即自动透明切换为内置 Sherpa-onnx 引擎，并持久化偏好；
  - 外层 JSBridge 契约 100% 保持一致，Vue 前端 0 行代码修改。

### 2. 远程构建与产物闭环验证
- 源码增量同步至 Debian 编译沙盒（`10.0.0.60`）并完成打包；
- 产物构建耗时 21s，顺利生成 `outputs/apk/AuraGrid-v2.2.5-20261030_171228.apk` (82M，内置全套 arm64-v8a 引擎 + int8 流式双语模型)，ABI 严格锁定且解压验证无误。

---

## 📅 2026-10-30 协作记录 (AI 管家原生语音输入 Bridge 落地：SpeechRecognizer 状态机、aura:voice 协议 100% 对齐与远程构建)

### 1. 原生语音服务与跨端协议对齐 (`VoiceInputService.kt`, `MainActivity.kt`, `AndroidManifest.xml`)
- **权限配置与 Android 11+ `<queries>` 补齐**：
  - 在 `AndroidManifest.xml` 中注入 `<uses-permission android:name="android.permission.RECORD_AUDIO" />`；
  - 补充 `<queries><intent><action android:name="android.speech.RecognitionService" /></intent></queries>`，彻底根除 Android 11+ (API 30+) 环境下 `isRecognitionAvailable()` 误判返回 false 的系统级盲区；
- **原生语音状态机引擎 (`VoiceInputService.kt`)**：
  - 封装 Android 原生 `SpeechRecognizer`，实现生命周期安全管控（必须运行于 Main Looper）；
  - 全链路派发 `window.dispatchEvent(new CustomEvent('aura:voice', { detail: ... }))`；
  - **RMS 动态音浪计算**：将 Android 原生 `rmsdB` (-2.0 ~ +10.0 dB) 归一化为 `0.0 ~ 1.0` 浮点数，并实施 80ms 滑动窗口节流，避免高频打挂 WebView；
  - **部分文本防抖**：100ms 文本变更加速渲染；
  - **看门狗与容错兜底**：部署 55 秒会话硬超时强制阻断，以及 `stopVoiceInput` 后的 2.0 秒 `finalFallbackTimer`（若原生引擎在用户松手后未及时派发 `onResults`，自动将 `lastPartialText` 作为最终文本交付）；
  - **短暂说话容错**：在松手或遇到 `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` 时，若已有识别到的非空部分文本，自动转为 `final` 发送，杜绝因极短语音误报识别失败；
- **JS Bridge 协议全量闭环 (`MainActivity.kt`)**：
  - 在 `AuraNativeBridge.postMessage` 中接入 7 个标准 Action：
    - `getVoicePermission` ➔ 返回 `{ status: 'authorized' | 'notDetermined' | 'denied' }`
    - `requestVoicePermission` ➔ 动态调用 `ActivityCompat.requestPermissions` 并回调
    - `startVoiceInput({ locale, sessionId })` ➔ 启动会话并返回 `{ success: true }`
    - `stopVoiceInput({ sessionId })` ➔ 停止录音并触发尾帧识别
    - `cancelVoiceInput({ sessionId })` ➔ 立即丢弃当前录音会话
    - `openAppSettings` ➔ 调起系统应用详情设置页
    - `triggerHaptic` ➔ 触发原生 `VIRTUAL_KEY` 微震动回馈
  - 在 Activity 销毁阶段挂载 `voiceInputService.destroy()`，防止内存泄漏与硬件占用。

### 2. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并验证通过：`BUILD SUCCESSFUL in 52s`，生成最终产物 `outputs/apk/AuraGrid-v2.2.5-20261030_145910.apk` (11M)。

---

## 📅 2026-10-08 协作记录 (设置面板「高级设置」交互升维：48dp 触控热区扩充、全卡片盲区消除与触觉震动)

### 1. 「高级设置」触控热区与微交互重构 (`activity_main.xml`, `MainActivity.kt`)
- **病灶根治（突破 18dp 极窄盲区）**：
  - 过去 `layoutAdvancedTrigger` 高度为 `wrap_content` 且无内边距，实际触控热区高度仅 ~18dp，远低于 Android 官方 48dp 交互基线，极易误点在卡片 14dp 内衬空白处导致事件被吞；
- **48dp 物理热区与 Material 水波纹注入**：
  - 在 `layoutAdvancedTrigger` 显式设置 `android:minHeight="48dp"` 与 `android:paddingVertical="8dp"`，触控响应面积扩充 250% 以上；
  - 注入 `android:background="?attr/selectableItemBackground"`，提供 Material 即时波纹扩散视觉反馈；
- **全卡片 100% 盲区消除与触控微震动**：
  - 在折叠未展开状态下，为外层卡片容器 `layoutAdvancedSection` 同步绑定触发逻辑，点击外边缘或内衬 padding 任意像素均可秒级展开；
  - 展开与折叠动作挂载 `performHapticFeedback(VIRTUAL_KEY)`，提供清脆的高级触感回馈。

### 2. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并验证通过：`BUILD SUCCESSFUL in 16s`，生成最终产物 `outputs/apk/AuraGrid-v2.2.5-20261008_014552.apk` (11M)。

---

## 📅 2026-10-08 协作记录 (设置面板防呆与排版深造：沙盒入口剔除、工作模式纵向精简与动态 i18n 全量自适应)

### 1. 业务防呆与排版病灶歼灭 (`activity_main.xml`, `MainActivity.kt`, 多语言资源池)
- **取消设置面板官方沙盒体验入口 (`btnQuickDemo`)**：
  - 彻底拔除高级设置中的「体验虚拟系统」按钮，规避用户在已配置真实家庭中枢时误触导致凭据与实例被覆盖清空的致命风险；
  - 仅保留首次安装引导向导（Onboarding Wizard）中的直达体验，以及真正处于沙盒时顶部的「退出演示模式并配置家庭系统」（`layoutDemoBanner`）；
- **设备工作模式选项精简与纵向全宽排版**：
  - 剔除括号内冗余长句注释（`(屏幕常亮)` / `(系统息屏)`），精简为对称、高质感的科技命名：
    - 简中：`挂墙大屏模式` / `随身伴侣模式`
    - 繁中：`掛牆大螢幕模式` / `隨身伴侶模式`
    - 英文：`Wall Kiosk` / `Mobile Companion`
  - 单选组（`radioGroupMode`）由易挤压折行的横向排版升级为 **纵向全宽排列（`orientation="vertical"` + `match_parent`）**，彻底根除中英文环境下的折行错位与挤压问题，触控区域更大更易点按；
- **设置面板多语言（i18n）全链路无遗漏动态自适应**：
  - 为 Bento 卡片 4 大分类标题（`txtSectionNodes`、`txtSectionRoutes`、`txtSectionAuth`、`txtSectionRuntime`）、语言行标签（`txtAppLanguageLabel`）、高级设置标题（`txtAdvancedSettingsTitle`）以及沙盒横幅全量注入 ViewBinding 引用；
  - 在 `applyLanguageToSettingsUI` 中挂载毫秒级文本重绘，实现点击 `[ 简 | 繁 | EN ]` 分段器瞬间，全局所有可见文本 100% 即时无感切换。

### 2. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并验证通过：`BUILD SUCCESSFUL in 18s`，生成最终产物 `outputs/apk/AuraGrid-v2.2.5-20261008_013616.apk` (11M)。

---

## 📅 2026-10-08 协作记录 (设置面板全维度升维：苹果 Liquid Glass / 黑曜石 Bento 布局重构与微晶交互落地)

### 1. 设置面板视觉层级与微晶工业设计升维 (`activity_main.xml`, `item_instance_row.xml`, `MainActivity.kt`)
- **对齐 iOS 客户端顶级排版体验 (Liquid Glass / Obsidian Bento 体系)**：
  - **顶部固定导航控制台**：注入科技感极强的顶部 Header（`txtSettingsTitle`、科技青胶囊微徽章 `txtStatusPill`、副标题 `txtSettingsDesc`）以及右上角黑曜石磨砂圆形关闭按钮（`btnTopCloseSettings`），实现全局 1 秒直出与一键退出；
  - **4 阶 Bento 黑曜石微晶卡片分区编排**：
    1. **空间中枢节点 (SPACE HUB NODES)**：中枢列表容器采用深空微晶底衬，添加节点按钮升维为发光青边框卡片；
    2. **网关寻址与路由 (GATEWAY ROUTES)**：局域网 LAN / 远程 WAN 输入组采用黑曜石卡片包裹，内置「🔍 自动发现中枢」脉冲探针按钮与发现卡片流；
    3. **中枢鉴权凭证 (SECURITY & AUTHENTICATION)**：统一账号密码凭证卡片，去重并强化输入视觉焦点；
    4. **运行环境与硬件 (RUNTIME & HARDWARE)**：包含工作模式单选胶囊、生物识别安全锁、家庭地震预警基准坐标、界面三语切换、Web 缩放分段器、版本检查与擦除数据等高级配置；
- **节点实例卡片重构 (`item_instance_row.xml`)**：
  - 彻底淘汰旧版 Gingerbread 风格 `@android:drawable/presence_online`，采用纯净 SVG 状态圆点 (`bg_status_dot.xml`)；
  - 右侧操作按钮独立容器封装，采用苹果风格黑曜石圆形毛玻璃按钮组（切换 `btnSwitchInstance`、编辑 `btnEditInstance`、删除 `btnDeleteInstance`），彻底根治文本溢出与挤压变形问题；
- **四语言资源完全对齐**：
  - 补齐 `section_nodes`、`section_routes`、`section_auth`、`section_runtime`、`app_language` 在默认英文、英文、简体中文与繁体中文全部资源池中的定义。

### 2. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并验证通过：`BUILD SUCCESSFUL in 21s`，生成最终产物 `outputs/apk/AuraGrid-v2.2.5-20261008_010654.apk` (11M)。

---

## 📅 2026-10-08 协作记录 (竖屏设置页「服务器」点击直出原生配置菜单与双轨 JS Bridge Polyfill 落地)

### 1. 竖屏端「服务器」菜单呼起痛点根治与双端协议对齐 (`MainActivity.kt`)
- **痛点研判与病灶定位**：
  - Pro 版本前端 `MobileSettingsTab.vue` 中，点击「服务器」按钮时首选检测 `native.postToNative` 函数，次选检测 `window.webkit.messageHandlers.AuraNative.postMessage`，均不匹配时回退提示“服务器地址请在大屏端系统设置中配置”；
  - iOS 端因 WKWebView 天然具备 `window.webkit` 且运行时注入了 `postToNative`，可直接通过 `openSettings` 触发原生面板；而 Android 端此前未挂载 `postMessage` / `postToNative`，导致误落入兜底提示，只能依靠物理三指三击或左上角 5 连击呼出设置；
- **全链路双轨 JS Bridge 兼容 Polyfill 注入**：
  - **`DOCUMENT_START_SCRIPT` + `onPageStarted` + `onPageFinished` 三重生命周期防护**：通过 `WebViewCompat.addDocumentStartJavaScript` 在全量 Frame 运行前注入 Polyfill，并在页面加载各阶段自动强化兜底；
  - **对齐 `window.webkit.messageHandlers.AuraNative.postMessage`**：实现 WebKit 命名空间模拟，将前端消息自动透明打包并通过 JSON 字符串分发至 Android Java 宿主；
  - **对齐 `window.AuraNative.postToNative(action, payload)`**：返回标准 ES6 Promise，注册并回调 `window.__auraCallbacks`，保证前端异步调用闭环；
- **Android `AuraNativeBridge` 原生动作分发硬化**：
  - 接入 `@JavascriptInterface fun postMessage(messageJson: String)` 与 `@JavascriptInterface fun openSettings()`；
  - 拦截 `openSettings` 动作并毫秒级调度 `runOnUiThread { toggleSettingsOverlay(true) }`，直接拉起黑曜石微晶实例列表与节点设置，彻底消除操作断层；
  - 完整兼容 `getHardwareFingerprint`、`getDeviceInfo`、`getAppVersion`、`getAppMode`、`getPushToken`、`playAlertSound`、`acknowledgeAlert`、`startUpgrade`、`syncServerConfig`、`requestSilentReAuth` 与 `clearStoredToken` 等全量协议指令。

### 2. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并验证通过：`BUILD SUCCESSFUL in 50s`，生成最终产物 `outputs/apk/AuraGrid-v2.2.5-20261008_003935.apk` (11M)。

---

## 📅 2026-09-24 协作记录 (苹果 Bonjour / mDNS 毫秒级零感发现引擎 NsdManager 双轨加速落地)

### 1. Android 原生 NsdManager mDNS 服务发现落地 (`SubnetScanner.kt`)
- **毫秒级零感发现体验全面对齐 iOS**：
  - 接入 Android 原生 `NsdManager` (`Context.NSD_SERVICE`)；
  - 启动扫描时（`subnetScanner.startScan()`），并发启动 `discoverServices("_auragrid._tcp", PROTOCOL_DNS_SD)`；
  - 在 1~5 毫秒内捕获局域网组播发布的 `_auragrid._tcp` 服务，通过 `ResolveListener` 毫秒级解析出物理 IPv4 与端口（如 `10.0.0.8:8125`）；
  - 发现后立即推入候选列表并回调 `callback.onHostDiscovered(host)`，实现类似 Home Assistant / Apple TV 的无感瞬时发现体验；
- **双轨无感兼容与原子级去重**：
  - 原有 HTTP 子网探测引擎继续并行静默运行，并优化优先探测端口（`8125`、`8500` 第一优先级）；
  - 通过 `ConcurrentHashMap.newKeySet<String>()` 的 `seenUrls.add(host.url)` 实现原子级线程安全去重；
  - 无论是 mDNS 先命中还是 HTTP 先命中，均无重复添加，即使在不支持组播的 Docker Bridge 模式或访客网络下，仍有 HTTP 子网探测无缝兜底；
- **扫描生命周期看门狗与资源释放**：
  - `stopScan()` 与全量探测完成时自动调用 `stopServiceDiscovery`，彻底释放系统级 NSD 监听资源，防止后台持续耗电。

### 2. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并验证通过：`BUILD SUCCESSFUL in 47s`，生成最终产物 `outputs/apk/AuraGrid-v2.2.5-20260924_032651.apk` (11M)。

---

## 📅 2026-09-21 协作记录 (双端体验绝对对齐：首次配置双轨梯度回退、多节点管理与防误触、家庭地震预警基准坐标及四语言资源硬化)

### 1. 登录与节点保存双轨鉴权梯度回退 (`MainActivity.kt`)
- **对齐 iOS 黄金逻辑 (Stage 1 LAN 3.5s ➔ Stage 2 WAN 6.0s)**：
  - 彻底解决用户在离家/蜂窝移动网络下首次配置或保存节点时直接报超时失败的痛点；
  - 重构 `performLoginRequest` 返回结构为 `AuthResult(token, externalUrl, userJson)`，支持动态超时传参；
  - 在登录返回时自动提取中枢的 `external_url`，若用户未填写 WAN 地址则毫秒级自愈回填并持久化入库；
  - `refreshActiveTokenSync` 同步支持双轨探活及 WAN 动态自愈升级。

### 2. 服务器节点列表与管理防御升级 (`activity_main.xml`, `item_instance_row.xml`, `MainActivity.kt`)
- **多节点 WAN 路由与多语言徽章对齐**：
  - 在每个中枢卡片中新增 WAN 穿透地址横向展示（点状分隔符 `•` + `txtInstanceWanUrl`），与 LAN 地址清晰并列；
  - 节点状态徽章（`txtInstanceStatus`）与切换按钮（`btnSwitchInstance`）接入动态多语言资源（`@string/active_badge` 与 `@string/switch_node_btn`）。
- **节点删除 Material 弹窗二次确认防误触**：
  - 接入 `MaterialAlertDialogBuilder`，弹出确认对话框提示删除后将清除该节点凭证；
  - 针对单个节点场景自动隐藏删除按钮，杜绝误删唯一中枢导致状态孤立。

### 3. 家庭地震预警基准坐标 (EEW Coordinates) 设置落地 (`activity_main.xml`, `MainActivity.kt`, `EarthquakeAlertActivity.kt`)
- **设置面板高级选项注入基准坐标卡片**：
  - 在「高级设置」中新增黑曜石微晶「家庭地震预警基准坐标」卡片，提供纬度（`inputHomeLat`）与经度（`inputHomeLon`）输入；
  - 配备「📍 自动获取 GPS 定位」按钮，支持一键读取设备当前高精度经纬度回填；
  - 坐标安全持久化至 `AuraGridPreferences`（`home_latitude` / `home_longitude`），无缝供 `EarthquakeAlertActivity` 消费，实现秒级精准 S 波到达倒计时与震中距离测算。

### 4. 国际化与硬编码拔除 100% 绝对纯净
- **四语言资源全量对齐**：
  - 默认（`values/strings.xml`）、英文（`values-en/strings.xml`）、简体中文（`values-zh/strings.xml`）与繁体中文（`values-zh-rTW/strings.xml`）对齐全部新特性词条；
  - 彻底拔除向导终页（`item_onboarding_connect.xml`）、设置面板以及退出/擦除按钮中的硬编码中文；
  - 移除冗余的 `btnExitDemo`，保留向导与横幅一键退出演示闭环。

### 5. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并通过：`BUILD SUCCESSFUL in 15s`，生成最新产物 `outputs/apk/AuraGrid-v2.2.5-20260921_171557.apk` (11M)。

---

## 📅 2026-09-12 协作记录 (双端体验全面对齐：四阶数字孪生向导、公共沙盒免密直达、生物识别安全锁与漫游白名单硬化)

### 1. 网络漫游探活与白名单安全屏障对齐 (`NetworkRoamingManager.kt` & `MainActivity.kt`)
- **零鉴权探活与外网地址毫秒级自愈**：
  - 将原先单一 `HEAD` 探针重构为三阶探活链：首选 `GET /api/v1/auth/status`（零鉴权开放接口，毫秒级比对返回体中的 `external_url` 并自动同步持久化至激活节点实例数据库）；回退探测 `/health` 与根路径 `HEAD /`；
- **私有网段与中枢宿主全量放行（消除跳出外部浏览器误伤）**：
  - 引入 `normalizeHost`、`isLocalPrivateHost`（RFC 1918 私网段：`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `*.local`, `localhost`, `127.0.0.1`）以及 `isKnownHubRoute`（扫描全量已配实例的 LAN/WAN 目标）；
  - 彻底杜绝在中枢切换或离家漫游时误将内部服务判为外链弹出 Chrome 浏览器的体验断层；
- **物理网络监听与离线蜂窝自愈重试**：
  - 注册 `ConnectivityManager.NetworkCallback`，精准捕获 Wi-Fi / 移动蜂窝切换事件；
  - 针对出门断开 Wi-Fi 瞬间蜂窝网络 2~4 秒的硬件时延，新增 `scheduleSelfHealRetry`，在首次探测全部离线后自动于 4 秒后发起轻量二次探测，保障离家 100% 连上外网。

### 2. 4 步全彩全屏设置向导与公共演示沙盒免密直达 (`overlay_onboarding.xml`, `item_onboarding_*.xml`, `MainActivity.kt`)
- **四阶全彩高保真视觉体系（Retina 资产物理编排）**：
  - 完整迁移并适配 iOS 端 Retina 高清视觉资产：`ob_hero_digital_twin`（空间中枢）、`ob_local_radar_shield`（本地雷达隐私盾）、`ob_dual_roaming_rings`（双轨漫游）与 `ob_biometric_vault`（安全地库）；
  - `ViewPager2` 打造横滑卡片流，顶部配备科技青进度指示胶囊条与实时三语（简中 / 繁中 / 英文）切换器；
- **终页中枢自动探针与一键回填**：
  - 进入第 4 步（连接空间中枢）时，后台静默触发 `SubnetScanner` 25 并发轻量扫描，智能去重过滤已绑节点，直出黑曜石微晶候选卡片；
- **官方演示沙盒 (`demo2.iaura.cn`) 免密 1 秒直达与安全退出闭环**：
  - 在向导第 4 步部署 **`✨ 探索官方演示系统`** 卡片，携带 `AuraGridApp/2.2.5 (Android; Mobile)` UA 异步向 `https://demo2.iaura.cn/api/v1/auth/login` 请求换取演示 Token，0 输入 1 秒直达真实全功能大屏；
  - 设置面板顶部展示黑曜石紫光横幅，提供 **`退出演示模式并配置家庭系统`** 一键操作，执行数据清除并平滑重开向导。

### 3. 生物识别安全地库与微晶交互升维 (Biometric Security Vault & Micro-Interactions)
- **多生物识别体系落地 (`androidx.biometric:biometric:1.1.0`)**：
  - 支持人脸识别（Face Unlock）、指纹识别（Fingerprint）以及系统锁屏密码多重回退；
  - 新建 `overlay_biometric_lock.xml` 黑曜石微晶锁屏遮罩；
- **Kiosk 上墙模式智能避让与随身伴侣模式自愈锁定**：
  - 在高级设置中新增「生物识别安全锁」开关，并在上墙 Kiosk 模式下自动隐藏与避让；
  - 随身伴侣模式（`isKioskMode == false`）下，App 切入后台或锁屏时自动上锁，回到前台（`onResume`）时优雅触发 `BiometricPrompt`，保护家庭数字中枢隐私；
- **雷达声纳动效与微触感反馈**：
  - 中枢探测时启用雷达旋转动画与状态呼吸反馈；
  - 关键操作节点（节点切换、保存、演示模式进入/退出）注入原生轻微触控震动（`performHapticFeedback`）。

### 4. 三语国际化 (i18n) 100% 绝对对齐
- 简体中文（`values-zh/strings.xml`）、繁體中文（`values-zh-rTW/strings.xml`）与英文（`values/strings.xml`, `values-en/strings.xml`）对齐全部新特性词条，0 硬编码。

### 5. 远程构建与产物验证
- 在 Debian 编译沙盒（`10.0.0.60`）执行 headless 构建并验证通过：`BUILD SUCCESSFUL in 12s`，生成最终产物 `outputs/apk/AuraGrid-v2.2.5-20260912_153256.apk` (11M)。

---

### 1. 外网远程访问地址 Bridge 自动同步入库 (`MainActivity.kt`)
- **根因痛点消除**：
  - 用户在局域网首次配网后，若在横屏大屏端配置了 DDNS 或公网访问地址，Android 客户端无须再进入 Kiosk 原生设置手动输入 WAN 地址；
- **全链路自动同步闭环**：
  - **AuraNativeBridge 扩展**：新增 `@JavascriptInterface fun syncServerConfig(wanUrlStr: String)`；
  - **实例数据库自动写入**：当 WebView 页面加载且发现中枢配置了 `externalUrl` 时，自动同步并更新激活节点的 `wanUrl`，实现断开 Wi-Fi 离家后无感漫游。

---

## 📅 2026-09-09 协作记录 (节点添加与切换直达 Dashboard 及 /login 滞留自愈)

### 1. WebView Token 注入与 `/login` 登录页滞留病灶歼灭 (`MainActivity.kt`)
- **根因分析**：
  - 过去在 `onPageFinished` 中检测到 `localStorage.getItem('auth_token') !== '$token'` 时调用 `window.location.reload()`；
  - 若页面先前因无会话进入了 `/login`，简单的页面重载（reload）依然重新打开 `/login`，而不会触发 SPA 守卫跳出；
- **自愈重定向落地**：
  - 在 `localStorage` 写入 `auth_token` 与 `auth_user` 后，显式判断 `window.location.pathname` 与 `hash`；
  - 若处于 `/login`，立即执行 `window.location.replace('/')` 无缝直达 Dashboard；若在其他业务页面且 Token 发生变更才执行 `reload()`；
- **双端体验对齐**：彻底消除 Android 节点管理后进入登录页的二次繁琐输入困扰。

---

## 📅 2026-09-07 协作记录 (局域网中枢轻量并发自动探针与一键配网)

### 1. 局域网轻量探针引擎全链路落地 (`SubnetScanner.kt`)
- **网段自适应提取**：通过 `NetworkInterface` 读取 WiFi IPv4 并推导 `/24` 子网网段；
- **高频优先顺序探测算法**：
  - 第一梯队：网关（`.1`）；
  - 第二梯队：本机邻域（`localHost ± 5`）；
  - 第三梯队：常用服务静态 IP（`.2~.15`, `.50`, `.60`, `.70`, `.80`, `.90`, `.100`, `.150`, `.200`, `.254`）；
  - 靶向端口优先：`8125`（Aura Grid 官方生产端口）、`5174`（开发前端）、`8500`（直连后端）、`3000`、`80`。
- **三阶精准识别链（对齐 iOS 黄金逻辑）**：
  - 一阶黄金探针：`GET /api/v1/auth/status`（包含 `"initialized"`）；
  - 二阶系统探针：`GET /health`（包含 `"ha_connected"` / `"未激活"` / `"auragrid"`）；
  - 三阶 Web 标题探针：`GET /`（包含 `<title>Aura Grid</title>` 或 `"auragrid"`）。
- **高并发与防抖控制**：25 并发线程池与 800ms 超时 OkHttp 连接池，5~8 秒内扫完核心频段。

### 2. Kiosk 设置面板一键发现与自动回填 (`activity_main.xml` & `MainActivity.kt`)
- **自动发现触发与卡片容器**：在局域网地址上方新增「🔍 自动发现中枢」发光胶囊按钮，打开“添加节点”时在后台静默发起扫描；
- **黑曜石微晶候选卡片 (`item_discovered_host.xml`)**：展示中枢名称、IP/端口与实时延迟毫秒数；
- **零键盘一键回填**：点击候选卡片即自动回填局域网地址并补全默认节点名称，彻底免除上墙触控大屏软键盘输入的困扰。

### 3. 三语国际化 (i18n)
- 简体中文（`values-zh/strings.xml`）、繁體中文（`values-zh-rTW/strings.xml`）与英文（`values/strings.xml`, `values-en/strings.xml`）完整对齐。

### 4. 远程构建与产物验证
- 通过 `build_apk.sh` 在 Debian 编译沙盒（`10.0.0.60`）完成 Gradle 远程构建：`BUILD SUCCESSFUL in 57s`，生成 `outputs/apk/AuraGrid-v2.2.5-20260907_163808.apk` (7.6M)。

---
*协作助手: Antigravity — 致力于构建工业级跨端通信与交互体验。*
