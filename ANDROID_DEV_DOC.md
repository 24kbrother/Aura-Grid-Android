# 📱 Aura Grid Android Companion App Developer Documentation (v2.0.2)

This documentation details the architecture, styling assets, user interaction, compilation procedures, and system integration strategies for the Aura Grid Android companion and wall kiosk application.

---

## 🏗️ Project Architecture Overview

The app is built as a single-module, high-performance Android Gradle project using modern **Kotlin** and **Material Components**. It operates in two main modes: **Wall Kiosk Mode** (keeping the screen persistently active) and **Companion Mode** (acting as a responsive mobile dashboard wrapper).

```
/Users/24k/Gemini_Projects/Aura-Grid-Android/
├── settings.gradle.kts          # Gradle settings configuring repositories (Maven GMS + Huawei HMS)
├── build.gradle.kts             # Top-level dependencies and Kotlin compilers configuration
├── gradle.properties            # JVM tuning and AndroidX triggers
└── app/
    ├── build.gradle.kts         # App dependencies (ConstraintLayout, Webkit, Socket.IO, FCM, HMS)
    ├── proguard-rules.pro       # Keep-rules for JS native bridge interfaces, Socket.IO, and Push SDKs
    └── src/main/
        ├── AndroidManifest.xml  # Core configurations, services, activities, receivers, and permissions
        ├── res/
        │   ├── drawable/
        │   │   ├── ic_launcher.xml            # Layered app icon with 10% inner padding protection
        │   │   ├── ic_launcher_foreground.png # Raw high-resolution brand PNG icon
        │   │   └── glass_panel_background.xml # Neon dark glassmorphism shape background
        │   ├── layout/
        │   │   ├── activity_main.xml          # Immersive WebView layout + responsive setup configuration modal
        │   │   └── activity_offline.xml       # Premium cyberpunk connection-lost overlay layout
        │   ├── values/
        │   │   ├── colors.xml                 # Cyberpunk Obsidian dark theme color assets
        │   │   ├── strings.xml                # Strings dictionary for system locales
        │   │   └── themes.xml                 # Main NoActionBar MaterialComponents theme
        │   └── xml/
        │       └── network_security_config.xml# LAN cleartext authorization (enabling local HTTP/WS)
        └── java/com/auragrid/app/
            ├── MainActivity.kt                # Immersive WebView window, setup form, routing and JS bridge
            ├── AuraSocketService.kt           # Background Foreground Service for LAN Socket.IO updates
            ├── NotificationOrchestrator.kt    # Push alert categorizer, screen wake control and alarm sounds
            ├── NetworkRoamingManager.kt       # Double-path probing engine (LAN Socket ping -> WAN fallback)
            ├── AuraFcmService.kt              # Google Firebase Cloud Messaging background receiver
            ├── AuraHmsService.kt              # Huawei Mobile Services Push Kit background receiver
            └── BootReceiver.kt                # On-boot autostart handler for unmanned wall displays
```

---

## ⚙️ Core Technical Implementation Details

### 1. High-Contrast Premium Dark Theme Settings Modal
- **Contrast Optimization**: Unfocused hint labels on `TextInputLayout` inputs default to black in standard light-theme environments. We explicitly override this by setting `android:textColorHint="@color/text_secondary"` (light grey) and `app:hintTextColor="@color/neon_teal"` (focused neon teal).
- **Styling Architecture**: Binds modern outlined boxes with rounded card overlays, making text inputs extremely legible against dark, obsidian glass backgrounds.

### 2. Right-Edge Swipe Gesture Settings Controller
- **UX Intent**: Instead of double-tapping the screen (which conflicts with web app zoom controls and double-clicks on active elements), the administrator Settings panel is triggered by a **Right-to-Left Edge Swipe Gesture**.
- **Execution Mechanism**:
  - Monitors touch motions inside `MainActivity.kt`.
  - When a swipe starts within **`80 pixels`** of the right edge of the screen, moves left by at least **`150 pixels`** horizontally, and remains relatively straight (vertical delta `< 150 pixels`), the Settings panel is invoked.
  - Keeps the double-tap fallback operational on the dark `loadingOverlay` to allow easy recovery if connection configurations are corrupt on first launch.

### 3. Clipless Layered App Icon Configuration
- **Android Rounded Corner Clipping**: Modern Android launchers (HarmonyOS, HyperOS, MIUI, Pixel Launcher) crop icons into circles, squircles, or round-rectangles. Full-bleed PNG icons will have their logo details clipped at the corners.
- **Adaptive Solution**: The PNG brand asset is renamed to `@drawable/ic_launcher_foreground.png`. We compose a layered `ic_launcher.xml` drawable featuring:
  1. A solid obsidian dark background (`#0A0B0E`).
  2. The foreground logo shifted inward via an explicit **`16dp`** (approx. 10%) safety padding:
     ```xml
     <item
         android:drawable="@drawable/ic_launcher_foreground"
         android:top="16dp"
         android:bottom="16dp"
         android:left="16dp"
         android:right="16dp" />
     ```
  - This guarantees the main logo remains untouched by system clipping shapes while matching the premium dark theme.

### 4. Background Foreground Alert Monitor & Fallbacks
- **AuraSocketService**: Stays active persistently as an OS-trusted background process, establishing rapid real-time Socket.IO streaming nodes with the server to deliver critical home alerts under milliseconds.
- **Push Orchestration**: Uses high-impact sound streams and triggers hardware `SCREEN_BRIGHT_WAKE_LOCK` to activate tablet displays immediately on leak or doorbell rings.

---

## 🚀 Debian Headless Build & Compilation Guide

The build environment is completely decoupled, using the local Mac for coding and the Debian server (`10.0.0.60`) for headless Gradle APK compilation:

### A. Sync Local Source Code to Debian
Run rsync to synchronize local modifications while ignoring build caches:
```bash
rsync -avz --exclude='.gradle' --exclude='build' --exclude='app/build' --exclude='.git' --exclude='.DS_Store' /Users/24k/Gemini_Projects/Aura-Grid-Android/ root@10.0.0.60:/root/aura-grid-android/
```

### B. Execute Remote Gradle Build
SSH to the server and trigger the compile assembler:
```bash
ssh root@10.0.0.60 "cd /root/aura-grid-android && export ANDROID_HOME=/usr/lib/android-sdk && /opt/gradle-8.2/bin/gradle clean assembleDebug"
```

### C. Retrieve Compiled APK
Pull the resulting APK back to the Mac outputs folder:
```bash
scp root@10.0.0.60:/root/aura-grid-android/app/build/outputs/apk/debug/app-debug.apk /Users/24k/Gemini_Projects/Aura-Grid-Android/outputs/apk/app-debug.apk
```
