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
2. **数据源层**（更早）：hook `QuickCardDeviceDataConverter$b.a()/d()` 直接给宿主自己的 `BatteryInfo`。

第 2 条看着更干净，但**不能直接这么做**：`$b` 是 `h()` 内部临时组装的电池组对象，`a()/d()`
本身**不带地址**，在数据源层注入等于对所有蓝牙设备生效——会把别的耳机卡片也写坏。
`h()` 拿到的 `IDevice` 才带地址，而它是在 `$b` 之后才用上的，因此唯一"带地址作用域"的注入面就是
DTO 结果层（`h()` 返回值）和 `BlueToothCardView`（本身就绑定了地址字段 `c`）。
这一条把原来的"先 DTO、后数据源"结论收紧为：**只有 DTO 层可用**。

## 4. 流体云种子卡（seedling）通道：第三方为什么进不去

`SystemUI.apk!classes3.dex` 里有一个完整的种子卡（流体云）框架，入口是 ContentProvider：

```
com.oplus.pantanal.seedling.serviceLayer.BaseSeedlingCardStrategyProvider
  extends com.oplus.channel.client.provider.ChannelClientProvider
  call(String, String, Bundle) -> Bundle          // 通道入口
  update(Uri, ContentValues, Bundle) -> Int
  updateAllCardData(SeedlingCard, String, JSONObject, SeedlingCardOptions, int, INegativeFeedbackCallback)
com.oplus.pantanal.seedling.SeedlingCardWidgetProvider   // 宿主侧实现，onCreate/initChannel
```

权限判定在 `checkCallPermission()Z`（`dexdump` `444238` 起），放行条件只有四条：

1. `Binder.getCallingUid()` 已在 `sysAppUids` 缓存里；
2. 调用方 uid == SystemUI 自己的 uid；
3. 调用方 uid == 1000（system）；
4. 调用包名在 `allowVisitPackageNameList` 里，或 `ApplicationInfo.flags & FLAG_SYSTEM != 0`。

也就是说：**普通第三方 App 无论申请什么权限都进不去**，这就是"控制中心设备卡片条 / 流体云卡片"
一直没有可用挂点的实证原因。唯一可能的路径是 hook 进 SystemUI 进程（此时 `myUid` 就是 SystemUI 的 uid，
第 2 条直接放行），但模块目前**完全没有 SystemUI scope**（`app/src/main/java` 里没有任何
`com.android.systemui` 目标），要新增 scope + 依赖宿主的 `SeedlingCard` 模板，属于独立大工程，本轮不做。
