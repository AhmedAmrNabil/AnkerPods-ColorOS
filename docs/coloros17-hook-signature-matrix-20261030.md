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

> **⚠ 真机更正（2026-10-30，OnePlus 15 / ColorOS 17）**
>
> 上面这段"顺手就能吃到"的推断**被真机证伪**，错在"设备空间首页卡片 = `BlueToothCardView`"这个前提：
>
> - 遍历窗口里的真实视图类（一次性 dump，见[审计报告](coloros17-audit-report-20261030.md) §8.2）：
>   `HomeCardRecyclerView` / `homecard.HomeCardView` / `quickapp.homecard.view.BatteryLottieView`，
>   **一个 `BlueToothCardView` 都没有**（`cards=0`）。
> - 给 `h()` 加落点日志后，首页渲染期间进来的地址是别的设备（`B4:E7:B3:05:57:7E`）和空串，
>   FreeClip 的地址一次都没进 `h()` —— 首页卡片的电量不走 `QuickCardDeviceData`。
> - `BlueToothCardView#k()` / `setBatteryPower()` 的钩子在整个验证过程中一次都没触发。
>
> 因此：`h()` 的 DTO 电池槽 + `MULTI` 切换对**蓝牙设置侧的 `BlueToothCardView`** 仍然有效（那套布局与字段
> 已按固件逐字核对），但对**设备空间首页**无效。首页要显示三段得新做 `HomeCardView`/`BatteryLottieView`
> 视图级注入，不能用打开开关的方式得到。

### 2.1 真机打过一次，翻车了：`MULTI` 会把英雄图让给 ViewStub（dexdump 实证）

按上面那条路做完、装到 PLK110 上，结果是"耳机图变成白色带柄的通用 TWS 图，电量还是单值"。
把 `MyDevices.apk!classes.dex` 用 `dexdump -d` 反汇编后，`BlueToothCardView` 的内部结构如下
（`.local/recon/dex/mydevices_classes_disasm.txt`，类起始 `line 1344960`）：

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| `b` | `I` | `mDeviceSecondaryType`，选内置设备图/设备的次级类型 |
| `c` | `String` | 蓝牙地址 |
| `d` | `ConnectState` | 连接状态 |
| `e` | `I` | 单值电量（`setBatteryPower` 写的那个） |
| `f` | `BluetoothCardBatteryDisplayMode` | `{ MULTI, SINGLE }` |
| `g` | `ImageView` | **`SINGLE` 模式的英雄图** |
| `n` | `FrameLayout` | `SINGLE` 模式的电量容器 |
| `o` | `View` | **`MULTI` 模式的容器，按需从 ViewStub inflate**（`i()` 负责 inflate 并缓存） |

`k()V`（bind / 刷新，384 code units）关键分支：

```
0029: v1 = this.f                     // 当前显示模式
002b: v3 = BluetoothCardBatteryDisplayMode.MULTI
0030: if-ne v1, v3, 0052             // 不是 MULTI → 走单值绘制
0032: i()                            // inflate 多段容器
003a: n.setVisibility(GONE)          // 藏单值容器
0042: o.setVisibility(VISIBLE)       // 显示多段容器
0045: j()                            // 把单值的 5 个电池图标槽位全部 GONE
0048: v0 = null
0049: this.g = null                   // ★ 英雄图字段被清空
004b: h(); 004e: m(); 0051: return   // ★ 直接 return，不再画图
```

结论（也就是上一轮翻车的真正原因）：

1. `MULTI` 下 `g` 被置 null 且 `k()` 提前返回，**宿主根本不会给英雄图赋值**；
   屏幕上那张图是 ViewStub 布局自带的默认图 —— 即"白色带柄 TWS"。
2. 我们原来只认字段 `g` 贴图，`MULTI` 时 `g == null` 直接放弃，于是留下宿主默认图。
   现在 `heroImage()` 会退一步：`g` 为空时在多段容器 `o` 里找面积最大的可见 `ImageView`。
3. `m()`（renderState）在 `MULTI` 分支只读了 `d`（ConnectState）和 `e`（单值电量），
   **三段数值不是视图自己画的**，来自数据/适配层 —— 所以只调 `setBatteryPower` 填三段没意义。
4. `setBatteryDisplayMode(mode)` 变值才会写 `f` 并重跑 `k()`；`setBatteryPower(p)` 变值才会写 `e` 并跑 `m()`。
   两个都是"值没变就啥也不干"，hook 时不能用同值反复覆盖来触发重绘。

因此三段电量的正确顺序是：**先解决多段容器里的图（`heroImage()` 已就绪），
再确认三段数值的真实来源（`QuickCardDeviceData` 的 4 个 `BatteryInfo` 到底喂给了谁）**，
两者都落定之前，`MULTI_BATTERY_CARD_ENABLED` 保持 `false`，卡片维持单值电量。

### 2.2 布局级实证：`MULTI` 容器到底是哪块布局（aapt2 反编译固件）

把 `MyDevices.apk` 的资源表反编译（`aapt2 dump resources` + `aapt2 dump xmltree`）后，
`R` 类里那些被 R8 改成 `C`/`G`/`H1`/`P` 的短字段名全部还原成了资源名，物证如下。

**卡片根布局 = `layout/devicecard_control_preference_bt`（`res/Tt.xml`）**

```
ConstraintLayout
├─ LinearLayout
│  └─ TextView            id/device_name
├─ FrameLayout            id/container_battery_and_hint          ← 字段 n（SINGLE 容器）
│  ├─ include             id/device_not_connected_hint_id        ← 字段 l
│  ├─ include             id/device_connected_hint_id            ← 字段 m
│  └─ ConstraintLayout    id/device_electricity_icon             ← 字段 i
│     └─ LinearLayout     id/device_battery
│        ├─ ProgressBar   id/device_electricity_progress         ← 字段 j
│        └─ TextView      id/device_electricity                  ← 字段 k
├─ ViewStub               id/stub_airpods_multiline_battery      ← 字段 o（MULTI 容器）
└─ ImageView × 6          id/bt_car / bt_a2dp / bt_heaset / bt_handle / bt_flash / bt_cooperate_headset
```

**重要更正**：字段 `g` 不是"英雄图"，而是上面 6 个**设备类型图**里被选中的那一个 ——
`k()` 在 `SINGLE` 分支按 `b`（`mDeviceSecondaryType`，`2008`/`2009` 有特例）做 `packed-switch`，
把其中一个设成 `VISIBLE` 并存入 `g`，其余 5 个 `GONE`。模块一直往 `g` 贴图之所以有效，
是因为在 `SINGLE` 下**它就是卡片上唯一的设备图槽位**。

**ViewStub 目标布局 = `layout/include_bluetooth_card_airpods_battery_block`（`res/RX.xml`）**

```
container_battery_and_hint_airpods
├─ device_not_connected_hint_id_airpods
├─ layout_airpods_max                    ← 主段
│  ├─ airpods_electricity_progress       (ProgressBar)
│  ├─ airpods_max_charging_icon          (drawable airpods_charge_max)
│  └─ airpods_electricity_text
└─ layout_airpods_battery
   ├─ layout_box   : airpods_box_image / airpods_box_battery / airpods_box_charge / airpods_box_low
   ├─ layout_left  : left_airpods_image / left_airpods_battery / left_airpods_charge / airpods_left_low
   ├─ layout_right : right_airpods_image / right_airpods_battery / right_airpods_charge / airpods_right_low
   ├─ view_airpods_battery_line           (分隔线)
   └─ airpods_image                       ★ 产品图（AirPods 渲染）
```

关键更正（两次修正后的定论）：这块布局**确实有产品大图**，就是最后那个 `airpods_image`。
上一轮"白色带柄 TWS 图 = ViewStub 自带默认图"和图省掉一次的"这块里没有大图"都不对；
真相是 `MULTI` 下宿主把 6 个 `bt_*_imageView` 全 `GONE`（`j()`），屏幕上那张图换成了 `airpods_image`
里内置的 AirPods 渲染 —— 也就是用户看到的"图不对"。

对策已落到代码：`heroImage()` 在字段 `g` 为空时**先按名字取 `airpods_image`**，取不到才退回"面积最大的
可见 ImageView"。按名字取是确定的，面积兜底只在本模块或固件换布局时才会用到。

**三段数值的真实来源**

`QuickCardDeviceData.getBatteryLeft/Right/Main` 在 5 个 dex 里只有两处消费者，都在 quickapp 首页卡模板：

| 消费者 | 行为 |
| --- | --- |
| `aa.s7.c(QuickCardDeviceData)` | 三段分别交给 `aa.s7.b(view, BatteryInfo)`，`batteryInfoContainer` 三格 |
| `aa.La0.b(QuickCardDeviceData)` | `BatteryLottieView.setBatteryInfo(BatteryInfo)` + 一个 TextView（单段 Lottie 电量） |

AirPods 三格块的贴数在 AirPods 插件里（`com.heytap.mydevices.plugin.airpods`）：

| 方法 | 作用 |
| --- | --- |
| `core.a.c(BatteryType, int level, int idx, int[] flags)` | `flags[idx] == 4` 判非充电，`level <= 0` 判无效，产出 `BatteryInfo(type, level, charging)` |
| `core.a.b(List, int[])` | 组装 `TSLItem("batteryCharging_1280_2", boolean)` 上报 |
| `core.a.f(int, int[])` | 由原始字节数组产出 `List<BatteryInfo>` |

**结论与实现路线**（本轮已按 (b) 落代码，开关仍为 `false`）：

- (a) 把 FreeClip 伪装成 AirPods 走插件路径 —— 需要伪造设备身份，`AirpodsSettingActivity` 会校验并 `finish`，风险高，不做；
- (b) 宿主 inflate 完多格块后**自己填四格**：按资源名取 id（`ColorOsAirpodsBatteryBlock` +
  `applyMultiBatteryBlock()`，纯逻辑已有 6 个单测），格式 / 低电阈值 / 充电图标 / 主段落回逻辑都在纯函数里。

**quickapp 卡片视图的归属（`aa.un` / `aa.m00`）**

`aa.s7` 三格渲染器只被一个视图使用：`aa.un`（`ConstraintLayout`，字段 `j` = `mainImage`、`u` =
`batteryInfoContainer`、`e` = `aa.Ln` 图片加载包装、`B`/`C`/`D` 图+文案）。它由工厂
`aa.m00.d(Context, QuickCardDeviceData) -> aa.un` 创建，而 `aa.m00` 的 `b/c/d` 在整个 APK
（5 个 dex）里**没有任何调用方** —— 说明这条 quickapp 卡片链是由动态插件侧反射/跨 APK 调用的，
本 APK 只提供实现。因此：

- 想确认"详情页三段电量是不是走 quickapp 卡片"，只能上真机看（模块日志里 `aa.m00.d` 是否被调到）；
- 在不碰真机的前提下，把数据面和展示面拆成两个开关是唯一安全的推进方式：
  `MULTI_BATTERY_DTO_ENABLED`（只写 DTO 的 4 个 `BatteryInfo` 槽位，卡片只读单值 `e`，理论上不改外观）
  与 `MULTI_BATTERY_CARD_ENABLED`（切卡片视图到 `MULTI` + 自填多格块），两者默认都关。

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
