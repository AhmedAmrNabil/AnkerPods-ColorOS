<div align="center">

<img src="docs/public/huaweipods-logo.png" width="120" height="120" style="border-radius: 24px;" alt="HuaweiPods Icon"/>

# HuaweiPods ColorOS

**Independently packaged HuaweiPods build with ColorOS integration**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![ColorOS](https://img.shields.io/badge/ROM-ColorOS-1BA784?style=flat-square)](https://www.coloros.com/)

**English** | **[Simplified Chinese](README.md)**

[Releases](https://github.com/ByteJuno/HuaweiPods-ColorOS/releases) · [Documentation](https://huaweipods.npiter.de/) · [Support the project](docs/sponsor/index.md) · [Report an issue](https://github.com/ByteJuno/HuaweiPods-ColorOS/issues) · [Telegram updates](https://t.me/ByteJuno)

</div>

HuaweiPods ColorOS is an Android 15+ Xposed module with the independent application ID `io.github.artifical0.huaweipods.coloros`, so the original HuaweiPods LSPosed marketplace entry cannot replace it. ColorOS integration includes the official Quick Device Connect popup, the My Devices headset-detail entry, the system earphone battery channel, and an Android 16+ Live Alert / Fluid Cloud battery card.

> Starting with 1.9.0.1 this fork targets ColorOS only. The upstream Xiaomi / HyperOS integrations (system Bluetooth detail page, Super Island, Fusion Device Center, and so on) have been removed; HyperOS users should use upstream [HuaweiPods](https://github.com/Nshpiter/HuaweiPods).

> The My Devices entry supports both the ColorOS 16.1 (`com.heytap.mydevices` 17.4.15) and ColorOS 17 (17.25.10) detail pages. Images, battery summaries and recovery after updating the module with the detail sheet open were verified on OnePlus PLK110 `PLK110_17.0.0.105(CN01)` / My Devices 17.25.10 / first-generation FreeClip. Other earphones, ROMs and lid-close/reopen scenarios are outside this verification. Unknown host layouts retain their stock rendering.

The unified build supports the 20 models below in one APK. Model-specific test APKs are no longer distributed; newly added models still need ColorOS device testing.

## 1.9.3.0 (current stable release)

- Fluid Cloud (Live Alert) cards gain an ANC cycle button for models that support it, refresh in place on ANC / low-latency changes, and tapping the card opens the module's earphone popup (ColorOS 17 does not dispatch card button actions).
- Low-latency / wear-detection switches use optimistic write, readback confirmation and a 3-second rollback. Disconnect clears cached battery, ANC, low-latency and wear state, including the OPLUS battery array.
- Bluetooth settings rows, the device-details / connected-devices page, Device Space home cards and the Control Center Bluetooth tile show left / right / case battery (new optional scopes `com.android.settings` and `com.android.systemui`). Rows are rewritten only when their battery matches a Huawei earphone, and settings pages pick up battery right after a hot reload.
- Fixes tall case images, such as the FreeClip 2 Collector Edition, being cropped in notifications and Fluid Cloud (#8). The SystemUI text probe now ships in Debug builds only.
- Security: separates the MAC reply action from the request action, clamps the Fluid Cloud disconnect watermark to real uptime, and rejects a forged `STATE_OFF` while the adapter is still on.
- 753 unit tests pass and Lint has no errors. First-generation FreeClip on PLK110 / ColorOS 17.0.0.105 and OPD2515 / ColorOS 17.0.0.103 (LSPosed API 102) was checked for in-place hot reload, Fluid Cloud icons and three-part settings battery; FreeClip 2 Collector Edition and other models were not tested on device.
- VersionCode 29 retains the fixed distribution certificate. GitHub and the independent [LSPosed repository](https://github.com/Xposed-Modules-Repo/io.github.artifical0.huaweipods.coloros/releases) distribute the same stable APK. See [release notes and limitations](.github/release-notes/1.9.3.0.md).

## 1.9.2.1 (previous stable release)

- Selectively ports upstream 1.9.2: adds a separate FreeBuds 4 identity with shared 4E-family basic controls, and corrects FreeBuds 7i fixed/head-tracking spatial-audio mapping.
- FreeClip 2 settings use device readback and refresh on resume. Strict TLV parsing, device/page session isolation and confirmed low-latency policy commits prevent stale or unconfirmed writes from changing saved settings.
- Fixes generic headset images in Device Space home cards and detail sheets, preserves custom names and existing color images, and recovers an already-open dialog after module hot reload.
- Reduces fallback icon refreshes after the connection settles. Updates GitHub identity and update links to ByteJuno, bundles the current maintainer avatar, and adds a [Telegram update-channel entry](https://t.me/ByteJuno) to About.
- 629 unit tests passed; Lint has no errors, Release assembly and Android-test compilation pass. First-generation FreeClip on PLK110 / ColorOS 17.0.0.105 was checked for the official popup, app and Device Space images, battery, Live Alerts and two in-place detail hot reloads. Lid-close/reopen and other new models were not tested in this round.
- VersionCode 28 retains the fixed distribution certificate. GitHub and the independent [LSPosed repository](https://github.com/Xposed-Modules-Repo/io.github.artifical0.huaweipods.coloros/releases) distribute the same stable APK. See [release notes and limitations](.github/release-notes/1.9.2.1.md).

## 1.9.1.1

- Adds FreeBuds 6 model recognition, official image identity, captured ANC levels and a dedicated controls page; keeps FreeBuds 6 distinct from 6i.
- Adds FreeClip 2 Collector Edition product identity `000173`, separate from ordinary `000167` images. FreeClip 2 gains confirmed nod/shake call actions and noisy-environment volume-boost readback, replacing the incorrect quality-priority label. Bundled family illustrations remain the fallback until official images are available.
- Routes generic low-latency writes and 6i / SE 4 ANC EQ/wind controls through the Bluetooth host. Manual changes invalidate older auto-enable writes and callbacks; outstanding requests are bounded and expire after eight seconds.
- Preserves ColorOS lifecycle, image isolation and sender checks. No HyperOS private UI or unverified SE 4 ANC adaptive-ANC command is imported. New models and cross-process paths still require real-device testing; no device was installed or restarted in this step.
- Local verification: 577 unit tests passed, Lint had no errors, and Release assembly plus Android-test compilation succeeded. VersionCode 27 uses the existing fixed distribution certificate, not the local Debug certificate. This is published to the stable channel on GitHub and the independent LSPosed module repository; new models still need ColorOS device testing. See the [release notes and limitations](.github/release-notes/1.9.1.1.md).

## 1.9.0.1

> Versions now follow `<upstream version>.<fork revision>`; the ColorOS label lives in the app name, and APKs are named `HuaweiPods-ColorOS-<version>-release.apk`.

- Syncs upstream HuaweiPods 1.9.0: adds HUAWEI FreeBuds 7.
- **More responsive My Devices battery**: while earbuds are connected, battery is refreshed in the background every 15 seconds and immediately when the screen turns on.
- **Quick controls in My Devices**: the HuaweiPods entry gains an Off / Noise cancelling / Awareness switch or a low-latency toggle, depending on the model, synced with the earbuds.
- **System Bluetooth settings**: the device list shows left/right battery and the detail page gets an earphone-settings entry that opens HuaweiPods (requires the optional `com.oplus.wirelesssettings` scope).
- **Low battery reminder** when an earbud that is not charging drops to 20% and again at 10%; can be turned off or previewed in settings.
- **Wear state**: the earbuds' in-ear report is forwarded to the system.
- **ColorOS only**: removes the Xiaomi / HyperOS hooks, scopes, settings, and dependencies (Super Island, focus notifications, Fusion Device Center, Xiaomi Bluetooth, the Settings detail page). LSPosed scopes are now Bluetooth, My Devices, Quick Device Connect, and optionally Huawei AI Life audio.
- The About page now shows this fork's maintainer and lists the upstream project separately.

## ColorOS 17 adaptation and native integration (1.8.3-coloros.1)

- Syncs upstream HuaweiPods 1.8.3: HUAWEI FreeBuds SE 4 ANC support, saving successful low-latency writes from the module popup, and the 1.7.x–1.8.x protocol, UI, and stability updates.
- **System earphone battery**: the Bluetooth process publishes left, right, and case battery levels to ColorOS's own earphone battery channel (the one AirPods and partner earbuds use) and to the standard Android battery level, so the status bar, Bluetooth settings, and My Devices can read them.
- **Quick Device Connect popup** now covers every supported model instead of FreeClip only, with the model's bundled image when no cached official image exists.
- **My Devices (ColorOS 17)**: supports the new preference-based detail page; its Bluetooth settings entry shows HuaweiPods battery and settings and opens the module popup.
- **Quick Device Connect (ColorOS 17)**: follows the renamed card data class and close method in 17.6.x so the card is not dismissed immediately.
- Bluetooth-process hooks, Live Alerts, and system settings intents were checked against ColorOS 17 firmware and remain compatible.
- **LSPosed API 102**: the release build now uses the upstream API 102 entry. On API 102 frameworks such as Vector, installing an update hot-reloads the hooks without restarting each scope; frameworks that only provide API 101 still load the module, just without hot reload.

## ColorOS startup and Live Alerts

Enable HuaweiPods ColorOS in the system auto-launch settings. Under App info → Battery management, allow all background activity (this may increase battery usage). Grant Bluetooth and notification permissions, enable Live Alerts, and open the app once after installation. Recheck these settings after reinstalling.

After updating, restart the Bluetooth scope or reboot the phone to load the new module; the headset will briefly disconnect.

ColorOS 16 was verified on 2026-09-08 (1.8.1-coloros.3) with OnePlus PLK110, `PLK110_16.0.10.500(CN01)`, Android 16 (API 36), and HUAWEI FreeClip: module-service status and Live Alerts work; after terminating the app process and restarting Bluetooth, reconnecting the headset restores the Live Alert without opening the app. Recovery after a full phone reboot has not been tested.

Versions from 1.8.1-coloros.2 use a stable release key and support in-place updates. Earlier builds signed with the old key require reinstalling, which clears local app data; save your settings first.

## Supported models

| Model | Status | Integrated capabilities |
| --- | --- | --- |
| HUAWEI FreeBuds 3 | Stable | Battery, ANC on/off, nine-position spatial ANC dial, double-tap gestures, and system UI integration |
| HUAWEI FreeBuds 4E | Extended support | Battery, ANC/off with Light/Balanced levels and readback, left/right double-tap and press-and-hold, wear detection, fixed swipe-volume guidance, three official sound presets, and official color images |
| HUAWEI FreeBuds 4 | Extended support, not device-tested on ColorOS | Separate model and image identity with shared 4E-family battery and basic controls; device acceptance is pending |
| HUAWEI FreeBuds 5 | Extended support | Battery, ANC/off readback, Smart/Light/Balanced ANC levels, wear detection, four official sound presets, high-quality audio and low-latency auto-apply; gesture settings remain pending |
| HUAWEI FreeBuds 5i | Extended support | Battery, transparency/ANC/off readback, Smart/Light/Balanced/Deep ANC levels, left/right double-tap, wear detection, four official sound presets, high-quality audio, low-latency auto-apply, and official color images; long-press and swipe settings remain pending |
| HUAWEI FreeBuds SE 4 ANC | Extended support | Battery, transparency/ANC/off readback, Light/Balanced/Deep ANC levels, wind-noise reduction, four official sound presets, a 10-band custom EQ, low-latency auto-apply, and official color images; gesture settings remain pending |
| HUAWEI FreeBuds 6i | Extended support | Battery, transparency/ANC/off, four ANC levels, voice transparency, double/triple-tap gestures, four official sound presets, a 10-band custom EQ, low-latency auto-apply, and dedicated images |
| HUAWEI FreeBuds 6 | Extended support, not device-tested on ColorOS | Battery, official image identity, Smart/Light/Balanced ANC with readback, adaptive volume, drop alerts, wear detection, case tones, confirmed sound presets and low-latency readback; no transparency or Deep ANC |
| HUAWEI FreeBuds Pro 3 | Extended support | Battery, three-mode control and readback, four ANC levels, voice transparency, long-press/pinch/swipe gestures, and low-latency auto-apply |
| HUAWEI FreeBuds Pro 4 | Basic support | Battery and ANC/off; no verified ANC state readback or gesture settings |
| HUAWEI FreeBuds Pro 5 | Extended support | Battery, three-mode readback, four ANC levels, standard/voice/adaptive transparency, triple-tap/pinch/swipe-volume gestures, wear detection, adaptive volume, head-motion and voice controls, spatial audio, nine Yuezhang/scene/AI sound presets, a 10-band custom EQ, high-quality audio, low-latency auto-apply, dual-device connection, case-open sound, and ear-tip material |
| HUAWEI FreeBuds 7 | Extended support | Battery and official images, three ANC levels with readback, spatial audio, double/triple-tap, pinch and swipe settings, wear detection, eight sound effects, a 10-band EQ, high-quality audio, low latency, and selected smart settings; protocol verified upstream on HyperOS ([validation scope](docs/FREEBUDS7_ADAPTATION.md), Chinese), not yet on ColorOS |
| HUAWEI FreeBuds 7i | Extended support | Battery, transparency/ANC/off readback, four ANC levels, double/triple-tap, long-press and swipe-volume gestures, wear detection, head-motion control, spatial audio, four sound presets, a 10-band custom EQ, high-quality audio, low-latency auto-apply, dual-device list management, and official color images |
| HUAWEI FreeClip | Extended support | Left/right/case battery, double/triple-tap gestures, wear detection, adaptive left/right earbuds, drop reminder, four official sound presets, and low-latency auto-apply; no traditional ANC or custom EQ |
| HUAWEI FreeClip 2 | Extended support | Battery, double/triple-tap and swipe gestures, spatial audio, low-latency auto-apply, and selected wearing/audio settings; new nod/shake actions and noisy-environment volume-boost readback still need ColorOS device testing; no traditional ANC |
| HUAWEI FreeClip 2 Collector Edition | Extended support, not device-tested on ColorOS | Separate product/color image identity, shared FreeClip 2 controls including head actions and volume boost; bundled family illustration until official images are available |
| HUAWEI FreeArc | Extended support | Left/right/case battery, double/triple-tap, press-and-hold and swipe gestures, five official sound presets, a 10-band custom EQ, and official color images; no traditional ANC |
| HUAWEI Eyewear (1st generation) | Basic support | Left/right temple battery and system UI integration; no ANC |
| HUAWEI Eyewear 2 | Basic support | Left/right temple battery, double-tap/swipe gestures, and low-latency auto-apply; no ANC |
| HUAWEI Eyewear 3 | Basic support | Protocol-model identification, left/right temple battery, system eyewear classification, and official color images; no ANC |

The table lists currently integrated capabilities. Unlisted official features should not be assumed to work, and behavior can vary by earbud firmware, ColorOS version, and system component version.

## Features

- **Battery display** for earbuds, charging cases, or the left/right temples of supported eyewear.
- **Model-aware controls** for ANC, transparency, ANC levels, and gestures where verified protocol data is available.
- **ANC dial** for FreeBuds 3 spatial noise cancellation adjustment only.
- **ColorOS Quick Device Connect popup** on connection and when the case is opened, plus **Fluid Cloud** with left, right, and case battery levels on Android 16+.
- **System earphone battery channel**: the status bar, Bluetooth settings, and My Devices show left, right, and case battery levels.
- **My Devices** headset detail page shows HuaweiPods battery, a settings entry, and noise-control, low-latency and wear-detection quick controls.
- **Fluid Cloud** card offers a one-tap low-latency toggle on supported models; the **Quick Connect** card shows left / right / case battery and refreshes while visible.
- **System Bluetooth settings** show left/right battery and an earphone-settings entry; low-battery reminders.
- Independent switches for the persistent headset notification and lock-screen notifications.
- Notification taps can open the module popup, system settings, or Huawei Smart Audio; verified models can toggle low latency in the popup.
- **Manual model binding** by Bluetooth address when a device has been renamed or cannot be identified automatically.
- **First-run setup guide** and in-app GitHub release checks.
- **Standalone About page** for version details, updates, feedback, and community access, with full settings available from its top-right action.
- **Post-update scope restart prompt** after installing a newer APK.
- **Official model images** downloaded from Huawei's CDN after modern devices report an exact model and color identity over Bluetooth; manual and built-in images remain available as fallbacks.

## Requirements

- OPPO / OnePlus / realme device running ColorOS 16 / 17.
- Android 15+.
- LSPosed API version >= 101 (API 102 frameworks additionally hot-reload updates).
- A paired device listed in the support table above.

## Usage

1. Install the HuaweiPods APK and follow the first-run guide to check LSPosed and the core scopes.
2. Enable the module in LSPosed.
3. Select the scopes for your system:
   - Required: `com.android.bluetooth`, `com.heytap.mydevices`, and `com.heytap.accessory` (`com.heytap.accessory` enables the system Quick Device Connect popup).
   - Optional: `com.oplus.wirelesssettings` (earphone battery and settings entry in the system Bluetooth settings).
   - Optional: `com.android.settings` (three-part battery on the Bluetooth device-details page).
   - Optional: `com.android.systemui` (three-part battery on the Control Center Bluetooth tile and Fluid Cloud capsule).
   - Optional: `com.huawei.smartaudio` (when Huawei AI Life/Smart Audio is installed, for FreeClip 2 spatial-audio and custom-EQ synchronization).
4. Reboot the phone, or restart the scoped apps from HuaweiPods.
5. On ColorOS 16+, open **ColorOS Fluid Cloud** in HuaweiPods settings and enable **Live alerts** in the system notification settings.
6. Connect a supported device and view its capabilities in HuaweiPods. Battery also appears in My Devices and Fluid Cloud. Modern models are identified from their protocol identity; if a renamed or legacy device is not identified, select its actual model once in HuaweiPods.

The release build no longer needs to install, run, or hook HUAWEI AI Life Audio for official images. Modern models provide the model and color identity over Bluetooth; legacy models can browse the verified Huawei color catalog in the image settings and ask the user to confirm once. Failures always fall back to cached or bundled images and never guess the default color.

Toggle low latency from the module popup or detail page. Version 1.8.3 fixes a popup issue where a successful FreeBuds 6i low-latency write was not saved and the switch appeared off when reopened. Because the protocol has no reliable readback for this setting, the UI shows the last successful write that will be reapplied after reconnection rather than a live device-reported state. The effect on the earbuds still needs device testing.

## Development Notes

Internal package names, broadcast actions, configuration names, and the public app identity are unified as HuaweiPods.

## Credits

- [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) by Nshpiter — direct upstream of this adaptation.
- [OppoPods](https://github.com/1812z/OppoPods) by 1812z — the fork HuaweiPods was directly adapted from.
- [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) by Leaf-lsgtky — the original upstream OppoPods project.
- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — original HyperOS headset integration ideas.
- [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) by melianmiko — Huawei earphone protocol reference.
- [HyperIsland](https://github.com/1812z/HyperIsland) by 1812z — interaction reference for the About page, update checks, and onboarding.
- [HyperLight](https://github.com/KiminonawaResa/HyperLight) by KiminonawaResa — HyperOS material hierarchy and motion reference.
- [Miuix](https://github.com/compose-miuix-ui/miuix) by YuKongA — HyperOS-style Compose UI components.

## License

[GPL-3.0](LICENSE)
