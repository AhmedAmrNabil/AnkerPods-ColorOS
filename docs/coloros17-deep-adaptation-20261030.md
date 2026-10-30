# ColorOS 17 深度适配记录（2026-10-30，1.9.3.0）

本轮起点：对宿主固件做一次接口级测绘，然后用测出来的事实改写模块里"靠猜"的部分，并补上两处此前完全缺席的系统集成。
所有结论都标注了证据来源：`firmware` 指从真机 pull 出的宿主 APK/jar 反编译结果（`.local/recon/firmware/`，已 gitignore），
`code` 指本仓库源码，`device` 指 OnePlus PLK110 / `PLK110_17.0.0.105(CN01)` 实机验证。

## 1. 本轮落地的改动

### 1.1 详情页开关不再"只能打开，不能回滚"

`code`：`hook/ColorOsMyDevicesHook.kt`、新增 `hook/ColorOsToggleWriteLedger.kt`

旧实现点击低时延 / 佩戴检测后立刻改内存状态并刷 UI，之后无论耳机有没有响应都不再回退。耳机侧写入失败
（机型不支持、AT 帧丢失、会话刚好断开）时，宿主页面会长期显示与真实状态相反的开关。

新实现引入一个可测的写入账本：

- `begin(address, key, requested, now)` 记录一次乐观写入和截止时间（默认 3 s）；
- 蓝牙进程回报 `ACTION_HUAWEI_LOW_LATENCY_CHANGED` / `ACTION_HUAWEI_WEAR_DETECTION_CHANGED` 时 `complete(...)` 认账，
  界面取回报里的真实值；`EXTRA_HUAWEI_LOW_LATENCY_WRITE_SUCCESS == false` 现在会被读取并单独记一条日志
  （此前这个字段被完全忽略）；
- 到期仍无人认账，`expired(now)` 取出该笔写入，界面回滚到 `!requested`。

账本不依赖 Android 类，注入时钟即可做单测（`ColorOsToggleWriteLedgerTest`，6 项）。

### 1.2 关闭 / 热重载时把宿主行还原

`code`：`ColorOsMyDevicesHook.onClose()` / `snapshotHostRow()` / `restoreHostRow()` / `readHostClickListener()`

旧 `onClose()` 只清自己的 map，宿主行上被改过的标题、摘要、无障碍描述和点击监听留在系统页面上；
API 102 热重载或模块停用后，"我的设备"里那行永远显示 HuaweiPods 且点不动。

现在注入前先快照（`HostRowSnapshot`：标题 / 摘要文字、摘要可见性、文本兜底列表、点击监听、clickable），
关闭时在主线程还原。`getListenerInfo().mOnClickListener` 是隐藏 API，被拦截时返回 `null`——
这种情况下清空监听是安全的，因为 androidx `Preference` 下次绑定会重新安装自己的监听。

### 1.3 17.x Preference 行不再盲改文本

`code`：`customizeRowText(..., allowLabelFallback)`

`row_bt_setting`（16.x）没有 `android.R.id.title/summary`，只能按文本顺序兜底；17.x 的 Preference 行一定有系统 id。
旧代码对两者都用同一条兜底路径，猜错 TextView 就会改坏宿主其它文字，而且事后无法判断哪段文字是自己的。
现在只有 16.x 入口传 `allowLabelFallback = true`，17.x 找不到 `android.R.id.title` 时保持宿主文字不动并记警告。

### 1.4 断连即清状态

`code`：`ColorOsMyDevicesHook` 新增 `ACTION_PODS_DISCONNECTED` 分支 + IntentFilter + `HuaweiPodsBroadcastTrustPolicy.myDevicesStateActions`

详情页缓存（电量摘要、降噪档位、低时延、佩戴）原来只在收到新数据时覆盖，从不清除。换一副耳机连上来之前，
页面会一直显示上一副的电量。现在断连事件清掉该地址的全部缓存并清空写入账本。
信任策略同步放开：该动作只接受 `com.android.bluetooth` 发送。

### 1.5 设备空间图片：先还原，再等线程

`code`：`ColorOsDeviceSpaceHook.onClose()`

旧顺序是 `check(awaitTermination(1.5s))` 再还原图片。线程卡住时 `check` 抛异常，还原被整段跳过，
宿主继续挂着模块的 Bitmap，等于把泄漏带进热重载。现在先还原（主线程阻塞调用），再关线程池，
超时只记警告——宿主状态优先于线程优雅退出。

### 1.6 快速连接弹窗的图片标记不再串场

`code`：`ColorOsAccessoryFrameworkHook` 的 `onCreate` hook

图片替换按资源 id 命中，不看卡片身份；旧代码只在"是华为卡片"时写标记，从不清空。
前一次华为弹窗留下的文件名会作用到下一个厂商的卡片上。现在每次弹窗 `onCreate` 先清空两个标记再判定。

### 1.7 断开时把 OPLUS 电量数组一起清掉

`code`：`ColorOsSystemBatteryBridge.clear()`

`notifyBatteryLevelChanged(int[6])` 是独立通道。旧 `clear()` 只清标准 Android 电量，
断连后"我的设备" / 蓝牙设置里仍显示断开前的左右耳和盒电量。现在两条通道一起清。

### 1.8 流体云：机器可读的降噪状态 + 一键切换

`code`：`coloros/ColorOsLiveAlertReceiver.kt`、`ColorOsLiveAlertActionReceiver.kt`、`ColorOsLiveAlertSettings.kt`

- 卡片内容抽成 `LiveAlertCard` + `buildLiveAlertNotification()`，卡片在 ANC / 低时延广播到来时可按持久化内容原样重建；
- 降噪按机型能力给出"关 → 降噪 → 通透"循环按钮（`colorOsLiveAlertNextNoiseMode`，纯函数，4 项单测）；
  未上报状态时按钮直接指向降噪，而不是从"关"开始空转一圈；
- 点击走 `PendingIntent` → `ColorOsLiveAlertActionReceiver` → 以模块身份 `ACTION_ANC_SELECT` 发给蓝牙进程；
  两个按钮用不同 request code，避免 `FLAG_UPDATE_CURRENT` 互相覆盖 extras；
- 刷新用同一 id 的 `NotificationManager.notify`，不重新启动前台服务（后台 FGS 启动受限）。

### 1.9 安全面收口

| 缺陷 | 处理 |
| --- | --- |
| `ACTION_PODS_MAC_RECEIVED` 与 `ACTION_GET_PODS_MAC` 是同一个字符串，`when` 落到请求侧控制域，被信任的控制发送方可伪造 MAC 回包 | 拆成 `.pods_mac_received`，并加"两常量必须不同 + 分别落在 BLUETOOTH_STATE / APP_CONTROL"的回归测试 |
| 流体云断连水位由事件自带的 `event_elapsed` 决定，伪造一个远未来的时间戳就能让之后所有真实电量事件被判过期 | 时间戳钳到真实 `elapsedRealtime()` 以内，且水位只允许前进 |
| `BluetoothAdapter.ACTION_STATE_CHANGED` 无发送方身份，任意三方应用可发 `STATE_OFF` 让卡片消失 | `STATE_OFF` 时核对真实适配器状态，仍开启则拒绝 |

## 2. 固件测绘得到的硬约束（决定了下一阶段怎么做）

来自 `.local/recon/firmware-surface.md`（60 KB，逐条给证据，APK 全部 pull 自本机）：

1. **原生三段降噪不是 Preference**，是 `IconTextSelector(LinearLayout)` + `IconTextItem(int,String,String,int,int)`。
   `AirpodsDetailPreferencesFragment.addNoiseList(mask)` 按位门控：id2 `noise` 无条件；id1 `off_noise`←bit0；
   id3 `trans_noise`←bit1；id4 `adaptive_noise`←bit2；bit5 控整表可见。喂数据 = `IconTextSelector.d(ArrayList,int)`，
   点击走 `NoiseReductionCommand → AirpodsBluetoothGateway → setExtendFeatureStatus(device, FEATURE_EXT_NOISE_MODE=4, val)`。
   实机验证：`AirpodsSettingActivity.onCreate` 直接 `it's not airpods devices !` 并 finish——非 AirPods 设备进不去这个页面。
2. **真入口在蓝牙栈**：`IOplusRemoteDevice` 定义在 `Bluetooth.apk!classes.dex`（不在 ext jar），44 方法，
   `setExtendFeatureStatus(BluetoothDevice,int,int)Z`、`notifyNoiseModeChanged(BluetoothDevice,int)V` 签名已核对；
   `FEATURE_EXT_NOISE_MODE=4 / ADAPTIVE=8 / OFF=512 / TRANSPARENT=1024` 在 `AirPodsManager`。
3. **华为耳机走 +OBUDS 协议就能点亮原生 UI**：登记 `TRANSPORT_HFP_AT(1)` + vendorId 进白名单 + 支持位，
   原生 `IconTextSelector` 自己渲染三段并发 AT 帧。协议常量已全解：`PREAMBLE=1839`、`msgId 48..53`、
   `FEATURE_NOISE_CONTROL=4`、`CMD_SET_NOISE_MODE=3`、`EVENT_NOISE_MODE_REPORT=3`、`LOCAL_VENDOR_ID=1946`。
4. **白名单精确到字节码**：`OplusThirdPartyEarbudAtHandler.parseHfpAtVendorProperty()` 读
   `persist.bluetooth.3rd_earbuds_hfp_at_vendor`，缺省 `[301,1356]`；`isVendorAllowed(int)` 在列表为空时 `return true`。
   WirelessSettings 侧还有第二份白名单 `[2688,1356,301,2016,3686]`，改一处不够。
5. **两个会直接崩的漂移点**：`BaseViewData` 已是 protobuf（4 字段，setter 全 private，Builder 是混淆类
   `com.oplus.pantaconnect.data.a`），反射找 title/desc 必挂；三段电量在 `ProtocolDeviceViewData$c`
   （`left/right/boxBattery_` + `*Status_`，**无充电布尔**）。
6. **`deviceplugin.sdk` 只在 SystemUI.apk（221 类），MyDevices 里 0 个**——`CompositeBatteryView` 在 MyDevices 进程里 new 不出来。
   ~~三段电量只能跨进程~~ **后续核对推翻了这条**：`QuickCardDeviceData` 本身就带
   `batteryLeft/Right/Main/Min`（`BatteryInfo{BatteryType{BOX,LEFT,RIGHT,SINGLE}, charge, value}`），
   卡片侧有 `BluetoothCardBatteryDisplayMode{MULTI,SINGLE}` + `setBatteryDisplayMode`，
   全部落在模块已 hook 的 `h()` 链上，见 [Hook 签名核对](coloros17-hook-signature-matrix-20261030.md)。
7. **前置开关**：`persist.bluetooth.airpods_support` 为假时降噪行整体失效；`oplus.software.bt.3rd_earbuds_extend` 属机型级开关。
8. **流体云正门是 Pantanal `SeedlingCard`**，但实测发现 promoted ongoing 通知**能进**：
   SystemUI 的 `OplusLiveAlertNotificationsRepository → NotificationLiveAlertProvider → PluginSeedling`
   会把带 `requestPromotedOngoing` 的常驻通知做成 `supCard=true` 的胶囊卡片（`sourceType=SOURCE_NOTIFICATION`）。
   代价是：这张卡片把整张卡（含按钮区）当成一个 contentIntent 点击区，**`Notification.Action` 的点击不会派发**。
9. **状态栏没有耳机电量图标入口**：`HeadsetIconViewModel` 只处理有线耳机（`WiredAudioDeviceInteractor`），无电量字段。

## 3. 下一阶段（按证据排序，未在本轮实施）

| 优先级 | 事项 | 依赖的固件事实 | 风险 |
| --- | --- | --- | --- |
| ~~P0~~ | ~~流体云按钮改走"点卡片开面板"~~ **已做（9.1）** | 实测卡片吞掉 `Notification.Action`，contentIntent 有效 | 已上线 |
| P0 | 设备空间卡片三段电量（左 / 右 / 盒） | **已有更短路径**：`QuickCardDeviceData.batteryLeft/Right/Main/Min`（`BatteryInfo{BatteryType{BOX,LEFT,RIGHT,SINGLE},boolean charge,int value}`）+ `BlueToothCardView.f: BluetoothCardBatteryDisplayMode{MULTI,SINGLE}`，全部在模块已 hook 的 `h()` 链上；详见 [Hook 签名核对](coloros17-hook-signature-matrix-20261030.md) | 中：`BatteryInfo` 私有构造需反射；`MULTI` 对 `batteryMain/Min` 的语义要真机目视确认，填错会把主电量显示成 0 |
| P0 | 原生三段降噪 UI（复用系统 `IconTextSelector`，不用自绘） | 1.2 / 1.3 / 1.4：`TRANSPORT_HFP_AT` + vendor 白名单 + `+OBUDS` AT 帧 | 高：需要长期驻留 AT handler，且要复现 `notifyNoiseModeChanged` 的回调时序 |
| P1 | 控制中心设备条（拖挂一台耳机卡片） | `SeedlingCard` 合法值枚举 + 三段电量数据源 | 中：无耳机模板，需要自己造卡 |
| P1 | `DialogActivity.g(DialogActivity)V` 弹窗复用（未变，可继续用） | 已核对签名未变 | 低 |
| P2 | 状态栏耳机电量图标 | 无入口，需要 hook SystemUI 自建 | 高，收益低 |

第 2 项是"深度适配"的真正分水岭：做成了，系统的 AirPods 详情页、控制中心、任何读 OPLUS 电量/降噪状态的地方
都会把华为耳机当成一等公民；代价是要把华为 AT 帧翻译成 `+OBUDS` 帧并在蓝牙进程里长期活着。

## 4. 本轮验证

**实机目视（16:39 后设备解锁、FreeClip 在连，全部为真机操作）**：

- 设备空间首页 FreeClip 卡片：华为官方配色图 + 电量 100 + 在线点；
- 设备空间详情浮层：官方整机图 + `左耳 100% · 右耳 100% · 耳机盒 50%`；
- 「我的设备」注入行：`HuaweiPods` + `左 100% · 右 100% · 盒 50%` + 箭头，点击实测拉起
  `chen.action.huaweipods.coloros.show_pods_ui → PopupActivity`（宿主 uid 10141 发出），
  说明注入与点击链路没被本轮 `onClose()` 还原逻辑破坏；
- 通知栏流体云卡片：`HUAWEI FreeClip` / `L 100%  R 100%  耳机盒 50%` / `低时延已关闭` / `[开启低时延]`，
  `dumpsys notification` 里 `PROMOTED_ONGOING` + `requestPromotedOngoing=true` + `shortCriticalText=100%` 全部符合；
- `coloros_live_alert_state.xml` 里 `card_*` 九个键完整落盘（地址/标题/路由/三段电量/三段充电态/降噪态）；
- 新发现缺陷：**流体云卡片上的按钮点不动**——卡片整体是一个 contentIntent 点击区，
  点按钮实际打开模块主页，`Live Alert low-latency requested` 日志从未出现，`dumpsys activity broadcasts`
  里也没有该 action（详见 `.local/recon/verification-20261030.md` §6.3）。已在第二轮修掉，见 §5。

Hook 签名核对（[coloros17-hook-signature-matrix-20261030.md](coloros17-hook-signature-matrix-20261030.md)）：
模块里全部反射目标逐条对固件 dump 核了一遍，**没有发现已被固件改签名而失效的 Hook**；
16.x 遗留 fragment 在 17.25.10 上确实不存在，由 `onHook()` 记为 `legacy.isFailure` 后自动走 Preference 路径。

自动检查与构建：641 项单元测试通过、Lint 0 error、Release 构建通过、1.9.3.0 覆盖安装到
PLK110 / ColorOS 17.0.0.105 / LSPosed API 102，四进程热重载 `restored=true failed=0`，
宿主 hook 安装日志正常、无异常栈。

## 5. 第二轮：把"死按钮"换成"一次点击就到位"

### 5.1 卡片点击改走耳机弹窗

`ColorOsLiveAlertReceiver.liveAlertContentIntent`：能拿到 `BluetoothDevice` 就发
`PopupActivity`（`chen.action.huaweipods.coloros.show_pods_ui` + `EXTRA_DEVICE`），拿不到再退回模块耳机详情页。
选弹窗而不是新写面板的理由：

- 弹窗里已经有真正能用的低时延开关和降噪档位，不用重造 UI；
- 弹窗自己尊重用户设置的 `notificationClickAction`，行为与点通知一致；
- activity start 一定会拉起被 OPLUS 缓存的进程，绕开 `BroadcastQueue setDeferPolicy … defer=true` 的推迟。

实测（`mRealCallingUid=10247` = SystemUI）：

```
ActivityTaskManager: START u0 {act=chen.action.huaweipods.coloros.show_pods_ui cat=[DEFAULT]
  cmp=…/moe.chenxy.huaweipods.PopupActivity (has extras) … from uid 10529}
```

注意操作路径：胶囊必须先点开成 `SeedlingCard` 浮卡（此时焦点是 `SeedlingCard`），再点浮卡正文才发 contentIntent；
在通知栏列表里直接点卡片正文，ColorOS 的首击常常被"防抖/展开"吃掉。

### 5.2 顺手修掉弹窗状态请求被拒的老 bug

`PopupActivity` 里 4 处 `sendBroadcast`（`PODS_UI_INIT` / `REFRESH_STATUS` ×2 / `ANC_REFRESH`）没走共享身份，
蓝牙进程侧的信任策略看到 `sentFromPackage=null` 直接拒：

```
LSPosedFramework: (com.android.bluetooth)[…,HuaweiPods-HuaweiHfp,…] Rejected untrusted broadcast
  action=chen.action.huaweipods.coloros.refresh_status sender=null
```

后果是弹窗三格电量恒为 `-`。改成 `sendIdentitySharingBroadcast` 后弹窗显示 `左 100% · 右 100% · 耳机盒 50%`，
蓝牙侧恢复 `Huawei RFCOMM battery updated device=30:A9:98:CB:C4:54`，拒绝日志消失。

这说明一件事：**凡是跨进程给宿主发请求，都必须带共享身份**，否则一律 fail-closed。
后续加新 action 时要按 `ColorOsLiveAlertActionReceiver` 的写法来，别用裸 `sendBroadcast`。

### 5.3 三段电量：数据通路打通，显示模式暂时关掉

`BatteryInfo` 构造是 public、`QuickCardDeviceData` 的 4 个电池槽是 `private final`（反射可写）、
视图侧有 `setBatteryDisplayMode(MULTI)` + `setBatteryPower(int)`——纸面全部可行，实做也行，
但**一进 `MULTI`，宿主会换成它自己的多段布局，耳机图变成内置的通用 TWS 图**（白色带柄），
我们注入的 `ImageView`（字段 `g`）不再是可见的那个。

所以本轮结论是：数据面先留着（`ColorOsPodBatteryStore` + 进程内电量接收者 + 10 个单测），
显示面用 `MULTI_BATTERY_CARD_ENABLED = false` 关掉。下一轮要做的不是"再切一次 MULTI"，
而是先搞清楚多段布局的取图字段（`mutiBatteryType` 的作用、它读 `icon` 还是另一个槽）。

**已经查清的那一半**（`dexdump -d` 实证，细节见
[hook 签名矩阵 §2.1](coloros17-hook-signature-matrix-20261030.md)）：`BlueToothCardView.k()` 在 `MULTI` 分支里
`0049: g = null` + `0051: return`，**根本不画英雄图**，屏幕上那张是 ViewStub 布局自带的默认图；
`m()` 的 `MULTI` 分支也只读 `ConnectState` 和单值 `e`，三段数值不经过视图。
对应代码已改进：`heroImage()` 在 `g == null` 时退到多段容器 `o` 里找面积最大的可见 `ImageView`，
开关仍关着等真机验证。

顺带修了两个真 bug（见 §5.4）。

### 5.4 电量接收者差点把耳机图刷回默认图

电量广播十几秒一次，最初让它顺手调 `refreshVisibleDetails()`。这个函数在 `route == null` 时
会 `restore()` 把已经替换好的耳机图还原成宿主原生图，于是"图一会儿就变成白色 AirPods"。
改成 `refreshVisibleBattery()`（只切显示模式 / 电量，不碰图片链路）。

另一处：`routeFor()` 把 `getRemoteDevice(...).name` 也包在同一个 `runCatching` 里，
宿主进程没 `BLUETOOTH_CONNECT` 时整条解析失败 → 图永远退回原生。现在名字只当辅助，取不到就用 prefs 里的路由。
