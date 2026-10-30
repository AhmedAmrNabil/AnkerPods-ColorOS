# ColorOS 17 钩子签名核对表（2026-10-30）

对象：模块 1.9.3.0 里所有面向宿主的反射目标
固件：PLK110_17.0.0.105(CN01) 的 `MyDevices 17.25.10` / `AccessoryFramework` / `WirelessSettings` / `Bluetooth`

跑法（可复现，脚本随仓库分发）：

```powershell
.\tools\audit-signatures.ps1 -RepoRoot . -DexDumpDir .local\recon\dex
```

固件 dump 不随仓库分发（`.local/` 被 gitignore），生成方式见
[Hook 签名矩阵](coloros17-hook-signature-matrix-20261030.md) §3：`dexdump -d` 全量反汇编 +
`aapt2 dump resources` 反编译资源表。

## 1. 结论

10 个宿主类全部存在，**0 个断链**。模块在 ColorOS 17 上的反射目标没有因为固件升级而失效。

| 宿主类 | 固件位置 | 模块实际命中的成员 |
| --- | --- | --- |
| `com.oplus.mydevices.bluetooth.BlueToothCardView` | `mydevices_classes.txt:84713` | `setBatteryDisplayMode`、`setBatteryPower`、`setBluetoothAddress`、`setDeviceName` |
| `com.oplus.mydevices.quickapp.data.QuickCardDeviceDataConverter` | `mydevices_classes.txt:91433` | 反射取 `h(...)`（成员名 `h` 太短，脚本按 3 字符过滤，需人工确认） |
| `com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData` | `mydevices_classes.txt:86952` | `batteryLeft`、`batteryRight`、`batteryMain`、`batteryMin`、`icon`、`name`、`originalIconUri`、`isShowBattery`、`isSupportMutiBattery` |
| `com.oplus.mydevices.domain.entities.device.BatteryInfo` | `mydevices_classes.txt:87880` | 构造器 `(BatteryType, int, boolean)`（名字来自 `BatteryType` 枚举，非字符串字面量） |
| `com.oplus.mydevices.bluetooth.PluginPanelHostActivity` | `mydevices_classes.txt:325745` | `onCreate`（外加 `Activity.onPostResume` 基类钩子） |
| `com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment` | `mydevices_classes.txt:303251` | `onViewCreated` |
| `com.oplus.pantaconnect.appcore.devicediscovery.ui.DialogActivity` | `accessory_classes.txt:33529` | `onCreate` |
| `com.oplus.pantaconnect.data.BaseViewData` | `accessory_classes.txt:49539` | `getPluginViewData`、`parseFrom` |
| `com.oplus.wirelesssettings.api.BluetoothNative` | `wirelesssettings_classes.txt:155976` | `getDeviceVendorId` |
| `com.android.bluetooth.a2dp.A2dpService` | `bluetooth_classes.txt:7723` | `connect`、`getConnectedDevices`、`mHandler` |

`QuickCardDeviceDataConverter` 的完整方法表（用于确认 `h` 的签名没变）：

```
h(IDevice, $c, $e, $d, boolean) -> QuickCardDeviceData
B(BatteryInfo, BatteryInfo, BatteryInfo) -> String
s(IDevice, boolean) -> QuickCardDeviceData
t(IDevice, boolean) -> ConnectState
v(IDevice, DeviceType, ConnectState) -> List
```

## 2. 未命中项与解释（不是断链）

首轮粗筛用"源码里所有类名样式字面量"对固件全量比对，43 条未命中，逐条分类如下：

| 类别 | 例子 | 判定 |
| --- | --- | --- |
| 旧版本类名 fallback | `com.oplus.mydevices.bluetooth.fragment.BtDetailPageFragment` | 预期未命中：模块同时试新旧两个名字，新名 `com.heytap.mydevices.plugin.bluetooth.fragment.*` 命中 |
| 广播 action / 权限名 | `android.bluetooth.adapter.action.STATE_CHANGED`、`android.permission.BLUETOOTH_PRIVILEGED` | 不是类名，脚本误收 |
| 运行时组件名 | `com.oplus.startupapp.view.StartupAppListActivity` | `ComponentName` 解析，宿主不在本机固件里也不影响 |
| 未安装的第三方包 | `com.huawei.audiodevicekit.*`、`com.huawei.smartaudio` | 华为智慧音频 App 的类，PLK110 上没装，相关钩子本来就不会装 |
| 模块自己的类 | `moe.chenxy.huaweipods.*` | 不是宿主目标 |
| framework 类 | `android.app.ActivityThread`、`android.view.WindowManagerGlobal`、`android.os.SystemProperties` | framework 不在这些 dump 里 |

## 3. 本轮顺带挖到的更优注入点

`QuickCardDeviceDataConverter#h()` 的内部实现（`dexdump` `4589e0` 起）显示：DTO 里的电池不是自己算的，而是从
`QuickCardDeviceDataConverter$b` 拿的：

```
$c.b()            -> QuickCardDeviceDataConverter$b        // 电池组
$b.a()            -> BatteryInfo                            // 第一段
$b.d()            -> BatteryInfo                            // 第二段
B(b1, b2, b3)     -> String                                 // 三段拼成的展示串
```

也就是说存在两个层次的注入点：

1. **DTO 结果层**（现在用的）：`h()` 返回后反射改 `batteryLeft/Right/Main/Min`；
2. **数据源层**（更早、更干净）：hook `QuickCardDeviceDataConverter$b.a()/d()` 直接给宿主自己的 `BatteryInfo`。

第 2 条的好处是宿主拿到的就是我们给的值，`B()` 拼出来的展示串、卡片、详情页会**同时**是这三段，
不需要和多段布局搏斗；坏处是 `$b` 是内部类、方法名 `a`/`d` 极短，且宿主可能在别处复用同一实例。
两条都在 `MULTI_BATTERY_*` 开关后面，等真机验证时按"先 DTO、后数据源"的顺序试。
