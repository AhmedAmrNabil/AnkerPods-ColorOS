<div align="center">

<img src="docs/public/huaweipods-logo.png" width="120" height="120" alt="HuaweiPods Icon"/>

# HuaweiPods ColorOS

**让华为耳机接入 ColorOS 系统界面的 LSPosed 模块**

[![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://www.android.com/)
[![ColorOS](https://img.shields.io/badge/ROM-ColorOS-1BA784?style=flat-square)](https://www.coloros.com/)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-6F42C1?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/github/license/ByteJuno/HuaweiPods-ColorOS?style=flat-square)](LICENSE)

[下载安装](https://github.com/ByteJuno/HuaweiPods-ColorOS/releases) ·
[使用文档](https://huaweipods.npiter.de/) ·
[赞助支持](docs/sponsor/index.md) ·
[问题反馈](https://github.com/ByteJuno/HuaweiPods-ColorOS/issues) ·
[更新频道](https://t.me/ByteJuno)

**简体中文** · **[English](README_EN.md)**

</div>

HuaweiPods ColorOS 把华为耳机的电量和控制接入 ColorOS 的快速设备连接弹窗、“我的设备”、设备空间、系统蓝牙设置和流体云。模块使用独立应用 ID `io.github.artifical0.huaweipods.coloros`，不会被原版 HuaweiPods 的 LSPosed 商店条目覆盖更新。

本分支只适配 ColorOS，已移除小米 / HyperOS 集成。HyperOS 用户请使用上游 [HuaweiPods](https://github.com/Nshpiter/HuaweiPods)。

## 当前版本：1.9.3.0

- 流体云卡片支持降噪切换；在 ColorOS 17 上点击卡片会打开模块耳机弹窗。
- 蓝牙设置、设备详细信息页、设备空间卡片和控制中心蓝牙磁贴显示左耳 / 右耳 / 耳机盒三段电量。
- 低时延和佩戴检测开关增加回读确认与超时回滚；断连后清理电量和状态缓存。
- 修复竖长耳机盒图片在通知和流体云中被裁切的问题（#8）。

已在 PLK110 / ColorOS 17.0.0.105 与 OPD2515 / ColorOS 17.0.0.103（LSPosed API 102，一代 FreeClip）实测。其他型号尚未在 ColorOS 实机验证。完整变更和验证范围见 [1.9.3.0 发布说明](.github/release-notes/1.9.3.0.md)，历史版本见 [发布说明目录](.github/release-notes/)。

## 主要功能

- 在 ColorOS 快速设备连接弹窗、“我的设备”和设备空间中显示耳机图片与电量。
- 将左耳 / 右耳 / 耳机盒电量写入 ColorOS 系统耳机电量通道，供状态栏、蓝牙设置等系统界面读取。
- 在“我的设备”、模块弹窗和流体云中快速切换降噪、低时延或佩戴检测。
- 按机型提供降噪、通透、降噪等级、手势、音效和均衡器设置。
- 通过蓝牙协议识别现代型号和配色，并从华为官方 CDN 获取图片；失败时回退到缓存或内置图。
- 支持低电量提醒、按蓝牙地址手动绑定型号、应用内检查更新和更新后重启作用域提示。

## 支持型号

一个 APK 支持以下 20 个型号。表格只列出已接入的能力，实际表现还取决于耳机固件和 ColorOS 组件版本。

| 型号 | 状态 | 主要能力 |
| --- | --- | --- |
| HUAWEI FreeBuds 3 | 稳定 | 电量、降噪开关、9 档降噪空间方向、双击手势 |
| HUAWEI FreeBuds 4E | 扩展支持 | 电量、两档降噪与回读、双击 / 按住、佩戴检测、3 种音效 |
| HUAWEI FreeBuds 4 | 扩展支持，ColorOS 未实测 | 独立型号与图片，复用 4E 系列基础控制 |
| HUAWEI FreeBuds 5 | 扩展支持 | 电量、三档降噪与回读、佩戴检测、4 种音效、高清音质、低时延 |
| HUAWEI FreeBuds 5i | 扩展支持 | 电量、通透 / 降噪与回读、四档降噪、双击、佩戴检测、4 种音效、高清音质、低时延 |
| HUAWEI FreeBuds SE 4 ANC | 扩展支持 | 电量、通透 / 降噪与回读、三档降噪、防风噪、4 种音效、10 段 EQ、低时延 |
| HUAWEI FreeBuds 6i | 扩展支持 | 电量、通透 / 降噪、4 档降噪、通透人声、双击 / 三击、4 种音效、10 段 EQ、低时延 |
| HUAWEI FreeBuds 6 | 扩展支持，ColorOS 未实测 | 电量、三档降噪与回读、自适应音量、掉落提醒、佩戴检测、音效、低时延 |
| HUAWEI FreeBuds Pro 3 | 扩展支持 | 电量、三态控制与回读、4 档降噪、通透人声、长按 / 捏合 / 滑动、低时延 |
| HUAWEI FreeBuds Pro 4 | 基础支持 | 电量、降噪 / 关闭 |
| HUAWEI FreeBuds Pro 5 | 扩展支持 | 电量、三态回读、4 档降噪、多种通透模式、手势、佩戴检测、头动与语音控制、空间音频、9 种音效、10 段 EQ、高清音质、低时延、双设备连接 |
| HUAWEI FreeBuds 7 | 扩展支持，ColorOS 未实测 | 电量、三态回读与 3 档降噪、空间音频、手势、佩戴检测、8 种音效、10 段 EQ、高清音质、低时延；协议由上游在 HyperOS 验证（[说明](docs/FREEBUDS7_ADAPTATION.md)） |
| HUAWEI FreeBuds 7i | 扩展支持 | 电量、通透 / 降噪与回读、4 档降噪、手势、佩戴检测、头动控制、空间音频、4 种音效、10 段 EQ、高清音质、低时延、双设备管理 |
| HUAWEI FreeClip | 扩展支持 | 三段电量、双击 / 三击、佩戴检测、左右耳自适应、掉落提醒、4 种音效、低时延 |
| HUAWEI FreeClip 2 | 扩展支持 | 电量、双击 / 三击 / 滑动、空间音频、低时延、点头 / 摇头动作、嘈杂环境大音量 |
| HUAWEI FreeClip 2 典藏版 | 扩展支持，ColorOS 未实测 | 独立型号与配色图片，复用 FreeClip 2 控制 |
| HUAWEI FreeArc | 扩展支持 | 三段电量、双击 / 三击 / 按住 / 滑动、5 种音效、10 段 EQ |
| 华为智能眼镜（第一代） | 基础支持 | 左右镜腿电量 |
| HUAWEI Eyewear 2 | 基础支持 | 左右镜腿电量、双击 / 滑动、低时延 |
| HUAWEI Eyewear 3 | 基础支持 | 型号识别、左右镜腿电量、配色图片 |

## 使用要求

- OPPO / OnePlus / realme 设备，运行 ColorOS 16 或 17
- Android 15 及以上
- LSPosed API 101 及以上；API 102 框架支持覆盖安装后自动热重载
- 已配对表中任一型号

## 快速开始

1. 从 [GitHub Releases](https://github.com/ByteJuno/HuaweiPods-ColorOS/releases) 下载并安装 APK，按首次引导检查 LSPosed 与作用域。
2. 在 LSPosed 中启用模块，并勾选作用域：
   - 必选：`com.android.bluetooth`、`com.heytap.mydevices`、`com.heytap.accessory`
   - 可选：`com.oplus.wirelesssettings`（系统蓝牙设置电量与“耳机设置”入口）
   - 可选：`com.android.settings`（设备详细信息页三段电量）
   - 可选：`com.android.systemui`（控制中心蓝牙磁贴与流体云胶囊三段电量）
   - 可选：`com.huawei.smartaudio`（已安装华为智慧音频时同步 FreeClip 2 空间音频与音效）
3. 在 HuaweiPods 内重启相关作用域，或重启手机。
4. 使用流体云时，在 HuaweiPods 设置中打开“ColorOS 流体云”，并在系统通知设置中开启“实时提醒”。
5. 连接耳机。改名设备或旧协议型号无法自动识别时，在 HuaweiPods 中手动选择一次型号。

如果流体云只在打开应用后出现，请在系统“自启动”中开启 HuaweiPods ColorOS，并在“应用详情 → 耗电管理”中选择“完全允许后台行为”。重装后需要重新检查这些设置。

从 1.8.1-coloros.2 起使用固定签名，可直接覆盖安装；更早版本需要卸载重装。更多说明见 [快速开始](docs/guide/getting-started.md)。

## 适配新型号

新型号需要先采集华为智慧音频（`com.huawei.smartaudio`）与耳机之间的通信数据。提交前请脱敏设备地址、账号等个人信息，流程见 [华为耳机协议采集指南](docs/DEBUG_CAPTURE_GUIDE.md)。

适配新 ColorOS 版本时，可连接手机并在 Windows PowerShell 运行 `./tools/capture-coloros-environment.ps1`，生成不含日志、账号和蓝牙地址的宿主环境包。

## 构建

```bash
# 正式版（LSPosed API 102，兼容 101）
./gradlew :app:assembleRelease

# 调试版（LSPosed API 101，包含协议采集界面）
./gradlew :app:assembleDebug
```

两个版本使用相同应用 ID，不能同时安装。

## Soundcore 适配（Liberty 4 NC / A3947）

本项目已扩展支持 Soundcore 蓝牙耳机（以 Liberty 4 NC / A3947 为基准）：
- **纯 Kotlin 协议栈**：基于 [OpenSCQ30](https://github.com/OpenSCQ30/OpenSCQ30)（GPL-3.0）逆向协议，原生实现 Soundcore RFCOMM 封包帧、校验码与状态解析。
- **系统电量集成**：通过 `ColorOsSystemBatteryBridge` 写入 ColorOS `IOplusRemoteDevice` 原生电量通道，支持状态栏与系统设置显示。
- **降噪模式切换**：支持正常（关闭）/ 降噪（ANC）/ 通透模式切换，并支持在“我的设备”与通知中显示。
- **控制中心磁贴（Quick Settings Tile）**：通过 `SoundcoreTileService` 实现下拉快捷设置磁贴，显示当前降噪模式并支持一键轮换。
- **桌面微件（Jetpack Glance Widget）**：提供实时桌面微件，展示左右耳及耳机盒电量、充电状态，并提供三档降噪模式直达控制。

## 致谢

- [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) by Nshpiter（直接上游）
- [OpenSCQ30](https://github.com/OpenSCQ30/OpenSCQ30) by OpenSCQ30 贡献者（Soundcore 耳机 RFCOMM 协议逆向参考，GPL-3.0）
- [OppoPods](https://github.com/1812z/OppoPods) by 1812z（HuaweiPods 直接基于）
- [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) by Leaf-lsgtky（原始项目）
- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen（耳机系统接入参考）
- [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) by melianmiko（华为耳机协议参考）
- [HyperIsland](https://github.com/1812z/HyperIsland) by 1812z（关于页、更新与首次引导参考）
- [HyperLight](https://github.com/KiminonawaResa/HyperLight) by KiminonawaResa（材质层次与动效参考）
- [Miuix](https://github.com/compose-miuix-ui/miuix) by YuKongA（Compose 界面组件）

## 许可证

本项目基于 [GPL-3.0](LICENSE) 开源。
