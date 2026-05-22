#!/bin/bash
# Headless Android SDK setup script for Debian 10.0.0.60
set -e

echo "=== 📥 1. Installing Required Base Package Utilities ==="
apt-get update
apt-get install -y unzip curl

echo "=== 📂 2. Setting Up Directory Framework ==="
SDK_ROOT="/usr/lib/android-sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"

cd /tmp
echo "=== 🌐 3. Downloading Android Command Line Tools ==="
# Pull the latest stable command line tools package from Android Developer Center
curl -sSLo cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

echo "=== 📦 4. Extracting Tools Package ==="
unzip -q cmdline-tools.zip
rm -f cmdline-tools.zip

# Setup the specific subdirectory layout Android requires: cmdline-tools/latest/bin
mkdir -p "$SDK_ROOT/cmdline-tools/latest"
cp -r cmdline-tools/* "$SDK_ROOT/cmdline-tools/latest/"
rm -rf cmdline-tools

# Export environment paths for local process context
export ANDROID_HOME="$SDK_ROOT"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "=== 📄 5. Auto-Accepting Android Licenses ==="
yes | sdkmanager --licenses

echo "=== ⚙️ 6. Installing Android Platform SDK 34 & Build-Tools ==="
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "=== 🖊️ 7. Persisting Environment Paths to /root/.bashrc ==="
if ! grep -q "ANDROID_HOME" /root/.bashrc; then
    echo "" >> /root/.bashrc
    echo "# Android SDK paths set by Aura Grid installer" >> /root/.bashrc
    echo 'export ANDROID_HOME="/usr/lib/android-sdk"' >> /root/.bashrc
    echo 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"' >> /root/.bashrc
fi

echo "=== 🎉 8. Headless Android SDK Installation Succeeded! ==="
