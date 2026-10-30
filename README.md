<div align="center">

<img src="docs/public/huaweipods-logo.png" width="120" height="120" alt="HuaweiPods Icon"/>

# HuaweiPods ColorOS

**独立应用 ID 的 HuaweiPods ColorOS 适配版**

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

HuaweiPods ColorOS 是一个使用独立应用 ID `io.github.artifical0.huaweipods.coloros` 的 Android 15+ Xposed 模块，不会被原版 HuaweiPods 的 LSPosed 商店条目覆盖更新。ColorOS 已接入官方“快速设备连接”弹窗、“我的设备”耳机详情入口、系统耳机电量通道及 Android 16+ 流体云电量卡片。

> 从 1.9.0.1 起本分支只适配 ColorOS，已移除上游的小米 / HyperOS 集成（系统蓝牙详情页、超级岛、融合设备中心等）。HyperOS 用户请使用上游 [HuaweiPods](https://github.com/Nshpiter/HuaweiPods)。

> ColorOS 说明：“我的设备”入口已适配 ColorOS 16.1（`com.heytap.mydevices` 17.4.15）与 ColorOS 17（17.25.10）两代详情页。OnePlus PLK110 `PLK110_17.0.0.105(CN01)` / My Devices 17.25.10 / 一代 FreeClip 已实测设备空间图片、电量摘要和保持详情页打开时的热重载恢复。该结果不代表其他耳机、ROM 或关盖开盒场景均已验收；私有宿主变化时保留系统原样显示。

连接手机并启用 USB 调试后，可在 Windows PowerShell 运行 `./tools/capture-coloros-environment.ps1`，生成不含日志、账号和蓝牙地址的宿主环境包，供下一阶段适配使用。

> 下列机型共用一个 APK，不按型号单独分发。新增型号的 ColorOS 实机验证范围请分别查看。

## 1.9.3.0（深度适配轮，本地验证构建）

- 流体云卡片新增降噪切换按钮（关 → 降噪 → 通透 → 关，按机型能力裁剪），点击直连蓝牙进程；降噪 / 低时延状态变化时卡片原地刷新。
- 详情页低时延 / 佩戴开关改为「乐观写入 + 回读确认 + 超时回滚」，不再出现界面与耳机相反且永不回退；读取并使用写入成功标志。
- 模块停用 / 热重载时还原宿主行（标题、摘要、可见性、无障碍描述、点击监听）；17.x Preference 行找不到系统文本 id 时保持宿主原样。
- 断连即清电量、降噪、低时延、佩戴缓存；断开时同时清 OPLUS 六元电量数组；设备空间先还原图片再关线程池；快速连接弹窗图片标记不再串场。
- 安全：MAC 回包动作与请求动作拆分（此前同名可被伪造）、流体云断连水位钳到真实 uptime 且只前进、适配器仍开启时拒绝伪造的 `STATE_OFF`。
- 641 项单元测试通过，Lint 0 error。1.9.3.0 已覆盖安装到 PLK110 / ColorOS 17.0.0.105 / LSPosed API 102，四进程热重载 `restored=true failed=0`。
- 实机目视（耳机在连）：设备空间卡片显示华为官方图与电量，详情浮层 `左耳 100% · 右耳 100% · 耳机盒 50%`，流体云卡片 `HUAWEI FreeClip / L 100% R 100% 耳机盒 50% / 低时延已关闭 / [开启低时延]`，`card_*` 九个状态键完整落盘。
- 实测缺陷与修复：ColorOS 17 的流体云卡片把整张卡当成一个点击区，**按钮点击不会派发给 `Notification.Action`**（连点 3 次「开启低时延」都只是走 contentIntent）。现在卡片点击直接打开模块耳机弹窗（三段电量 + 低时延开关），一次点击即可改状态；顺带修掉弹窗状态请求未共享身份导致三格电量恒为 `-` 的老问题。
- versionCode 29。本检出只有 debug 签名，正式发布需用维护者固定签名重新打包；详见 [1.9.3.0 说明](.github/release-notes/1.9.3.0.md) 与 [深度适配记录](docs/coloros17-deep-adaptation-20261030.md)。

## 1.9.2.1

- 选择性移植上游 1.9.2：新增 FreeBuds 4 独立型号识别及 4E 系列基础控制复用；修正 FreeBuds 7i 固定 / 头部跟踪空间音频映射。
- FreeClip 2 设置显示耳机回读状态，返回前台自动刷新；修复严格 TLV 解析、跨设备 / 页面会话隔离及低时延回读确认后才保存自动保持策略。
- 修复设备空间首页与详情页把 FreeClip 显示成通用头戴耳机的问题，保留用户配色和别名，标准型号标题不再截断；保持详情浮层打开时更新模块也能恢复正确图片。
- 连接稳定后降低图标兜底刷新频率；更新检查、维护者姓名和链接适配 GitHub 用户名 ByteJuno，更新维护者头像，并在关于页加入 [Telegram 更新频道](https://t.me/ByteJuno)。
- 629 项单元测试通过，Lint 无错误，Release 构建及 Android 测试代码编译通过。PLK110 / ColorOS 17.0.0.105 / 一代 FreeClip 已实测官方弹窗、应用内及设备空间图片、电量、流体云和两次详情热重载；本轮未做开关盒及其他新增型号实测。
- versionCode 28，沿用固定签名；GitHub 与独立 [LSPosed 仓库](https://github.com/Xposed-Modules-Repo/io.github.artifical0.huaweipods.coloros/releases) 同步同一稳定版 APK。详见 [发布说明与验证限制](.github/release-notes/1.9.2.1.md)。

## 1.9.1.1（前一稳定版）

- 新增 FreeBuds 6 型号识别、官方图片身份、电量、三档降噪及独立设置页，不复用 6i 的通透或音效编号。
- 识别 FreeClip 2 典藏版产品 `000173`，与普通版 `000167` 官方图片身份隔离；未获取官方图时仅显示系列示意图。FreeClip 2 系列新增点头 / 摇头来电动作及“嘈杂环境大音量”设置和回读，纠正原“声音质量优先”的命名。
- 6i / SE 4 ANC 音效、防风噪及通用低时延操作复用蓝牙宿主连接；手动设置使旧自动开启和旧回调失效。共用控制请求仅允许本应用发起，最多 32 个待处理请求、8 秒超时。
- 保留 RC3 的一代 FreeClip 能力、图片、双 Profile 会话、后台唤醒和身份校验。不带回 HyperOS 私有界面，未导入缺少回读证据的 SE 4 ANC 智慧动态档位。
- 新增型号仅完成源码 / 协议层接入，尚未在本机 ColorOS 与对应耳机实测；详见 [上游 FreeBuds 6 验证范围](https://github.com/Nshpiter/HuaweiPods/blob/1f013d204c590fd3b3d5eaa687e08d9342e6fa59/docs/FREEBUDS6_ADAPTATION.md) 和 [典藏版协议依据](https://github.com/Nshpiter/HuaweiPods/blob/1f013d204c590fd3b3d5eaa687e08d9342e6fa59/docs/FREECLIP2_COLLECTOR_ADAPTATION.md)。
- 577 项单元测试通过（0 失败 / 0 错误），Lint 无错误，Release 构建及设备测试代码编译通过；本轮无设备连接，未安装或重启。发布包使用原有固定签名，versionCode 27，不使用本地 Debug 签名包分发。
- 发布于 GitHub 与独立 [LSPosed 模块仓库](https://github.com/Xposed-Modules-Repo/io.github.artifical0.huaweipods.coloros/releases) 的稳定版频道。新增型号的 ColorOS 实机验证尚未完成；欢迎反馈系统版本、耳机型号、操作步骤和脱敏日志，详见 [发布说明与验证限制](.github/release-notes/1.9.1.1.md)。

## 1.9.0.2-rc3（前一测试候选）

- 选择性移植上游 `59d5fa8`：RFCOMM 音频服务过滤、A2DP 精确回调签名与备用方法名、蓝牙别名展示和改名刷新。
- 保留 ColorOS 双 Profile 会话、唤醒、图片和流体云链路，不带回 HyperOS 私有集成；详见 [移植范围与验证边界](docs/upstream-selective-port-20261016.md)。
- 551 项单元测试通过，Lint 无错误，Release 构建及设备测试代码编译通过；本轮未做实机验证。使用原有固定发布签名，versionCode 26，作为 Pre-release 发布、不设为正式 Latest。详见 [RC3 发布说明](.github/release-notes/1.9.0.2-rc3.md)。

## 1.9.0.2-rc2（前一测试候选）

- 包含下述双 Profile 会话及有界重试修复，并补齐热重载时未确认事件的交接。
- 恢复时核对实际音频连接，已全断开则清理旧快照，未知状态不误清理；尚未读到电量的 HFP-only 会话也保留地址。
- 交接只使用基础类型，旧模块 Parcelable / ClassLoader 不传入新代；用户手动强行停止的应用不会被绑定通道绕过。
- 530 项单元测试、Lint、Release 构建及设备测试代码编译通过。新增逻辑本轮无实机验证，详见 [RC2 发布说明](.github/release-notes/1.9.0.2-rc2.md)。

## 1.9.0.2-rc1（前一测试候选）

- **媒体 / 通话连接统一处理**：同一耳机的 A2DP 或 HFP 任一仍连接就保留协议会话、控制能力和电量刷新；两个音频连接均明确断开才结束会话。未知连接状态、其他设备断开、设备切换及迟到查询不会误清理当前会话。
- **唤醒通道故障恢复**：增加最多 5 次退避重试与处理完成回执；绑定 / Binder / 应用重启失败时保留待处理状态，合并重复电量事件，隔离传输代次与模块代次。超过重试预算等待新的状态事件，不无限拉起应用。
- **流体云防残留**：延续 ColorOS 冻结兜底，蓝牙进程通过校验调用 UID 的短同步服务入口递交状态；断连清理确认完成才解除绑定。迟到事件按本次开机的时间顺序过滤。
- 纳入上一轮通知开关即时生效、佩戴检测读取失败结束等待、跨进程状态校验、旧模块版本与真实超时分别显示、ColorOS 16 缺失私有电量接口安全回退等修复。
- **验证范围**：上一轮候选在 OPPO Pad Mini / ColorOS 16 / FreeClip 已实测后台开盒出现流体云、关盖清理、图片恢复；本 RC 新增的单 Profile 场景和故障恢复逻辑通过回归测试，本轮无 Android 设备连接，尚未实机验证。详见 [测试版说明](.github/release-notes/1.9.0.2-rc1.md)。
- 使用固定发布签名，可覆盖安装已有同签名 ColorOS 版。API 101 框架更新后请重载蓝牙与快速设备连接作用域，以同步代码和图片资源；API 102 框架支持热重载。

## 1.9.0.1

> 版本号改为“上游版本.修订号”，ColorOS 标识已在软件名中；安装包为 `HuaweiPods-ColorOS-<版本>-release.apk`。

- 同步上游 HuaweiPods 1.9.0：新增 HUAWEI FreeBuds 7。
- **“我的设备”电量更灵敏**：耳机连接期间每 15 秒在后台刷新一次电量，亮屏时立即刷新，系统界面不再长时间停留在旧电量。
- **“我的设备”快捷控制**：HuaweiPods 入口下方按机型显示关闭 / 降噪 / 通透切换，以及低时延、佩戴检测开关，状态随耳机同步。
- **流体云低时延按钮**：支持低时延的型号可在流体云 / 通知卡片上一键开关低时延。
- **连接卡片电量实时刷新**：快速设备连接卡片显示左耳 / 右耳 / 耳机盒电量与充电标记，卡片显示期间电量变化会同步更新。
- **系统蓝牙设置**：设备列表显示左右耳电量，详情页出现“耳机设置”并打开 HuaweiPods（需勾选可选作用域 `com.oplus.wirelesssettings`）。
- **耳机低电量提醒**：耳机未充电时电量降到 20% 和 10% 各提醒一次，可在设置中关闭或发送测试提醒。
- **佩戴状态**：读取耳机上报的入耳状态并同步给系统。
- **只保留 ColorOS 适配**：移除小米 / HyperOS 相关的 Hook、作用域、设置项与依赖（超级岛、焦点通知、融合设备中心、小米蓝牙、系统设置详情页），LSPosed 作用域只剩蓝牙、我的设备、快速设备连接与可选的智慧音频。
- 关于页改为显示本分支维护者，并单独列出上游项目。

## ColorOS 17 适配与原生系统集成（1.8.3-coloros.1）

- 同步上游 HuaweiPods 1.8.3：新增 HUAWEI FreeBuds SE 4 ANC，修复模块弹窗中低时延设置写入成功后未保存，并包含 1.7.x～1.8.x 的协议、界面与稳定性更新。
- **系统耳机电量**：蓝牙进程把华为耳机的左右耳 / 充电盒电量写入 ColorOS 自有的耳机电量通道（与 AirPods、三方耳机相同），并同步 Android 标准电量。状态栏耳机电量、蓝牙设置和“我的设备”等系统界面可以直接读取。
- **快速设备连接弹窗**：官方弹窗从仅 FreeClip 扩展到所有已支持型号；没有缓存官方图时使用对应机型的内置图。
- **我的设备（ColorOS 17）**：适配改为 Preference 页面的新版详情页，原“蓝牙设置”入口改为显示 HuaweiPods 电量与设置，点击打开模块弹窗。
- **快速设备连接（ColorOS 17）**：适配 17.6.x 改名后的卡片数据类与关闭方法，卡片不会被系统立即自动收起。
- 蓝牙进程 Hook、流体云实时通知、系统设置入口已对照 ColorOS 17 固件核对，签名与行为保持兼容。
- **LSPosed API 102**：正式版改用上游的 API 102 入口，在 API 102 框架（如 Vector）上覆盖安装新版后自动热重载，无需逐个重启作用域；只支持 API 101 的框架仍可正常加载，只是没有热重载。

## ColorOS 流体云、自启动与模块状态

- 在系统“自启动”页面开启 **HuaweiPods ColorOS**；在“应用详情 → 耗电管理”选择 **完全允许后台行为**。这可能增加后台耗电。重装后需重新检查这些设置。
- 允许蓝牙与通知权限，并开启系统通知设置中的“实时通知 / 流体云”。首次安装后打开应用一次，再连接耳机。
- 更新后重启蓝牙作用域，或重启手机，让蓝牙进程加载新版模块；期间耳机会短暂断连。
- 若流体云只在手动打开应用后恢复，请先检查上述自启动和后台权限。
- **ColorOS 16 实测范围（2026-09-08，1.8.1-coloros.3）**：OnePlus PLK110、`PLK110_16.0.10.500(CN01)`、Android 16（API 36）、HUAWEI FreeClip。已确认模块服务连接正常、流体云可见；模拟应用进程被回收并重启蓝牙后，耳机重连可自动恢复流体云，无需打开应用。尚未验证整机重启后的自动恢复。
- 从 **1.8.1-coloros.2** 起使用固定发布签名，之后的版本可直接覆盖安装。更早的旧签名版本首次迁移需要卸载重装，会清除本地数据，请先保存设置。

## 支持型号

| 型号 | 状态 | 当前能力 |
| --- | --- | --- |
| HUAWEI FreeBuds 3 | 稳定 | 电量、降噪开关、9 档降噪空间方向、双击手势与系统界面集成 |
| HUAWEI FreeBuds 4E | 扩展支持 | 电量、降噪 / 关闭与轻度 / 均衡两档降噪、状态回读、左右耳双击与按住、佩戴检测、轻滑音量说明、3 种官方音效和官方配色图片 |
| HUAWEI FreeBuds 4 | 扩展支持，ColorOS 未实测 | 独立型号及图片身份；复用 4E 系列基础控制与电量路径，尚未完成该型号实机验收 |
| HUAWEI FreeBuds 5 | 扩展支持 | 电量、降噪 / 关闭与状态回读、智慧动态 / 轻度 / 均衡三档降噪、佩戴检测、4 种官方音效、高清音质与低时延自动保持；手势设置待补充 |
| HUAWEI FreeBuds 5i | 扩展支持 | 电量、通透 / 降噪 / 关闭与状态回读、智慧动态 / 轻度 / 均衡 / 深度四档降噪、左右耳双击、佩戴检测、4 种官方音效、高清音质、低时延自动保持与官方配色图片；长按和滑动设置待补充 |
| HUAWEI FreeBuds SE 4 ANC | 扩展支持 | 电量、通透 / 降噪 / 关闭与状态回读、轻度 / 均衡 / 深度三档降噪、防风噪、4 种官方音效、10 段自定义均衡器、低时延自动保持与官方配色图片；手势设置待补充 |
| HUAWEI FreeBuds 6i | 扩展支持 | 电量、通透 / 降噪 / 关闭、4 档降噪、通透人声模式、双击 / 三击手势、4 种官方音效、10 段自定义均衡器、低时延自动保持与专属图片 |
| HUAWEI FreeBuds 6 | 扩展支持，ColorOS 未实测 | 电量、官方图片身份、智慧动态 / 轻度 / 均衡三档降噪与回读，自适应音量、掉落提醒、佩戴检测、盒提示音、已确认音效及低时延回读；不提供通透或深度档 |
| HUAWEI FreeBuds Pro 3 | 扩展支持 | 电量、三态控制与状态回读、4 档降噪、通透人声模式、长按 / 捏合 / 滑动手势与低时延自动保持 |
| HUAWEI FreeBuds Pro 4 | 基础支持 | 电量、降噪 / 关闭两态控制；暂不支持降噪状态回读与手势设置 |
| HUAWEI FreeBuds Pro 5 | 扩展支持 | 电量、三态回读、4 档降噪、通透 / 透传人声 / 智慧动态透传、三击 / 按捏 / 轻滑音量、佩戴检测、自适应音量、头动与语音控制、空间音频、9 种悦彰 / 场景 / AI 音效、10 段自定义均衡器、高清音质、低时延自动保持、双设备连接、开盒提示音与耳塞材质 |
| HUAWEI FreeBuds 7 | 扩展支持 | 电量与官方图片、三态回读与 3 档降噪、空间音频、双击/三击/捏合/滑动设置、佩戴检测、8 种音效与 10 段均衡器、高清音质、低时延及部分智慧设置；协议由上游在 HyperOS 上实机验证（[验证范围](docs/FREEBUDS7_ADAPTATION.md)），ColorOS 上尚待验证 |
| HUAWEI FreeBuds 7i | 扩展支持 | 电量、通透 / 降噪 / 关闭与状态回读、4 档降噪、双击 / 三击 / 长按 / 滑动音量、佩戴检测、头动控制、空间音频、4 种官方音效、10 段自定义均衡器、高清音质、低时延自动保持、双设备列表管理与官方配色图片 |
| HUAWEI FreeClip | 扩展支持 | 左右耳与充电盒电量、双击 / 三击手势、佩戴检测、左右耳机自适应、掉落提醒、4 种官方音效和低时延自动保持；不支持传统主动降噪与自定义 EQ |
| HUAWEI FreeClip 2 | 扩展支持 | 电量、双击 / 三击 / 滑动手势、空间音频、低时延自动保持及部分佩戴和音频设置；不支持传统主动降噪；新增点头 / 摇头动作、大音量开关和回读（新增项 ColorOS 未实测） |
| HUAWEI FreeClip 2 典藏版 | 扩展支持，ColorOS 未实测 | 独立产品身份 / 官方配色图片；复用 FreeClip 2 控制协议，包含头动动作和大音量设置，未获取官方图时为系列示意图 |
| HUAWEI FreeArc | 扩展支持 | 左右耳与充电盒电量、双击 / 三击 / 按住 / 滑动手势、5 种官方音效、10 段自定义均衡器与官方配色图片；不支持传统主动降噪 |
| 华为智能眼镜（第一代） | 基础支持 | 左右镜腿电量与系统界面集成；不提供主动降噪 |
| HUAWEI Eyewear 2 | 基础支持 | 左右镜腿电量、双击 / 滑动手势与低时延自动保持；不提供主动降噪 |
| HUAWEI Eyewear 3 | 基础支持 | 协议型号识别、左右镜腿电量、系统眼镜分类与官方配色图片；不提供主动降噪 |

表格仅列出当前已接入的能力；未列出的官方功能不代表已经支持，具体表现还会受到耳机固件和 ColorOS 系统组件版本影响。

如需适配其他华为耳机，可提交 Issue 或提供脱敏抓包数据。

## 主要功能

- 接入 ColorOS 官方“快速设备连接”弹窗（连接与开盖）；Android 16+ 可在流体云显示左右耳与充电盒电量
- 左右耳 / 充电盒电量写入 ColorOS 系统耳机电量通道，状态栏、蓝牙设置和“我的设备”可直接显示
- “我的设备”耳机详情页显示 HuaweiPods 电量与设置入口，并可直接切换降噪或低时延
- 系统蓝牙设置显示左右耳电量与“耳机设置”入口；耳机低电量时提醒
- 可分别控制常驻耳机通知与锁屏通知
- 通知可打开模块弹窗、系统设置或华为智慧音频；模块弹窗可直接切换低时延
- 显示左右耳、充电盒或眼镜左右镜腿电量
- 按机型提供主动降噪、通透模式、降噪等级和手势设置
- 直接从蓝牙协议读取现代型号的精确资源身份，并从华为官方 CDN 校验、缓存对应机型与配色图片
- 耳机名称被修改或无法自动识别时，可按蓝牙地址手动选择型号
- 首次启动提供设置引导，并可在应用内检查 GitHub 更新
- 独立的关于页面集中展示版本、更新、反馈与社区入口，完整设置收进右上角入口
- 覆盖安装新版本后提示重启作用域，无需直接重启手机

## 使用要求

- OPPO / OnePlus / realme ColorOS 设备（ColorOS 16 / 17）
- Android 15 及以上
- LSPosed API 101 及以上（API 102 框架额外支持覆盖安装后自动热重载）
- 表中任一已集成型号

## 快速开始

1. 从 [GitHub Releases](https://github.com/ByteJuno/HuaweiPods-ColorOS/releases) 下载并安装 APK；首次打开可按引导检查 LSPosed 与核心作用域。
2. 在 LSPosed 中启用 HuaweiPods。
3. 按系统勾选作用域：

   - 必选：`com.android.bluetooth`、`com.heytap.mydevices`、`com.heytap.accessory`（`com.heytap.accessory` 用于系统“快速设备连接”官方弹窗）
   - 可选：`com.oplus.wirelesssettings`（系统蓝牙设置中的耳机电量与“耳机设置”入口）
   - 可选：`com.huawei.smartaudio`（已安装华为智慧音频时，用于 FreeClip 2 空间音频与自定义音效同步）

4. 在 HuaweiPods 内重启相关作用域，或重启手机。
5. ColorOS 16+ 首次使用流体云时，在 HuaweiPods 设置中点击“ColorOS 流体云”，并在系统通知设置开启“实时提醒”。
6. 连接设备后，即可在 HuaweiPods、“我的设备”与流体云中查看已接入能力。现代型号会直接读取设备标识；改名设备或旧协议型号识别失败时，再在 HuaweiPods 中选择一次真实型号。

正式版不需要安装或运行华为智慧音频来获取图片：现代型号由蓝牙协议直接确认机型与配色，旧协议型号可在图片设置中检索华为官方配色并手动确认。下载失败时始终回退到已有缓存或内置图，不会猜测默认配色。

低时延请从模块弹窗或详情页操作。1.8.3 修复了 FreeBuds 6i 等机型在弹窗内写入成功后设置未保存、再次打开又显示关闭的问题。现有协议没有可靠的低时延状态回读，界面显示最近一次成功写入且会在重连时恢复的设置，不代表耳机主动上报的实时状态；耳机端效果仍需实机验证。

更完整的安装说明见 [快速开始](docs/guide/getting-started.md)。

## 适配新型号

未支持型号需要先采集华为智慧音频（`com.huawei.smartaudio`）与耳机之间的真实通信数据。通用协议采集版按设备、噪声与音量、手势、音质、连接和充电盒等类别引导单变量操作；只负责记录，不代表该型号已经适配。

请勿直接公开包含设备地址、账号或其他个人信息的原始采集文件。提交前请检查并脱敏，完整流程见 [华为耳机协议采集指南](docs/DEBUG_CAPTURE_GUIDE.md)。

可复现问题请提交至 [GitHub Issues](https://github.com/ByteJuno/HuaweiPods-ColorOS/issues)。

## 构建

```bash
# 正式版（按 LSPosed API 102 构建，兼容 101）
./gradlew :app:assembleRelease

# 协议采集与调试版（LSPosed API 101，包含采集界面）
./gradlew :app:assembleDebug
```

`release` 的图片识别不依赖华为智慧音频；仅在智慧音频本来就在运行时，注入一个 FreeClip 2 空间音频同步桥，不会主动启动或保活它。`release` 按 API 102 构建并携带自动热重载元数据，运行时兼容 API 101 框架；`debug` 固定使用 API 101，另外包含面向适配工作的智慧音频协议采集功能。两者使用相同应用 ID，无法同时安装。

## 致谢

- [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) by Nshpiter（本适配版的直接上游）
- [OppoPods](https://github.com/1812z/OppoPods) by 1812z（HuaweiPods 直接基于）
- [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) by Leaf-lsgtky（上游原始项目）
- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen（HyperOS 耳机接入参考）
- [OpenFreebuds](https://github.com/melianmiko/OpenFreebuds) by melianmiko（华为耳机协议参考）
- [HyperIsland](https://github.com/1812z/HyperIsland) by 1812z（关于页、更新与首次引导交互参考）
- [HyperLight](https://github.com/KiminonawaResa/HyperLight) by KiminonawaResa（HyperOS 材质层次与动效参考）
- [Miuix](https://github.com/compose-miuix-ui/miuix) by YuKongA（HyperOS 风格 Compose 界面组件）

## 许可证

本项目基于 [GPL-3.0](LICENSE) 开源。
