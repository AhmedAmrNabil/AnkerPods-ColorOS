# ColorOS 17 深度适配审计报告（2026-10-30）

对象：`ByteJuno/HuaweiPods-ColorOS`（1.9.3.0 / versionCode 29）
实机：OnePlus PLK110（`PLK110_17.0.0.105(CN01)`，API 37，ColorOS 17），LSPosed 7927 / libxposed API 102，adbd `127.0.0.1:18583`（root via `su -c`）
配套文档：[深度适配设计](coloros17-deep-adaptation-20261030.md)、[Hook 签名矩阵](coloros17-hook-signature-matrix-20261030.md)、[钩子签名核对表](coloros17-signature-audit-20261030.md)

## 1. 结论摘要

| 能力 | 现状 | 落点 |
| --- | --- | --- |
| 快速设备连接弹窗（`com.heytap.accessory` / PanTaConnect） | 可用：官方弹窗显示 FreeClip 整机图 + 三段电量，图与文案正确 | `ColorOsAccessoryFrameworkHook` |
| 我的设备 / 设备空间（`com.heytap.mydevices`） | 可用：卡片与详情页图替换成功、注入 `HuaweiPods` 行可进弹窗；三段电量显示未启用 | `ColorOsMyDevicesHook`、`ColorOsDeviceSpaceHook` |
| 系统耳机电量通道（`com.android.bluetooth`） | 可用：RFCOMM 电量解析、通知与状态广播；跨进程请求必须共享身份 | `HuaweiHfpController`、`MiuiStrongToastUtil` |
| 流体云 / 实时通知卡片 | 可用（点击链路已修）：点卡片一次直达耳机弹窗；卡片内按钮仍不可点（平台限制） | `ColorOsLiveAlertReceiver` |
| 蓝牙设置（`com.oplus.wirelesssettings`） | 可用：设备详情入口与 OEM 判定钩子生效 | `ColorOsWirelessSettingsHook` |

本轮共发现 **9 个缺陷 / 缺口**，修复 **7 个**，2 个受平台或固件限制保留为已知限制（见 §5）。

## 2. 方法

1. **静态测绘**：从实机拉取 5 个宿主 APK（Bluetooth / MyDevices / AccessoryFramework / SystemUI / WirelessSettings），
   用 `dexdump -d` 反汇编 + 自写脚本（`.local/recon/tools/`）生成类/方法/字段表与 smali 级反汇编。
2. **签名核对**：把模块里所有反射目标（`findClass` / `getMethod` / `getDeclaredField`）逐条与固件 dump 对照，
   结论见 [钩子签名核对表](coloros17-signature-audit-20261030.md)：10 个宿主类全部存在、0 断链
   （核对脚本 `tools/audit-signatures.ps1` 随仓库分发，可重跑）。
3. **实机验证**：`adb` 触发 + `logcat` / LSPosed modules 日志 + `dumpsys notification` + 截图，
   每条结论都留了可复现命令与截图（`.local/recon/`，已 gitignore）。
4. **构建门禁**：`assembleRelease` + `testDebugUnitTest` + `lintDebug` 全绿才认为改动可交付。

## 3. 关键发现

### 3.1 跨进程广播必须共享身份，否则 fail-closed（根因级）

宿主侧接收器统一用 `BroadcastReceiver.getSentFromPackage()` 做校验，而**裸 `sendBroadcast` 的这个值是 `null`**。
所以任何"模块 → 宿主"的控制广播都必须走 `BroadcastOptions.setShareIdentityEnabled(true)`
（模块里是 `Context.sendIdentitySharingBroadcast`）。现象典型是"功能看着钩上了，实际恒为 `-`/无反应"，
日志里是 `Rejected untrusted broadcast … sender=null`。

### 3.2 流体云卡片的按钮不可点，点卡片才是有效路径

ColorOS 17 的实时通知模板把整张卡（含按钮区）当作一个 `contentIntent` 点击区，
`Notification.Action` 点击不会派发（同一台机器上 Telegram 的普通通知按钮正常，被提升成实时活动的就不行）。
因此把卡片 `contentIntent` 改成直达 `PopupActivity`：一次点击即到可改状态的弹窗。

### 3.3 设备空间卡片：`MULTI` 会换布局并丢掉注入的耳机图（dexdump + aapt2 实证）

`BlueToothCardView` 的根布局是 `layout/devicecard_control_preference_bt`，字段与资源 id 已逐一还原
（`g` = 6 个 `bt_*_imageView` 里被 `mDeviceSecondaryType` 选中的那一个，`n` = `container_battery_and_hint`，
`o` = `stub_airpods_multiline_battery` 展开出来的多段容器）。
`k()` 在 `MULTI` 分支里 `0049: g = null`、`0051: return`，并且 `j()` 会把 6 个设备图槽位全部 `GONE`，
卡片在该模式下改画多段块里的 `id/airpods_image` —— 那是一张 **AirPods 产品渲染**，这就是"图不对"的根因。
多段块布局是 `layout/include_bluetooth_card_airpods_battery_block`（主段 / 盒 / 左 / 右四行 + 产品图），
模块现在按名字取 `airpods_image` 贴我们自己的图，并按行容器填四段数字。
三段数值的 DTO 消费者全固件只有 quickapp 首页卡模板（`aa.s7` / `aa.La0`），
AirPods 三格块的数据由 `plugin.airpods.core.a` 从字节数组生成 `BatteryInfo`。
指令级证据与完整 id 表见 [Hook 签名矩阵](coloros17-hook-signature-matrix-20261030.md) §2.1 / §2.2。

### 3.4 导出的通知接收器不校验发送方（外部可利用）

`MiBluetoothToastHook` 的通知接收器是 `RECEIVER_EXPORTED`，处理 `UPDATE_PODS_NOTIFICATION` /
`CANCEL_PODS_NOTIFICATION` 时完全不看发送方：任意应用构造 `BatteryParams` + `BluetoothDevice` 广播，
即可在通知栏伪造耳机卡片或刷掉真实通知。本轮已按 Action 域 fail-closed 收口（§4.4）。

### 3.5 关机广播只对 `STATE_OFF` 做过真实性核对

`ACTION_STATE_CHANGED` 属系统广播，任何持 `BLUETOOTH_CONNECT` 的应用都能发。
原逻辑只对 `STATE_OFF` 核对真实适配器状态，`STATE_TURNING_OFF` 直接照信 → 伪造一条即可收掉流体云卡片。
本轮改为两种状态都必须与真实适配器状态一致，取不到真实状态则不取消（§4.5）。

## 4. 本轮修复清单

| # | 缺陷 | 修复 | 证据 |
| --- | --- | --- | --- |
| 4.1 | 流体云卡片点击无反应 / 只落到内容页 | `contentIntent` 改指 `PopupActivity`，解析不到设备时退回模块详情页 | `START u0 {act=…show_pods_ui … mRealCallingUid=10247}`，截图 `verify-26` |
| 4.2 | 弹窗三格电量恒为 `-`（4 处裸 `sendBroadcast` 被拒） | `PopupActivity` 全部改 `sendIdentitySharingBroadcast` | 修复后弹窗显示 `左 100% · 右 100% · 耳机盒 50%`，日志无 `Rejected untrusted broadcast` |
| 4.3 | 电量刷新把已替换的耳机图刷回宿主原生图 | 电量链路与图片链路解耦（`refreshVisibleBattery`），`routeFor()` 名字降级为辅助信息 | 截图 `verify-33/34` |
| 4.4 | 通知接收器不校验发送方（外部可伪造通知） | 入口 fail-closed + 两个 action 改归 `APP_CONTROL` + 发送方统一共享身份 | 单测 `podsNotificationCommandsAreAppControlAndFailClosed` |
| 4.5 | `STATE_TURNING_OFF` 被无条件信任 | `shouldCancelForAdapterState(claimed, actual)`，取不到真实状态不取消 | 单测 `ColorOsLiveAlertAdapterStateTest`（4 例） |
| 4.6 | 设备空间三段电量显示会丢图 | `heroImage()`：`g == null` 时先按名字精确取多段块里的 `id/airpods_image`，取不到才退回"面积最大的可见 `ImageView`" | 布局 id 全量实证，§3.3 |
| 4.7 | 三段电量数据通路缺失 | `ColorOsPodBatteryStore`（快照 + 选段纯逻辑）、进程内电量接收者、DTO 电池槽写入 | 单测 `ColorOsPodBatteryStoreTest`（12 例） |
| 4.8 | 多段电量块没人填数（`MULTI` 下四格空白），且充电/低电图标名是拼错的 | 布局级实证后新增 `ColorOsAirpodsBatteryBlock`（`Slot` 表：行容器 / 数字 / 进度条 / 充电 / 低电五项逐一显式声明）+ `applyMultiBatteryBlock()`（按行填数、缺段整行隐藏、展开 ViewStub） | 单测 `ColorOsAirpodsBatteryBlockTest`（8 例，含"槽位 id 必须与固件布局逐字一致"）；开关仍 `false` |
| 4.9 | `module resources unavailable` 只剩一句日志，查不出原因 | `ModuleResourceResolver.resources()` 改成三条路径各自尝试、各自记录异常（同路径只报一次），最后兜底 `getResourcesForApplication` | 代码；本次改动后重装即可在日志里看到真实异常类型 |
| 4.10 | 单耳离线就整卡没有电量（FreeClip 一只耳在盒里是常态） | `ColorOsBatteryTriple` 三段全部改为可空、只要求"至少一段在线"；缺的 DTO 槽位不写（不塞 0），`isSupportMutiBattery` 改为"≥2 段"才为真，单段时不强切 `MULTI` 布局 | 单测 `triple keeps whatever part is online`、`isMulti needs at least two parts` |
| 4.11 | 三段电量的开关是编译期常量，验证一种组合就要重装 + 重启宿主 | 改成运行时 `Settings.Global/huaweipods_multi_battery` 位掩码（bit0 = DTO，bit1 = 卡片），3 秒 TTL 缓存，读取失败保留上次值、默认全关 | 单测 `ColorOsBatteryFlagsTest`（5 例）；命令见 §7 |

## 5. 未修 / 已知限制

1. **流体云卡片内的按钮点不动** —— 平台模板限制（整卡一个 `contentIntent`），已用"点卡片开弹窗"绕开，
   不做 hack（伪造 `PendingIntent` 会破坏平台校验）。
2. **设备空间首页卡片的"三段电量"没有注入面** —— 真机验证后结论变了，详见 §8：首页卡片是
   `HomeCardView` + `BatteryLottieView`（Lottie 电量），既不读 `h()` 返回的 DTO 电池槽，
   也不走 `BlueToothCardView#k()/setBatteryPower`。§4.7/§4.8 的数据面与四格块逻辑保留（蓝牙设置侧卡片仍可能用到），
   但要让首页显示三段，必须新做 `HomeCardView` 视图级注入，属下一轮独立工程。
3. **原生三段降噪（`TRANSPORT_HFP_AT` + `+OBUDS`）** —— 需要真机反复试 AT 帧，本轮未动。
4. **状态栏耳机电池图标** —— ColorOS 17 移除了蓝牙 UID 的 STATUS_BAR 权限，图标由 SystemUI 自绘，无挂点。
5. **控制中心设备卡片条 / 流体云种子卡** —— 实证无第三方入口：种子卡通道是 ContentProvider
   （`com.oplus.pantanal.seedling.serviceLayer.BaseSeedlingCardStrategyProvider`），
   `checkCallPermission()` 只放行 system uid / SystemUI 自身 uid / 系统应用 / 包名白名单，
   普通应用申请任何权限都进不去（详见[钩子签名核对表](coloros17-signature-audit-20261030.md) §4）。
   要做得 hook 进 SystemUI（模块目前没有这个 scope），属独立工程。
6. **覆盖安装后宿主进程复用旧资源句柄** —— 日志 `skip notification: module resources unavailable`，
   需重启宿主进程才恢复；属加固前的既有现象。本轮把异常从"被 `runCatching` 吞掉"改成逐路径记录
   （§4.9），下次接上手机重装一次就能看到真正的异常类型，再决定是清缓存还是重启宿主。
7. **通知仍能刷新（真机）** —— 信任校验加固后未在真机复核，待手机接回来第一时间验证。

## 6. 验证与构建证据

- 单测：**670 通过 / 0 失败**（`app/build/test-results/testDebugUnitTest`，108 个 suite）
- Lint：**0 error**（235 warning/hint，`app/build/reports/lint-results-debug.xml`）
- Release：`:app:assembleRelease` 通过，当前 APK SHA-256
  `bc1c8e2c8488bcc23a9122693d3f8ac8eb3bffd88956d4f3b27c1ac8ca5fb784`
- 实机（PLK110 / ColorOS 17，2026-10-30 20:30–21:45）：`pm install -r -d` 覆盖安装成功，
  宿主重启后钩子全部装载；关键路径截图见 `.local/recon/verify2-*.png`
- 相关提交：`ddd028c`（§8.1–8.4）、`cd4cc51`（§8.5）

## 7. 复现命令

```powershell
# 构建门禁
cd H:\HuaweiPods-ColorOS; $env:JAVA_HOME='D:\DevTools\Java\jdk-21'
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintDebug --console=plain

# 安装 + 重启宿主以重载钩子
adb -s 127.0.0.1:18583 install -r app\build\outputs\apk\release\app-release.apk
adb -s 127.0.0.1:18583 shell "su -c 'am force-stop com.heytap.mydevices'"
adb -s 127.0.0.1:18583 shell "am start -n com.heytap.mydevices/com.oplus.mydevices.deviceui.devicecard.DeviceCardHomeActivity"

# 触发官方连接弹窗（关/开蓝牙让耳机回连）
adb -s 127.0.0.1:18583 shell "svc bluetooth disable"; Start-Sleep 3
adb -s 127.0.0.1:18583 shell "svc bluetooth enable"
adb -s 127.0.0.1:18583 shell "dumpsys window | grep -m1 mCurrentFocus"   # 期望 DialogActivity

# 三段电量的两个开关：运行时切，不用重装（默认 0 = 全关）
adb -s 127.0.0.1:18583 shell settings put global huaweipods_multi_battery 1   # 只写 DTO
adb -s 127.0.0.1:18583 shell settings put global huaweipods_multi_battery 3   # DTO + 卡片 MULTI
adb -s 127.0.0.1:18583 shell settings put global huaweipods_multi_battery 0   # 回滚

# 看模块日志
adb -s 127.0.0.1:18583 shell 'su -c "tail -n 200 /data/adb/lspd/log/modules_*.log"'
```

## 8. 真机验证（2026-10-30 21:00，OnePlus 15 / ColorOS 17，FreeClip 已连接）

### 8.1 数据面：通了

`settings put global huaweipods_multi_battery 3` 后重启宿主，两个进程都按秒级收到三段数据：

```
(com.heytap.mydevices:cards) Device Space battery event 30:A9:98:CB:C4:54 left=100/true right=100/true case=44/true
(com.heytap.mydevices)       Device Space battery event 30:A9:98:CB:C4:54 left=100/true right=100/true case=44/true
```

链路成立：`HuaweiHfpController#sendBattery` → 共享身份广播 → 宿主 `Application.onCreate` 注册的动态接收者
→ `ColorOsPodBatteryStore`。注意接收者 **必须在广播之后** 才有数据，卡片首次构建时仓库必然为空，
所以 `applyCardBattery` 现在保留 DTO 弱引用，电量到了直接补写同一个对象（`reapplyLastCardBattery()`）。

### 8.2 展示面：首页卡片根本不碰 `BlueToothCardView`

给 `h()` 加落点日志后，一次首页渲染只有不相关地址进来，FreeClip 地址一条都没有：

```
Device Space h() skipped: unsupported route address=B4:E7:B3:05:57:7E
Device Space h() skipped: unsupported route address=
```

一次性 dump 窗口里的真实视图类：

```
Device Space view dump cards=0 classes=[
  com.oplus.mydevices.deviceui.devicecard.view.HomeCardRecyclerView,
  com.oplus.mydevices.deviceui.devicecard.view.homecard.HomeCardView,
  com.oplus.mydevices.quickapp.homecard.view.IndicatorButtonView,
  com.oplus.mydevices.quickapp.homecard.view.IndicatorLightView,
  com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView]
```

**设备空间首页卡片是 `HomeCardView` + `BatteryLottieView`（Lottie 电量动画），不是审计阶段认定的
`BlueToothCardView`**。`BlueToothCardView`（`layout/devicecard_control_preference_bt`）用在蓝牙设置侧，
首页那张卡既不读 DTO 电池槽，也不调 `k()`/`setBatteryPower`（日志里 `cards=0`、`card mode` 一条没有），
所以 §4.7/§4.8 的数据面与四格块在这张卡上不会被触发。

### 8.3 三段电量目前靠哪条路显示

首页卡片仍是单值（截图 `verify2-04-multi-card.png` 里 `100`），三段数字由既有的
`ColorOsSystemBatteryBridge`（模块 → 系统耳机电量通道）驱动，弹窗/详情显示
「左耳 100% · 右耳 100% · 耳机盒 45%」（`verify2-03-devicespace-connected.png`）。

### 8.4 处置

- 两个开关保持默认全关；新增 App 内开关（设置 → ColorOS 分区「设备空间三段电量（实验）」），
  与 `Settings.Global` 覆盖并存（adb 非 0 时优先）。
- 首页要显示三段，需要新做 `HomeCardView`/`BatteryLottieView` 视图级注入（自己挂一行三段文本/图标），
  不是打开现有开关能得到的；下一轮独立做。
- 诊断日志（`Device Space battery event` / `DTO gate` / `card mode` / `view dump`）保留，每进程各一条。

### 8.5 第二轮实测补充（21:35–21:45）

- **`BlueToothCardView` 只在弹窗/详情窗口里出现**：打开弹窗那一刻的日志是
  `card mode enabled=false modeClass=true address=30:A9:98:CB:C4:54 storeHit=true isMulti=true`、
  `Existing detail image recovery roots=3 cards=1` —— 视图、地址、三段数据、`isMulti` 全部对得上，
  说明 §4.7/§4.8 那条路对**弹窗里的蓝牙卡**是通的，只是首页不用它。
- **三段数字在弹窗里一直是对的，且不是本轮代码的功劳**：`svc bluetooth disable/enable` 重新触发
  `com.heytap.accessory/...DialogActivity` 后显示「左耳 100% · 右耳 100% · 耳机盒 93% ⚡」，
  和 `hueweipods_multi_battery=0/3` 无关 —— 那是既有的 `ColorOsSystemBatteryBridge` 在供数。
- **`BatteryLottieView` 构造钩子在本机不触发**：两参和三参构造都挂了，日志里一条 `card tree` 都没有，
  说明首页卡片的 Lottie 电量不是在这两个构造点建出来的（或建在别的进程/更早），
  所以"读结构再插入"这条路本轮没走通，探针保留但不指望它。
- **通知链路在信任加固后是好的**：日志 `notification receiver registered` →
  `Cached notification restored device=…` → 断开时 `ColorOS notification cleared on ACL disconnect`，
  发布、恢复、清理三段都走到了。
- **`module resources unavailable` 本轮没复现**：整套新日志里一条都没有，模块资源在干净进程里可用；
  之前那次是"覆盖安装 + 宿主进程复用旧句柄"导致的，重启宿主即恢复（§5 第 6 条）。

## 9. 首页卡片三段电量：视图级注入（已实现并真机验证）

§8 的结论是"首页卡片不走 DTO、不建 `BlueToothCardView`、Lottie 电量也不是能挂的构造点"。
这一轮换打法：不去改宿主数据，**自己往卡片里补一行文本**。

### 9.1 卡片真实结构（`Device Space card tree` 日志）

把探针改成挂在 `BatteryLottieView` 的**父容器**上之后（构造钩子始终不触发，改成在电量遍历时按类名找实例），
一次就拿到了首页卡片内部结构：

```
L0 LinearLayout#singleBatteryContainer: BatteryLottieView#batteryView  TextView#batteryText="73"
L1 LinearLayout#textContainer: TextView#deviceName="OPPO Pad Mini"
                               LinearLayout#statusContainer(2)
                               LinearLayout#singleBatteryContainer(2)
                               LinearLayout#batteryInfoContainer(5)
L2 FrameLayout#bottomContainer: LinearLayout#textContainer(4)
L3 LinearLayout#-: FrameLayout#topContainer(3)  FrameLayout#bottomContainer(1)
L4 un#-: LinearLayout#-(2)
```

关键三条：
- 电量数字 **是** `TextView#batteryText`（不是 Lottie 文本层），
- 它和 `BatteryLottieView#batteryView` 同属 `LinearLayout#singleBatteryContainer`，
- 设备名是 `TextView#deviceName`，卡片上没有 MAC。

### 9.2 实现

| 文件 | 作用 |
| --- | --- |
| `hook/ColorOsHomeCardBattery.kt` | 纯逻辑：`textFor(left,right,case)` 拼 `L100 R100 C100`；`matches(cardName,podName)` 规范化后双向包含匹配 |
| `hook/ColorOsPodBatteryStore.kt` | `ColorOsPodBattery` 增加 `name`（广播 `device_name`）；新增 `snapshot()` |
| `hook/ColorOsDeviceSpaceHook.kt` | 电量刷新时遍历窗口，命中 `BatteryLottieView` → 找到 `HomeCardView` → 读 `deviceName` → 配对 → 在 `singleBatteryContainer` 里插入 `TextView`，并把宿主 `batteryText` 收起 |

行为约定：
- **只有一段电量时不显示**（宿主已经画了那个数字，多一行没意义）；≥2 段才挂。
- **配不上名字就什么都不做**，绝不让别的设备电量落到耳机卡上。
- 浮层文本用单字母标签 `L/R/C`：真机上中文标签 `左 100 · 右 100 · 盒 100` 会被截断成 `盒 100 …`，
  换成 `L100 R100 C100` 一行正好放下。
- 任一耳机低于 `ColorOsAirpodsBatteryBlock.LOW_THRESHOLD` 时浮层标红，补上被收起的 `batteryText` 丢失的低电提示。
- 只在 `singleBatteryContainer` 是 `LinearLayout` 时插入，别的布局一律不动（记一条 `home overlay skipped` 日志）。

### 9.3 真机结果

`huaweipods_multi_battery=2`（卡片位）时，FreeClip 卡片底部由 `▭ 100` 变成 `▭ L100 R100 C100`，
电池图标保留、宿主单值数字收起；日志：

```
Device Space home overlay attached name=FreeClip text=L100 R100 C100
Device Space DTO gate mask=2 storeHit=true left=100 right=100 case=100 address=30:A9:98:CB:C4:54
```

截图：`.local/recon/verify2-19-overlay.png`（最终形态）、`verify2-11-overlay.png`（未收数字时的对比）。

### 9.4 开关与回退

`Settings.Global huaweipods_multi_battery` 或 App 内「设备空间三段电量（实验）」开关；
关掉后下一轮电量刷新就把浮层 `GONE`、把宿主 `batteryText` 恢复 `VISIBLE`，不需要重启宿主。
默认仍是**关**。