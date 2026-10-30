# ColorOS 17 深度适配审计报告（2026-10-30）

对象：`ByteJuno/HuaweiPods-ColorOS`（1.9.3.0 / versionCode 29）
实机：OnePlus PLK110（`PLK110_17.0.0.105(CN01)`，API 37，ColorOS 17），LSPosed 7927 / libxposed API 102，adbd `127.0.0.1:18583`（root via `su -c`）
配套文档：[深度适配设计](coloros17-deep-adaptation-20261030.md)、[Hook 签名矩阵](coloros17-hook-signature-matrix-20261030.md)

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
   结论见 [Hook 签名矩阵](coloros17-hook-signature-matrix-20261030.md) §1（本轮没有发现被固件改签名而断掉的钩子）。
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

### 3.3 设备空间卡片：`MULTI` 会换布局并丢掉注入的耳机图（dexdump 实证）

`BlueToothCardView.k()` 在 `MULTI` 分支里 `0049: g = null`、`0051: return`，**宿主不画英雄图**，
屏幕上那张是 ViewStub 布局自带的通用 TWS 图；`m()` 的 `MULTI` 分支只读 `ConnectState` 与单值电量。
三段电量的原生消费方是另一套模板：`aa.un`（`batteryInfoContainer`）→ `aa.s7.b(view, BatteryInfo)` ×
`getBatteryMain/Left/Right`，以及公开 API `BatteryLottieView.setBatteryInfo(BatteryInfo)`。
细节与指令级证据见 [Hook 签名矩阵](coloros17-hook-signature-matrix-20261030.md) §2.1。

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
| 4.6 | 设备空间三段电量显示会丢图 | `heroImage()`：`g == null` 时在多段容器 `o` 内取面积最大的可见 `ImageView` | 代码 + §3.3 实证，待真机开开关验证 |
| 4.7 | 三段电量数据通路缺失 | `ColorOsPodBatteryStore`（快照 + 选段纯逻辑 + 10 单测）、进程内电量接收者、DTO 电池槽写入 | 单测 `ColorOsPodBatteryStoreTest` |

## 5. 未修 / 已知限制

1. **流体云卡片内的按钮点不动** —— 平台模板限制（整卡一个 `contentIntent`），已用"点卡片开弹窗"绕开，
   不做 hack（伪造 `PendingIntent` 会破坏平台校验）。
2. **设备空间多段电量未开启** —— `MULTI_BATTERY_CARD_ENABLED = false`。数据面已通，
   但多段布局的三段数值来源与英雄图落点还差一次真机验证（§3.3 已把下一步锁定到 `aa.s7` / `BatteryLottieView`）。
3. **原生三段降噪（`TRANSPORT_HFP_AT` + `+OBUDS`）** —— 需要真机反复试 AT 帧，本轮未动。
4. **状态栏耳机电池图标** —— ColorOS 17 没有可挂钩点（`deviceplugin.sdk` 只在 SystemUI 内部使用）。
5. **覆盖安装后宿主进程复用旧资源句柄** —— 日志 `skip notification: module resources unavailable`，
   需重启宿主进程才恢复；属加固前的既有现象，未在本轮改动。

## 6. 验证与构建证据

- 单测：**656 通过 / 0 失败**（`app/build/test-results/testDebugUnitTest`）
- Lint：**0 error**（`app/build/reports/lint-results-debug.html`）
- Release：`:app:assembleRelease` 通过，APK ≈ 4.72 MB
- 实机：`adb install -r` 覆盖安装成功；关键路径截图见 `.local/recon/verify-*.png`

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

# 看模块日志
adb -s 127.0.0.1:18583 shell 'su -c "tail -n 200 /data/adb/lspd/log/modules_*.log"'
```
