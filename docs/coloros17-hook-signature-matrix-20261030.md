# ColorOS Hook 与实机固件签名核对（PLK110 / `PLK110_17.0.0.105(CN01)`）

2026-10-30。目的：把模块里所有反射 / Hook 目标摊开，逐条对真机固件（从本机 pull 出的 APK → dex → dexdump，
落在 `.local/recon/dex/*.txt`）核对一次，确认哪些还成立、哪些已经静默失效。

固件基线：MyDevices 17.25.10、AccessoryFramework、SystemUI 17.99.02、Bluetooth、WirelessSettings 17.5.17，
LSPosed 7927 / API 102。

## 1. 核对结果总表

| 模块目标 | 宿主 APK | 固件实测 | 结论 |
| --- | --- | --- | --- |
| `com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment#onViewCreated` | MyDevices `classes4.dex` | 类存在、方法存在 | ✔ 17.x 详情页入口有效 |
| `com.oplus.mydevices.bluetooth.fragment.BtDetailPageFragment` | MyDevices | **不存在** | ✔ 预期（16.x 遗留路径，17.x 走 Preference 路径） |
| `androidx.preference.Preference#onBindViewHolder`（结构定位，不按名） | 宿主自带 | androidx 类名保留、方法被 R8 改名 | ✔ 结构定位是唯一可行做法；按名反射必挂 |
| `android.R.id.title` / `android.R.id.summary` | AndroidX 布局 | 存在于 Preference 行 | ✔ 17.x 用系统 id，不再猜 TextView（本轮已改） |
| `com.oplus.mydevices.quickapp.data.QuickCardDeviceDataConverter#h(IDevice,$c,$e,$d,Z)` | MyDevices `classes.dex` | 签名逐字节一致，返回 `QuickCardDeviceData` | ✔ 设备空间首页卡片注入有效 |
| `QuickCardDeviceDataConverter$c` / `$d` / `$e` | MyDevices | 三个内部类都在 | ✔ |
| `com.oplus.mydevices.domain.ports.device.IDevice#getMacAddress()String` | MyDevices | 存在 | ✔ |
| `QuickCardDeviceData` 字段 `icon` / `name` / `originalIconUri` | MyDevices | 三者都是 `String` 私有字段 | ✔ 四个 `check()` 全部通过 |
| `com.oplus.mydevices.bluetooth.BlueToothCardView#k()V` | MyDevices `classes.dex` | 存在（`k()` 与 `h()/g()/l()/m()` 同族） | ✔ 详情大图注入有效 |
| `BlueToothCardView` 字段 `c:String`（地址）、`g:ImageView` | MyDevices | 字段名与类型一致 | ✔ 未漂移 |
| `BlueToothCardView#setBluetoothAddress(String)` / `#setDeviceName(String)` | MyDevices | 存在 | ✔ |
| `com.oplus.mydevices.bluetooth.PluginPanelHostActivity`（按类名匹配窗口） | MyDevices `classes4.dex` | 类存在（`extends Laa/a4`，`onPostResume` 由框架 Activity 提供） | ✔ Hook `Activity#onPostResume` + 类名过滤成立 |
| `android.view.WindowManagerGlobal#getInstance` + 字段 `mViews` | framework | 存在（隐藏 API） | ✔ 详情浮层窗口枚举成立 |
| `com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice#notifyBatteryLevelChanged(BluetoothDevice,int[])` | **Bluetooth.apk `classes.dex`** | 接口存在，方法存在；`TRANSPORT_HFP_AT=1`、`TRANSPORT_AIRPOD=2`、`TRANSPORT_3RD_ECHO=0` | ✔ 注意：接口不在 `oplus_bluetooth_common_ext.jar`，取 ClassLoader 必须用蓝牙进程自己的 |
| `IOplusRemoteDevice#notifyWearStatusChanged` | Bluetooth.apk | 存在 | ✔ |
| `com.oplus.bluetooth.common.OplusFeatureCache#get(IOplusCommonFeature)` | Bluetooth.apk | 静态方法签名一致 | ✔ |
| `com.android.bluetooth.btservice.AdapterService#setBatteryLevel(BluetoothDevice,int,boolean)` | Bluetooth.apk | 存在（`I` 为 int） | ✔ 标准电量通道有效 |
| `com.oplus.pantaconnect.appcore.devicediscovery.ui.DialogActivity#onCreate(Bundle)` / `#g(DialogActivity)` | AccessoryFramework | 类存在、`onCreate` 存在；`dispatchTouchEvent` 继承自框架 | ✔ 快速连接弹窗守卫有效 |
| `com.oplus.pantaconnect.pluginmgr.services.SceneService#a(...)` | AccessoryFramework | 存在（混淆名 `a`，多参） | ✔ 弹窗宿主入口有效 |
| `com.heytap.accessory.plugin.discovery.DialogParams#setHideCancelButton` | AccessoryFramework | 存在 | ✔ |
| `com.oplus.wirelesssettings.api.BluetoothNative#getDeviceVendorId(BluetoothDevice)I` | WirelessSettings `classes2.dex` | 存在（static） | ✔ |
| `android.os.SystemProperties#get(String,String)` | framework | 存在（隐藏 API） | ✔ 白名单读取有效 |
| `persist.bluetooth.3rd_earbuds_hfp_at_vendor` | system property | 由 `OplusThirdPartyEarbudAtHandler.parseHfpAtVendorProperty()` 读取，缺省 `[301,1356]`；列表为空时 `isVendorAllowed` 返回 true | ⚠ 机型级开关，模块只读不写 |

**结论：本轮没有发现"已经被固件改签名"的 Hook。** 也就是说 1.9.2.1 之前的所有 ColorOS 集成点在 17.25.10 上都还在，
真正的问题不是"钩子断了"，而是"钩上了但行为不对"——这正是本轮 10 项修复的落点。
唯一的静默失效风险在 16.x 遗留路径（类不存在时 `hookLegacyDetailPage()` 抛异常，由 `onHook()` 记为 `legacy.isFailure` 并继续走 Preference 路径）。

## 2. 顺带测出的一件事：三段电量其实有现成通道

固件测绘报告原本判断"三段电量只能 hook 宿主 binder `aa.Z7` 或跨进程"，实测发现有更直接的路径，
所有字段都在模块**已经 hook 的**那条链上：

```
QuickCardDeviceDataConverter#h(IDevice,$c,$e,$d,Z)  →  QuickCardDeviceData
```

- `QuickCardDeviceData` 除 `icon` / `name` / `originalIconUri`（模块已在改）之外，还有
  `batteryLeft` / `batteryRight` / `batteryMain` / `batteryMin`，类型 `com.oplus.mydevices.domain.entities.device.BatteryInfo`；
- `BatteryInfo` = `{ BatteryType batteryType; boolean charge; int value }`，全部私有字段；
- `BatteryType` = `{ BOX, LEFT, RIGHT, SINGLE }`；
- 卡片侧 `BlueToothCardView` 有字段 `f: BluetoothCardBatteryDisplayMode`，枚举值只有 `{ MULTI, SINGLE }`，
  并暴露 `setBatteryDisplayMode(BluetoothCardBatteryDisplayMode)` 与 `setBatteryPower(int)`；
  另有 `h/k: TextView`、`j: ProgressBar` 用于显示。

也就是说：**在已有的 `h()` 钩子里顺手把 `batteryLeft/Right/Main` 填成 `BatteryInfo(LEFT/RIGHT/BOX, charging, percent)`，
再把卡片的 `f` 设成 `MULTI`，设备空间首页卡片就会按系统原生样式显示左 / 右 / 盒三段电量**，
不需要碰 protobuf 的 `ProtocolDeviceViewData`，也不需要 `deviceplugin.sdk`（那套确实只在 SystemUI 里）。

风险点（下一轮必须带验证再做）：

1. `BatteryInfo` 全是私有字段且只有 `private BatteryInfo(...)` 构造 —— 要反射构造，或找它的 `Companion`；
2. `MULTI` 模式对 `batteryMain` / `batteryMin` 的语义（盒电是 `BOX` 还是 `MAIN`）需要真机目视确认，填错会把主电量显示成 0；
3. 宿主可能在绑定后再次调用 `setBatteryPower` 覆盖，需要 hook 之后在主线程补一次。

## 3. 方法说明（可复现）

```powershell
# 1) 从模块源码里抽出所有反射目标
Get-ChildItem app/src/main/java -Recurse -Filter *.kt |
  Select-String -Pattern '(?:findClass|Class\.forName|getMethod|getDeclaredMethod|getDeclaredField)\(' 

# 2) 对每个目标在固件 dump 里按块核对（块以 "===== CLASS L..." 开头）
#    .local/recon/dex/{mydevices,bluetooth,accessory,wirelesssettings,systemui}_classes.txt
Select-String -Path .local\recon\dex\mydevices_classes.txt -Pattern 'CLASS Lcom/oplus/mydevices/bluetooth/BlueToothCardView;' -Context 0,45
```

固件 dump 的生成方式与脚本在 `.local/recon/tools/`（`cls.py` / `meth.py` / `fieldmap.py` / `disasm.py` 等），
对应 APK 在 `.local/recon/firmware/`；两者都在 `.gitignore` 的 `/.local/` 下，不会进仓库。
