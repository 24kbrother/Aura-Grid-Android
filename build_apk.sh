#!/bin/bash
# AURA Grid Android Headless Automated Builder
# Automatically builds, pulls, and names APK with version number and timestamp.
set -e

# 1. 从 app/build.gradle.kts 中自动解析当前的版本号 (versionName)
VERSION=$(grep -oE 'versionName\s*=\s*"[^"]+"' app/build.gradle.kts | cut -d'"' -f2 || echo "unknown")
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
APK_NAME="AuraGrid-v${VERSION}-${TIMESTAMP}.apk"

echo "=== 📱 AURA Grid Android Headless Builder ==="
echo "📌 当前版本号 : $VERSION"
echo "📌 时间戳标记 : $TIMESTAMP"
echo "📌 目标 APK 名: $APK_NAME"
echo "--------------------------------------------"

echo "=== 📥 1. 增量同步源码到 Debian 编译沙盒 (10.0.0.60) ==="
rsync -avz --exclude='.gradle' --exclude='build' --exclude='app/build' --exclude='.git' --exclude='.DS_Store' ./ root@10.0.0.60:/root/aura-grid-android/

echo "=== ⚙️ 2. 执行 Headless 远程构建 (Gradle clean assembleDebug) ==="
ssh root@10.0.0.60 "cd /root/aura-grid-android && export ANDROID_HOME=/usr/lib/android-sdk && /opt/gradle-8.2/bin/gradle clean assembleDebug"

echo "=== 📥 3. 拉回编译好的 APK 并自动进行版本与时间戳命名 ==="
mkdir -p outputs/apk
scp root@10.0.0.60:/root/aura-grid-android/app/build/outputs/apk/debug/app-debug.apk outputs/apk/"$APK_NAME"

echo "--------------------------------------------"
echo "=== 🎉 安卓 App 远程构建与命名成功！ ==="
echo "📂 本地路径: outputs/apk/$APK_NAME"
echo "📦 文件大小: $(du -sh outputs/apk/"$APK_NAME" | cut -f1)"
