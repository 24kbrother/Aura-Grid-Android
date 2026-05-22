# 📱 Aura Grid Android Companion

> 工业级安卓伴侣与壁挂式大屏 Kiosk 控制端 / Industrial-grade Android Companion & Wall-mounted Kiosk Display Wrapper.

[![Platform](https://img.shields.io/badge/Platform-Android%208.0+-00C853?style=for-the-badge&logo=android)](https://developer.android.com)
[![Build](https://img.shields.io/badge/Build-Gradle%20Kotlin%20DSL-6200EE?style=for-the-badge&logo=gradle)](https://gradle.org)
[![Version](https://img.shields.io/badge/Version-v2.1.7--dynamic--i18n-FF1744?style=for-the-badge)](https://github.com/24kbrother/Aura-Grid-Android)

`Aura-Grid-Android` 是专为 **Aura Grid 智能家居管理终端** 量身打造的高性能原生 Kotlin 容器。它不仅是一个高度加速的 Web 容器，更是一个集成了局域网智能漫游、多通道云推送（FCM / 华为 HMS）、硬件控制和断网自愈能力的工业级控制中心。

---

## 🌟 核心特性 / Core Features

### 1. 沉浸式 Kiosk 锁屏控制 / Immersive Kiosk Mode
* **全屏硬件锁定**：自动隐藏系统状态栏与虚拟导航键，防止用户意外退出。
* **物理防误触机制**：支持阻断物理返回键，通过**右边缘向左滑动的特定手势（R2L Swipe）**或特定双指双击调用管理员设置面板。
* **硬件唤醒 (WakeLock)**：接收到门铃、漏水或火灾等高危报警时，自动唤醒并点亮屏幕，直接显示对应摄像头画面。

### 2. 双通道智能漫游与自愈 / Smart Roaming & Auto-Healing
* **智能漫游探测**：启动时自动发送 UDP/HTTP 探测局域网网关（LAN URL），若可用则优先走千兆局域网；若离家则无感切换至外网（WAN URL）。
* **断网自愈界面**：当网络完全中断时，自动覆盖高颜值的毛玻璃霓虹断网报警屏，并在后台保持高频重试，网络恢复后无感刷新还原。

### 3. 多通道实时报警监听器 / Real-time Alarm Service
* **前台 WebSocket 守护进程**：在后台启动符合 Android 14 最新规范的 `remoteMessaging` 前台服务，与网关保持长连接，实现毫秒级报警响应。
* **双通道云推送**：集成 **Google FCM** 与 **华为 HMS Push Kit**（完美兼容 HarmonyOS 3/4 及国内无 GMS 模拟器环境），支持 App 被系统杀掉后的强行唤醒推送。

### 4. 零延迟动态中英切换 / Dynamic i18n Engine
* **即时无闪烁刷新**：在设置弹窗顶部集成紧凑的 `中/EN` 双语胶囊按钮。点击时通过内存直译机制更新，**彻底告别 Activity 销毁重建导致的屏幕闪烁**。
* **表单零数据丢失**：在切换语言时，用户当前正在填写的 IP 地址、用户名和密码等配置完美保留。

---

## 📂 项目结构 / Directory Structure

```
/Users/24k/Gemini_Projects/Aura-Grid-Android/
├── settings.gradle.kts          # Gradle 多仓库配置（整合 GMS + 华为 Maven）
├── build.gradle.kts             # 根目录编译插件与 Kotlin 版本管理
├── gradle.properties            # JVM 内存调优与 AndroidX 兼容开关
├── ANDROID_DEV_DOC.md           # 深度技术实现与踩坑文档
└── app/
    ├── build.gradle.kts         # App 依赖（WebKit, Socket.IO, Firebase, HMS）
    ├── proguard-rules.pro       # 针对 JS Bridge 接口与推送 SDK 的代码混淆保护
    └── src/main/
        ├── AndroidManifest.xml  # 系统权限、前台服务及开机自启广播声明
        ├── res/                 # 资源文件（Obsidian 暗色主题、防裁剪圆角自适应图标）
        └── java/com/auragrid/app/
            ├── MainActivity.kt        # Immersive 视窗、管理员设置与 native 桥接
            ├── AuraSocketService.kt   # WebSocket 前台守护服务
            ├── NetworkRoamingManager.kt # 局域网/外网双通道漫游引擎
            └── NotificationOrchestrator.kt # 报警分发、显示唤醒与静音绕过
```

---

## 🛠️ 运维与编译部署 / Development & Build Guide

本项目支持在 Mac 本地开发调试，并配备了专为 headless（无头）服务器设计的 **Debian 远程自动化编译流水线**。

### 1. 远程一键编译（Debian Build Server `10.0.0.60`）
在 Mac 终端中运行以下组合命令，即可自动同步源码、执行编译并拉回最新 APK：

```bash
# 1. 增量同步源码到 Debian 编译沙盒
rsync -avz --exclude='.gradle' --exclude='build' --exclude='app/build' --exclude='.git' --exclude='.DS_Store' /Users/24k/Gemini_Projects/Aura-Grid-Android/ root@10.0.0.60:/root/aura-grid-android/

# 2. 执行 Headless 远程构建
ssh root@10.0.0.60 "cd /root/aura-grid-android && export ANDROID_HOME=/usr/lib/android-sdk && /opt/gradle-8.2/bin/gradle clean assembleDebug"

# 3. 拉回编译完成的 APK
scp root@10.0.0.60:/root/aura-grid-android/app/build/outputs/apk/debug/app-debug.apk /Users/24k/Gemini_Projects/Aura-Grid-Android/outputs/apk/app-debug.apk
```

### 2. 本地调试（Android Studio）
1. 启动 **Android Studio**，选择 **Open** 导入项目文件夹：`/Users/24k/Gemini_Projects/Aura-Grid-Android`
2. 同步 Gradle 完成后即可直接连接真机/模拟器一键运行（Run）。

---

## 🔒 跨端桥接协议 / JS Bridge Specification

伴侣端向 Web 前端自动注入 `window.AuraNative` 对象，支持以下原生交互：
* `window.AuraNative.getHardwareFingerprint()`：获取由 `ANDROID_ID` 进行 SHA-256 加密后的硬件唯一标识（HWID）。
* `window.AuraNative.getPushToken()`：获取当前活跃的 FCM / HMS 推送 Token 用于后端推送注册。
* `window.AuraNative.playAlertSound(severity)`：调用原生声音播放器根据警报级别（CRITICAL / WARNING）播放对应声调。
