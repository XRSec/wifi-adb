# WiFi ADB

[![Platform](https://img.shields.io/badge/Platform-Android%204.4%2B%20(API%2019%2B)-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Non--Commercial-red.svg)](LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-XRSec%2Fwifi--adb-black.svg)](https://github.com/XRSec/wifi-adb)

A lightweight, standalone, high-compatibility Android application to enable and manage **Wireless ADB (TCP/IP)** without requiring USB cables or a PC host command after initial setup.

---

## Key Features

- **Extreme Compatibility (Android 4.4+ KitKat)**:
  - `minSdk = 19`, `targetSdk = 34`.
  - Built with classic native XML Views and pure `android.app.Activity` (zero Compose overhead, zero crashes on legacy hardware).
- **One-Tap Wireless ADB Control**:
  - Quickly toggle ADB over TCP/IP (default port `5555` or custom port from settings) via `su` / Root permissions.
  - Safely switch back to USB debugging mode (`port -1`).
  - Active detection using direct non-blocking loopback socket probing (`127.0.0.1:port`).
- **Deep Accessibility Authorization Assistant**:
  - Automatically identifies USB and Wireless debugging RSA key authorization dialogs.
  - Automatically checks **"Always allow from this computer"** (`isCheckable`) and clicks **"OK / Allow"** (`isClickable`).
  - Deep support across AOSP, Google Pixel, and major Chinese/international OEM skins:
    - Xiaomi MIUI / HyperOS
    - OPPO / Realme / OnePlus ColorOS
    - Vivo / iQOO OriginOS
    - Huawei / Honor EMUI & HarmonyOS
    - Samsung OneUI
  - Strict security guardrails: Package whitelist filtering, multi-keyword RSA signature verification, and depth limits (<=8) to prevent misclicks on non-ADB dialogs.
- **Persistent Foreground Service**:
  - Displays device local IPv4 and one-click copyable connection command (`adb connect <ip>:<port>`) in the notification drawer.
  - Immediate foreground attachment to prevent Android 8.0+ `ForegroundServiceDidNotStartInTimeException`.
- **Dynamic Network & Boot Listeners**:
  - Automatically refreshes IPv4 on WiFi connection/disconnection.
  - Optional auto-start on boot (`RECEIVE_BOOT_COMPLETED`).
- **Bilingual Internationalization (i18n)**:
  - Supports English and 简体中文 (Chinese), with runtime language switching via settings.
- **One-Click Command Copy**:
  - Copy `adb connect <ip>:<port>` directly to the clipboard.

---

## Architecture & Principles

### 1. TCP/IP Port Switching
Enabling Wireless ADB executes atomic chained shell commands via root:
```bash
setprop service.adb.tcp.port 5555 && stop adbd && start adbd
```
Reverting back to USB mode executes:
```bash
setprop service.adb.tcp.port -1 && stop adbd && start adbd
```

### 2. State Verification
Unlike naïve implementations relying solely on unverified `getprop`, WiFi ADB conducts a non-blocking TCP socket connection against `127.0.0.1:<port>`. A successful socket handshake confirms that the ADB daemon is genuinely actively listening on the target port.

### 3. Automated Dialog Processing
Using Android's `AccessibilityService`, incoming window state events (`typeWindowStateChanged`) from system packages are inspected. If the view hierarchy contains cryptographic RSA fingerprint or debugging prompt keywords, the accessibility node tree is parsed to check the persistence checkbox and trigger the positive action button.

---

## Supported Architectures

The build targets all standard Android ABIs:
- `armeabi-v7a` (32-bit ARM)
- `arm64-v8a` (64-bit ARM)
- `x86` (32-bit Intel/AMD)
- `x86_64` (64-bit Intel/AMD)

---

## Build from Source

```bash
# Clone the repository
git clone https://github.com/XRSec/wifi-adb.git
cd wifi-adb

# Build debug APK
./gradlew assembleDebug

# Output APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Non-Commercial License & Legal Disclaimer

### Non-Commercial License
This software is provided exclusively for **personal, educational, research, and non-commercial testing purposes**. Any commercial redistribution, resale, bundling, or incorporation into proprietary commercial software is strictly prohibited without prior written permission from the copyright holder.

### Legal Disclaimer
**USE AT YOUR OWN RISK.** Opening port 5555 enables network-level debugging access to your device. Enabling this port on untrusted public Wi-Fi networks may expose your device to security vulnerabilities or unauthorized access. The authors and contributors shall not be held liable for any damages, security incidents, device malfunctions, or data loss resulting from the use or misuse of this software.
