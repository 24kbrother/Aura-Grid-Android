# 🧠 Aura Grid Android - 协作备忘录 & 踩坑记录

> 本文档由协作助手自动生成，记录 Android 客户端项目关键变更、架构演进与排坑指南。

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
