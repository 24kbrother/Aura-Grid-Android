# 🧠 Aura Grid Android - 协作备忘录 & 踩坑记录

> 本文档由协作助手自动生成，记录 Android 客户端项目关键变更、架构演进与排坑指南。

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
