<div align="center">

<img src="docs/public/huaweipods-logo.png" width="120" height="120" alt="HuaweiPods Icon"/>

# HuaweiPods ColorOS

**An LSPosed module that brings Huawei earphones into ColorOS system UI**

[![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://www.android.com/)
[![ColorOS](https://img.shields.io/badge/ROM-ColorOS-1BA784?style=flat-square)](https://www.coloros.com/)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-6F42C1?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/github/license/ByteJuno/HuaweiPods-ColorOS?style=flat-square)](LICENSE)

[Releases](https://github.com/ByteJuno/HuaweiPods-ColorOS/releases) ·
[Documentation](https://huaweipods.npiter.de/) ·
[Support the project](docs/sponsor/index.md) ·
[Report an issue](https://github.com/ByteJuno/HuaweiPods-ColorOS/issues) ·
[Telegram updates](https://t.me/ByteJuno)

**[简体中文](README.md)** · **English**

</div>

HuaweiPods ColorOS brings Huawei earphone battery and controls to ColorOS Quick Device Connect, My Devices, Device Space, system Bluetooth settings and Fluid Cloud. It uses the independent application ID `io.github.artifical0.huaweipods.coloros`, so the original HuaweiPods LSPosed entry cannot replace it.

This fork targets ColorOS only and removes the Xiaomi / HyperOS integrations. HyperOS users should use upstream [HuaweiPods](https://github.com/Nshpiter/HuaweiPods).

## Current release: 1.9.3.0

- Fluid Cloud cards support ANC switching. On ColorOS 17, tapping the card opens the module's earphone popup.
- Bluetooth settings, the device-details page, Device Space cards and the Control Center Bluetooth tile show left / right / case battery.
- Low-latency and wear-detection switches confirm by readback and roll back after a timeout. Disconnecting clears cached battery and state.
- Fixes cropped tall case images in notifications and Fluid Cloud (#8).

Tested with first-generation FreeClip on PLK110 / ColorOS 17.0.0.105 and OPD2515 / ColorOS 17.0.0.103 (LSPosed API 102). Other models have not been tested on ColorOS devices. See the [1.9.3.0 release notes](.github/release-notes/1.9.3.0.md) for details and the [release notes directory](.github/release-notes/) for older versions.

## Features

- Shows earphone images and battery in ColorOS Quick Device Connect, My Devices and Device Space.
- Publishes left / right / case battery to the ColorOS earphone battery channel for the status bar, Bluetooth settings and other system UI.
- Provides quick ANC, low-latency or wear-detection controls in My Devices, the module popup and Fluid Cloud.
- Offers model-specific ANC, transparency, ANC level, gesture, sound preset and EQ settings.
- Identifies modern models and colors over Bluetooth and downloads images from Huawei's CDN, falling back to cached or bundled images.
- Includes low-battery reminders, manual model binding by Bluetooth address, in-app update checks and a scope restart prompt after updates.

## Supported models

One APK supports the 20 models below. The table lists integrated capabilities only; behavior may vary with earphone firmware and ColorOS component versions.

| Model | Status | Main capabilities |
| --- | --- | --- |
| HUAWEI FreeBuds 3 | Stable | Battery, ANC on/off, nine-position spatial ANC, double-tap |
| HUAWEI FreeBuds 4E | Extended | Battery, two ANC levels with readback, double-tap / press-and-hold, wear detection, 3 sound presets |
| HUAWEI FreeBuds 4 | Extended, not tested on ColorOS | Separate model and image identity with shared 4E-family controls |
| HUAWEI FreeBuds 5 | Extended | Battery, three ANC levels with readback, wear detection, 4 sound presets, high-quality audio, low latency |
| HUAWEI FreeBuds 5i | Extended | Battery, transparency / ANC readback, four ANC levels, double-tap, wear detection, 4 sound presets, high-quality audio, low latency |
| HUAWEI FreeBuds SE 4 ANC | Extended | Battery, transparency / ANC readback, three ANC levels, wind-noise reduction, 4 sound presets, 10-band EQ, low latency |
| HUAWEI FreeBuds 6i | Extended | Battery, transparency / ANC, four ANC levels, voice transparency, double / triple-tap, 4 sound presets, 10-band EQ, low latency |
| HUAWEI FreeBuds 6 | Extended, not tested on ColorOS | Battery, three ANC levels with readback, adaptive volume, drop alerts, wear detection, sound presets, low latency |
| HUAWEI FreeBuds Pro 3 | Extended | Battery, three-mode control with readback, four ANC levels, voice transparency, long-press / pinch / swipe, low latency |
| HUAWEI FreeBuds Pro 4 | Basic | Battery, ANC on/off |
| HUAWEI FreeBuds Pro 5 | Extended | Battery, three-mode readback, four ANC levels, transparency modes, gestures, wear detection, head and voice controls, spatial audio, 9 sound presets, 10-band EQ, high-quality audio, low latency, dual-device connection |
| HUAWEI FreeBuds 7 | Extended, not tested on ColorOS | Battery, three-mode readback and three ANC levels, spatial audio, gestures, wear detection, 8 sound presets, 10-band EQ, high-quality audio, low latency; protocol verified upstream on HyperOS ([notes](docs/FREEBUDS7_ADAPTATION.md), Chinese) |
| HUAWEI FreeBuds 7i | Extended | Battery, transparency / ANC readback, four ANC levels, gestures, wear detection, head control, spatial audio, 4 sound presets, 10-band EQ, high-quality audio, low latency, dual-device management |
| HUAWEI FreeClip | Extended | Three-part battery, double / triple-tap, wear detection, adaptive left/right earbuds, drop reminder, 4 sound presets, low latency |
| HUAWEI FreeClip 2 | Extended | Battery, double / triple-tap and swipe, spatial audio, low latency, nod / shake actions, noisy-environment volume boost |
| HUAWEI FreeClip 2 Collector Edition | Extended, not tested on ColorOS | Separate model and color image identity with shared FreeClip 2 controls |
| HUAWEI FreeArc | Extended | Three-part battery, double / triple-tap, press-and-hold and swipe, 5 sound presets, 10-band EQ |
| HUAWEI Eyewear (1st generation) | Basic | Left / right temple battery |
| HUAWEI Eyewear 2 | Basic | Left / right temple battery, double-tap / swipe, low latency |
| HUAWEI Eyewear 3 | Basic | Model identification, left / right temple battery, color images |

## Requirements

- OPPO / OnePlus / realme device running ColorOS 16 or 17
- Android 15+
- LSPosed API 101+; API 102 frameworks also hot-reload updates
- A paired model from the table

## Quick start

1. Install the APK from [GitHub Releases](https://github.com/ByteJuno/HuaweiPods-ColorOS/releases) and follow the first-run guide to check LSPosed and scopes.
2. Enable the module in LSPosed and select scopes:
   - Required: `com.android.bluetooth`, `com.heytap.mydevices`, `com.heytap.accessory`
   - Optional: `com.oplus.wirelesssettings` (battery and earphone-settings entry in system Bluetooth settings)
   - Optional: `com.android.settings` (three-part battery on the device-details page)
   - Optional: `com.android.systemui` (three-part battery on the Control Center Bluetooth tile and Fluid Cloud capsule)
   - Optional: `com.huawei.smartaudio` (FreeClip 2 spatial-audio and sound-preset sync when Huawei AI Life/Smart Audio is installed)
3. Restart the scoped apps from HuaweiPods, or reboot the phone.
4. For Fluid Cloud, enable **ColorOS Fluid Cloud** in HuaweiPods settings and **Live alerts** in system notification settings.
5. Connect your earphones. If a renamed or legacy device is not identified, select its model once in HuaweiPods.

If Fluid Cloud appears only after opening the app, enable HuaweiPods ColorOS in system auto-launch settings and allow all background activity under App info → Battery management. Recheck these settings after reinstalling.

Versions from 1.8.1-coloros.2 use a fixed signing key and support in-place updates. Earlier versions require reinstalling. See [getting started](docs/guide/getting-started.md) for more details.

## Adding models

New models need captured communication between Huawei AI Life/Smart Audio (`com.huawei.smartaudio`) and the earphones. Remove device addresses, account data and other personal information before sharing captures. See the [Huawei earphone capture guide](docs/DEBUG_CAPTURE_GUIDE.md) (Chinese).

For a new ColorOS version, connect the phone and run `./tools/capture-coloros-environment.ps1` in Windows PowerShell to create a host environment package without logs, account data or Bluetooth addresses.

## Build

```bash
# Release build (LSPosed API 102, compatible with 101)
./gradlew :app:assembleRelease

# Debug build (LSPosed API 101, includes protocol capture UI)
./gradlew :app:assembleDebug
```

Both builds use the same application ID and cannot be installed side by side.

## Credits

- [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) by Nshpiter (direct upstream)
- [OppoPods](https://github.com/1812z/OppoPods) by 1812z (base of HuaweiPods)
- [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) by Leaf-lsgtky (original project)
- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen (system earphone integration reference)
- [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) by melianmiko (Huawei earphone protocol reference)
- [HyperIsland](https://github.com/1812z/HyperIsland) by 1812z (About page, update and onboarding reference)
- [HyperLight](https://github.com/KiminonawaResa/HyperLight) by KiminonawaResa (material hierarchy and motion reference)
- [Miuix](https://github.com/compose-miuix-ui/miuix) by YuKongA (Compose UI components)

## License

[GPL-3.0](LICENSE)
