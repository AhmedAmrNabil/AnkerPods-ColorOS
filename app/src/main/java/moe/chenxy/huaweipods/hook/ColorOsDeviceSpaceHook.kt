package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import moe.chenxy.huaweipods.config.DeviceRoutePrefs
import moe.chenxy.huaweipods.config.PodImagePrefs
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.utils.PodImageLoader
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsActions

/** Verified against My Devices 17.25.10. Unknown host shapes retain their native rendering. */
object ColorOsDeviceSpaceHook : HookContext() {
    private const val TAG = "HuaweiPods-DeviceSpace"
    private const val CARD = "com.oplus.mydevices.bluetooth.BlueToothCardView"
    private const val BATTERY_INFO = "com.oplus.mydevices.domain.entities.device.BatteryInfo"
    private const val BATTERY_TYPE = "com.oplus.mydevices.domain.entities.device.BatteryType"
    private const val CARD_BATTERY_MODE = "com.oplus.mydevices.bluetooth.BluetoothCardBatteryDisplayMode"
    private const val CONVERTER = "com.oplus.mydevices.quickapp.data.QuickCardDeviceDataConverter"
    private const val DATA = "com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData"
    private const val DEVICE = "com.oplus.mydevices.domain.ports.device.IDevice"
    private const val DETAIL_ACTIVITY = "com.oplus.mydevices.bluetooth.PluginPanelHostActivity"
private const val HOME_CARD_BATTERY = "com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView"
private const val HOME_CARD = "com.oplus.mydevices.deviceui.devicecard.view.homecard.HomeCardView"

/** 首页卡片三段浮层的标记，用来在复用/重绑时找回自己插的那个 TextView。 */
private const val HOME_CARD_OVERLAY_TAG = "huaweipods_battery_overlay"

/** 低电时浮层的颜色（宿主那份 Lottie 被收起后，低电提示由我们自己给）。 */
private val LOW_BATTERY_COLOR = 0xFFFF5252.toInt()
    private val main = Handler(Looper.getMainLooper())
    private val requests = DeviceSpaceImageRequests<ImageView>()
    private val images = WeakHashMap<ImageView, OriginalImage>()
    private val cards = WeakHashMap<View, WeakReference<ImageView>>()
    private val scheduled = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val worker = ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, ArrayBlockingQueue(8), { task ->
        Thread(task, "HuaweiPods-DeviceSpaceImage").apply {
            isDaemon = true
            contextClassLoader = ClassLoader.getSystemClassLoader()
        }
    })
    @Volatile private var stopped = false
    @Volatile private var batteryShape: HomeCardBatteryShape? = null
    @Volatile private var applyingBattery = false
    private var batteryReceiver: BroadcastReceiver? = null
    private var batteryReceiverContext: Context? = null

    /**
     * 最近一次构建出来的卡片 DTO。
     *
     * 宿主只在数据变化时重建卡片（切页、连接状态变化），电量广播每十来秒一次，光靠 `h()`
     * 重跑根本等不到：卡片第一次构建时仓库必然是空的。所以这里留下弱引用，电量到了直接
     * 把新数据补进同一个 DTO，详情页下次读到的就是三段。
     */
    @Volatile private var lastCardData: java.lang.ref.WeakReference<Any>? = null
    @Volatile private var lastCardAddress: String? = null
    @Volatile private var lastCardShape: HomeCardBatteryShape? = null
    @Volatile private var lastBatterySignature: String? = null
    @Volatile private var lastGateSignature: String? = null
    @Volatile private var lastModeSignature: String? = null
    @Volatile private var viewDumped = false
    @Volatile private var cardTreeDumped = false
    private val overlaySkipLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun viewDumpDone(): Boolean = viewDumped

    private fun markViewDumpDone() {
        viewDumped = true
    }

    override fun onHook() {
        runCatching { hookHomeImages() }
            .onSuccess { Log.i(TAG, "Device Space home image hook installed") }
            .onFailure { Log.w(TAG, "Unknown home card shape; retaining stock images", it) }
        runCatching { hookDetailImages() }
            .onSuccess { Log.i(TAG, "Device Space detail image hook installed") }
            .onFailure { Log.w(TAG, "Unknown detail card shape; retaining stock images", it) }
        runCatching { hookApplicationCreate() }
            .onSuccess { Log.i(TAG, "Device Space battery receiver hook installed") }
            .onFailure { Log.w(TAG, "Unable to hook Application.onCreate for battery", it) }
        runCatching { hookHomeCardLayout() }
            .onSuccess { Log.i(TAG, "Device Space home card layout probe installed") }
            .onFailure { Log.w(TAG, "Home card layout probe unavailable", it) }
    }

    /**
     * 设备空间首页卡片是 `HomeCardView` + `BatteryLottieView`（Lottie 电量动画），
     * 既不读 `h()` 的 DTO 电池槽也不走 `BlueToothCardView`，所以要三段数字只能自己往卡里挂视图。
     * 这里先在 `BatteryLottieView` 构造完时把卡片内部结构打一次（一次性），
     * 拿到父容器和电量文本的真实 id 以后再决定往哪插。
     */
    private fun hookHomeCardLayout() {
        val type = findClass(HOME_CARD_BATTERY)
        // XML 里一般是两参构造，代码里 new 出来是 (Context, AttributeSet, int)，两个都挂上。
        val constructors = listOfNotNull(
            runCatching { type.getDeclaredConstructor(Context::class.java, AttributeSet::class.java) }
                .getOrNull(),
            runCatching {
                type.getDeclaredConstructor(Context::class.java, AttributeSet::class.java, Int::class.javaPrimitiveType!!)
            }.getOrNull(),
        )
        check(constructors.isNotEmpty()) { "no usable BatteryLottieView constructor" }
        constructors.forEach { constructor ->
            hookConstructorAfter(constructor) {
                (instance as? View)?.let { view -> main.post { dumpHomeCardTree(view) } }
            }
        }
    }

    private fun dumpHomeCardTree(battery: View) {
        if (cardTreeDumped) return
        cardTreeDumped = true
        runCatching {
            val builder = StringBuilder()
            var node: View = battery
            repeat(5) { level ->
                val parent = node.parent as? ViewGroup ?: return@repeat
                builder.append(" | L").append(level).append(' ')
                    .append(parent.javaClass.simpleName).append('#').append(idName(parent)).append(':')
                for (index in 0 until parent.childCount) {
                    val child = parent.getChildAt(index)
                    builder.append(' ').append(child.javaClass.simpleName)
                        .append('#').append(idName(child))
                    if (child is TextView) builder.append("=\"").append(child.text).append('"')
                    if (child is ViewGroup) builder.append('(').append(child.childCount).append(')')
                }
                node = parent
            }
            Log.i(TAG, "Device Space card tree$builder")
        }.onFailure { Log.w(TAG, "Unable to dump Device Space card tree", it) }
    }

    private fun idName(view: View): String = runCatching {
        if (view.id == View.NO_ID) "-" else view.resources.getResourceEntryName(view.id)
    }.getOrDefault("?")

    /**
     * `onPackageLoaded` 阶段宿主还没有 Application，拿不到 Context，所以电量接收者挂在
     * `Application.onCreate` 之后再注册；卡片首次构造时也会补一次（幂等）。
     */
    private fun hookApplicationCreate() {
        hookAfter(android.app.Application::class.java.getDeclaredMethod("onCreate")) {
            (instance as? Context)?.let { context ->
                runCatching { registerBatteryReceiver(context) }
                    .onFailure { Log.w(TAG, "Unable to register Device Space battery receiver", it) }
            }
        }
    }

    /**
     * 电量广播在宿主两个进程（`com.heytap.mydevices` 与 `:cards`）都会到达，
     * 卡片渲染在 `:cards`，所以每个进程都要自己收一份，不能只靠「我的设备」页的接收者。
     */
    private fun registerBatteryReceiver(context: Context) {
        if (batteryReceiver != null) return
        // 拿到 Context 就把运行时开关接上：三段电量的两个开关读 Settings.Global，改设置几秒内生效。
        ColorOsBatteryFlags.attach(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val address = intent.getStringExtra("address")?.uppercase()
                when (intent.action) {
                    HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        val target = address ?: return
                        val pod = ColorOsPodBattery(
                            address = target,
                            left = intent.getIntExtra("left_battery", 0),
                            leftConnected = intent.getBooleanExtra("left_connected", false),
                            right = intent.getIntExtra("right_battery", 0),
                            rightConnected = intent.getBooleanExtra("right_connected", false),
                            case = intent.getIntExtra("case_battery", 0),
                            caseConnected = intent.getBooleanExtra("case_connected", false),
                            name = intent.getStringExtra("device_name")?.takeIf(String::isNotBlank),
                        )
                        ColorOsPodBatteryStore.put(pod)
                        val signature = "$target left=${pod.left}/${pod.leftConnected} " +
                            "right=${pod.right}/${pod.rightConnected} case=${pod.case}/${pod.caseConnected}"
                        if (signature != lastBatterySignature) {
                            lastBatterySignature = signature
                            Log.i(TAG, "Device Space battery event $signature")
                        }
                    }
                    HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> ColorOsPodBatteryStore.clear(address)
                    else -> return
                }
                // 只刷电量：图片刷新链路会按 host 原生图 restore，电量广播每十几秒一次，
                // 走图片路径会把已经替换好的耳机图反复还原回宿主默认图。
                main.post {
                    refreshVisibleBattery()
                    reapplyLastCardBattery()
                }
            }
        }
        val filter = IntentFilter().addHuaweiPodsActions(
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
        )
        // 电量广播来自蓝牙进程（共享身份广播），宿主侧必须显式 EXPORTED 才能收到。
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        batteryReceiver = receiver
        batteryReceiverContext = context
    }

    private fun hookHomeImages() {
        val dataType = findClass(DATA)
        listOf("icon", "name", "originalIconUri").forEach {
            check(dataType.getDeclaredField(it).type == String::class.java)
        }
        val deviceType = findClass(DEVICE)
        val addressGetter = deviceType.getMethod("getMacAddress").apply {
            check(returnType == String::class.java && parameterCount == 0)
        }
        val battery = homeCardBatteryShape(dataType)
        batteryShape = battery
        val method = findMethod(CONVERTER, "h", deviceType, findClass("$CONVERTER\$c"),
            findClass("$CONVERTER\$e"), findClass("$CONVERTER\$d"), Boolean::class.javaPrimitiveType!!)
        check(method.returnType == dataType)
        hookAfter(method) {
            runCatching {
                // h returns a freshly allocated presentation DTO. Never change the IDevice or database.
                val address = addressGetter.invoke(args[0]) as? String
                if (address == null) {
                    Log.i(TAG, "Device Space h() skipped: no address")
                    return@runCatching
                }
                val app = currentApplicationOrNull()
                if (app == null) {
                    Log.i(TAG, "Device Space h() skipped: no Application address=$address")
                    return@runCatching
                }
                val route = routeFor(app, address)
                if (route == null) {
                    Log.i(TAG, "Device Space h() skipped: unsupported route address=$address")
                    return@runCatching
                }
                val data = result ?: return@runCatching
                val title = getObjectField(data, "name") as? String ?: return@runCatching
                val uri = imageUri(address, route)
                setObjectField(data, "icon", uri)
                setObjectField(data, "originalIconUri", uri)
                setObjectField(data, "name", deviceSpaceCardTitle(title, route))
                // 三段电量：数据来自本进程收到的电量广播，DTO 只是展示层，改完不影响 IDevice / 数据库。
                runCatching { registerBatteryReceiver(app) }
                applyCardBattery(data, address, battery)
            }.onFailure { Log.w(TAG, "Unable to customize Device Space card", it) }
        }
        hookBatteryDisplayMode(battery)
    }

    /**
     * 固件里 `BatteryInfo(BatteryType, int value, boolean charge)` 的公共构造 + 卡片显示模式枚举
     * `BluetoothCardBatteryDisplayMode{SINGLE, MULTI}`。
     */
    private class HomeCardBatteryShape(
        batteryTypeClass: Class<*>,
        private val constructor: java.lang.reflect.Constructor<*>,
        val viewModeClass: Class<*>?,
    ) {
        private val typeOfChannel: Map<String, Any> = listOf(
            ColorOsBatteryTriple.CHANNEL_LEFT,
            ColorOsBatteryTriple.CHANNEL_RIGHT,
            ColorOsBatteryTriple.CHANNEL_BOX,
        ).associateWith { batteryTypeClass.getField(it).get(null) }

        fun info(channel: String, value: Int): Any = constructor.newInstance(
            typeOfChannel[channel] ?: typeOfChannel.getValue(ColorOsBatteryTriple.CHANNEL_LEFT),
            value.coerceIn(0, 100),
            false,
        )
    }

    /**
     * 三段电量：**数据面**和**展示面**分开开关，别再用一个开关管两件事。
     *
     * - `BIT_DTO`：只往 `QuickCardDeviceData` 的 4 个 `BatteryInfo` 槽位写数据。
     *   实测固件里这些槽位的消费者只有 quickapp 首页卡模板（`aa.s7` / `aa.La0`，见签名矩阵 §2.2），
     *   蓝牙卡片 `BlueToothCardView` 只读单值 `e`，所以单开这一项**不会**改变卡片外观，
     *   是用来验证"详情页/quickapp 卡片是否原生吃这三段"的最小开关。
     * - `BIT_CARD`：把卡片视图切到 `MULTI` 并自己填多格块。切过去以后宿主不再画设备图
     *   （`j()` 把所有 `bt_*_imageView` 置 GONE），所以这一项必须等"图怎么补"定下来再开。
     *
     * 两个开关都是**运行时**读 `Settings.Global/huaweipods_multi_battery`（默认 0 = 全关），
     * 真机验证时用 `adb shell settings put global huaweipods_multi_battery 1` 直接切，不用重装。
     */
    private fun cardBatteryEnabled(): Boolean = ColorOsBatteryFlags.cardEnabled()

    /**
     * `QuickCardDeviceData` 的四个 `BatteryInfo` 槽位：left/right 是左右耳，main 放盒子（没有盒子时放较大的那只），
     * min 放最小那一段——固件用它决定低电提示。
     *
     * 为什么只在这一层注入：`h()` 内部的电池其实是 `$c.b()` -> `$b.a()/d()` 拿来的，看着更"源头"，
     * 但 `$b` 不带地址（对所有蓝牙设备生效），注进去会把别的设备卡片写坏；`h()` 返回的 DTO 和
     * `BlueToothCardView`（有地址字段 `c`）才是唯一带地址作用域的注入面。
     */
    private fun applyCardBattery(data: Any, address: String, shape: HomeCardBatteryShape) {
        // 开关要读 Settings.Global（adb 覆盖）和模块配置，两者都需要宿主 Context；
        // 卡片绑定不一定发生在 Application.onCreate 之后，所以这里再兜一次。
        ColorOsBatteryFlags.attach(currentApplicationOrNull())
        // 卡片 DTO 只在数据变化时重建，所以留一份弱引用供电量广播补写。
        lastCardData = java.lang.ref.WeakReference(data)
        lastCardAddress = address
        lastCardShape = shape
        val snapshot = ColorOsPodBatteryStore.get(address)
        val mask = ColorOsBatteryFlags.mask()
        val gate = "mask=$mask storeHit=${snapshot != null} " +
            "left=${snapshot?.left} right=${snapshot?.right} case=${snapshot?.case}"
        if (gate != lastGateSignature) {
            lastGateSignature = gate
            Log.i(TAG, "Device Space DTO gate $gate address=$address")
        }
        if (mask and (ColorOsBatteryFlags.BIT_DTO or ColorOsBatteryFlags.BIT_CARD) == 0) return
        val triple = colorOsBatteryTriple(snapshot) ?: return
        // 缺的段不写：宿主对没写过的槽位保持自己的处理，塞 0 反而会显示成"0%"。
        triple.left?.let { setObjectField(data, "batteryLeft", shape.info(ColorOsBatteryTriple.CHANNEL_LEFT, it)) }
        triple.right?.let { setObjectField(data, "batteryRight", shape.info(ColorOsBatteryTriple.CHANNEL_RIGHT, it)) }
        val third = triple.case
            ?.let { ColorOsBatteryPart(ColorOsBatteryTriple.CHANNEL_BOX, it) }
            ?: triple.mainPart
        setObjectField(data, "batteryMain", shape.info(third.channel, third.value))
        setObjectField(data, "batteryMin", shape.info(triple.minPart.channel, triple.minPart.value))
        // 只有一段时告诉宿主"不支持多段"，免得它自己切多段布局后显示成空块。
        setObjectField(data, "isSupportMutiBattery", triple.isMulti)
        setObjectField(data, "isShowBattery", true)
        Log.i(
            TAG,
            "Device Space card battery left=${triple.left} right=${triple.right} case=${triple.case} multi=${triple.isMulti}",
        )
    }

    /** 电量到了以后，把新数据补写进最近一次构建的卡片 DTO（弱引用没了就等下次重建）。 */
    private fun reapplyLastCardBattery() {
        val data = lastCardData?.get() ?: return
        val address = lastCardAddress ?: return
        val shape = lastCardShape ?: return
        runCatching { applyCardBattery(data, address, shape) }
            .onFailure { Log.w(TAG, "Unable to refresh Device Space DTO battery", it) }
    }

    /** 卡片视图侧：宿主按 `SINGLE` 只画一个数字，这里在它 bind 完以后切成 `MULTI`。 */
    private fun applyCardBatteryMode(card: View, shape: HomeCardBatteryShape) {
        if (applyingBattery) return
        val enabled = cardBatteryEnabled()
        val modeClass = shape.viewModeClass
        val address = getObjectField(card, "c") as? String
        val snapshot = address?.let { ColorOsPodBatteryStore.get(it) }
        val triple = colorOsBatteryTriple(snapshot)
        val signature = "enabled=$enabled modeClass=${modeClass != null} address=$address " +
            "storeHit=${snapshot != null} isMulti=${triple?.isMulti}"
        if (signature != lastModeSignature) {
            lastModeSignature = signature
            Log.i(TAG, "Device Space card mode $signature")
        }
        if (!enabled) return
        if (modeClass == null) return
        if (address == null) return
        // 一段电量切多段布局只会得到一块空着的模板，不值得切。
        if (triple == null || !triple.isMulti) return
        val mode = runCatching { modeClass.getMethod("valueOf", String::class.java).invoke(null, "MULTI") }
            .getOrNull() ?: return
        applyingBattery = true
        try {
            card.javaClass.getMethod("setBatteryDisplayMode", modeClass).invoke(card, mode)
            card.javaClass.getMethod("setBatteryPower", Int::class.javaPrimitiveType).invoke(card, triple.main)
            applyMultiBatteryBlock(card, triple)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to switch Device Space card to multi battery", error)
        } finally {
            applyingBattery = false
        }
    }

    /**
     * 宿主切到 `MULTI` 之后只负责把多段容器显示出来（`k()` 里 `o.VISIBLE` 然后直接 return），
     * 四格数字要自己填。容器是 ViewStub（`id/stub_airpods_multiline_battery`）按需 inflate 的
     * `layout/include_bluetooth_card_airpods_battery_block`，id 名已用 aapt2 从固件里核对过
     * （见 [ColorOsAirpodsBatteryBlock]）。
     */
    private fun applyMultiBatteryBlock(card: View, triple: ColorOsBatteryTriple) {
        val root = multiBatteryRoot(card) ?: return
        val slots = ColorOsAirpodsBatteryBlock.slots(triple.left, triple.right, triple.case)
        // 先把所有行按"有没有数据"摆好，再填内容：缺的那段整行隐藏，而不是写 0%。
        val present = slots.map { it.first.label }.toHashSet()
        listOf(
            ColorOsAirpodsBatteryBlock.MAIN,
            ColorOsAirpodsBatteryBlock.LEFT,
            ColorOsAirpodsBatteryBlock.RIGHT,
            ColorOsAirpodsBatteryBlock.CASE,
        ).forEach { slot ->
            if (slot.label !in present) {
                slot.container?.let { hostView(root, it)?.visibility = View.GONE }
            }
        }
        slots.forEach { (slot, value) ->
            bindSlot(root, slot, value)
            Log.d(TAG, "Multi battery slot=${slot.label} value=$value")
        }
    }

    /** 多段容器：宿主已经 inflate 过就在字段 `o` 里，否则自己把 ViewStub 展开。 */
    private fun multiBatteryRoot(card: View): View? {
        (getObjectField(card, "o") as? View)?.let { return it }
        val id = hostId(card, "stub_airpods_multiline_battery")
        if (id == 0) return null
        val stub = card.findViewById<View>(id) as? ViewStub ?: return null
        return runCatching { stub.inflate() }.getOrNull()
    }

    private fun bindSlot(root: View, slot: ColorOsAirpodsBatteryBlock.Slot, value: Int?) {
        slot.container?.let { hostView(root, it)?.visibility = View.VISIBLE }
        hostView(root, slot.label)?.let { view ->
            (view as? TextView)?.text = ColorOsAirpodsBatteryBlock.label(value)
            view.visibility = View.VISIBLE
        }
        slot.progress?.let { name ->
            hostView(root, name)?.let { view ->
                (view as? ProgressBar)?.progress = ColorOsAirpodsBatteryBlock.progress(value)
                view.visibility = View.VISIBLE
            }
        }
        // 充电图标来自我们自己的电量快照时一律隐藏：快照里没有充电位，宁可少画也不画错。
        slot.charge?.let { name ->
            hostView(root, name)?.visibility =
                if (ColorOsAirpodsBatteryBlock.shouldShowCharging(value, charging = false)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }
        slot.low?.let { name ->
            hostView(root, name)?.visibility =
                if (ColorOsAirpodsBatteryBlock.shouldShowLow(value)) View.VISIBLE else View.GONE
        }
    }

    private fun hostView(root: View, name: String): View? {
        val id = hostId(root, name)
        return if (id == 0) null else runCatching { root.findViewById<View>(id) }.getOrNull()
    }

    /** 宿主资源 id 按名字取：R 类字段被 R8 改成 `H1`/`P` 这种短名，硬编码不可维护。 */
    private fun hostId(view: View, name: String): Int = runCatching {
        view.resources.getIdentifier(name, "id", view.context.packageName)
    }.getOrDefault(0)

    private fun homeCardBatteryShape(dataType: Class<*>): HomeCardBatteryShape {
        val batteryInfoType = findClass(BATTERY_INFO)
        listOf("batteryLeft", "batteryRight", "batteryMain", "batteryMin").forEach {
            check(dataType.getDeclaredField(it).type == batteryInfoType)
        }
        check(dataType.getDeclaredField("isSupportMutiBattery").type == Boolean::class.javaPrimitiveType)
        val batteryTypeClass = findClass(BATTERY_TYPE)
        check(batteryTypeClass.isEnum)
        val constructor = batteryInfoType.getDeclaredConstructor(
            batteryTypeClass,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val viewModeClass = runCatching { findClass(CARD_BATTERY_MODE) }.getOrNull()
        return HomeCardBatteryShape(batteryTypeClass, constructor, viewModeClass)
    }

    private fun hookBatteryDisplayMode(battery: HomeCardBatteryShape) {
        // k 在 bind / 复用时会重画电量；必须在宿主画完以后再切 MULTI，否则会被它覆盖回 SINGLE。
        val bind = findMethod(CARD, "k").apply { check(returnType == Void.TYPE) }
        hookAfter(bind) { (instance as? View)?.let { applyCardBatteryMode(it, battery) } }
        // 宿主有时在 k() 之后单独调 setBatteryPower；再拦一次，写入自己的值（用 applyingBattery 防递归）。
        val power = findMethod(CARD, "setBatteryPower", Int::class.javaPrimitiveType!!)
        hookAfter(power) { (instance as? View)?.let { applyCardBatteryMode(it, battery) } }
    }

    private fun hookDetailImages() {
        val type = findClass(CARD)
        check(View::class.java.isAssignableFrom(type))
        check(type.getDeclaredField("c").type == String::class.java)
        check(type.getDeclaredField("g").type == ImageView::class.java)
        // k initializes/replaces the native image; the setters also run during recycler rebinding.
        val methods = listOf(findMethod(CARD, "k"),
            findMethod(CARD, "setBluetoothAddress", String::class.java),
            findMethod(CARD, "setDeviceName", String::class.java))
        check(methods.all { it.returnType == Void.TYPE })
        // 这三个方法在真机上都会跑（`k()` 只是其中之一），电量模式跟着它们走：
        // 只挂 `k()`/`setBatteryPower` 时实测一次都没触发，卡片就一直停在 INTEGER/SINGLE。
        methods.forEach { method ->
            hookAfter(method) {
                (instance as? View)?.let { view ->
                    schedule(view)
                    batteryShape?.let { shape -> applyCardBatteryMode(view, shape) }
                }
            }
        }
        // Hot reload does not replay native view binding. An existing panel must be
        // rediscovered after old images are restored, and again when a paused panel resumes.
        hookAfter(Activity::class.java.getDeclaredMethod("onPostResume")) {
            (instance as? Activity)?.takeIf { it.javaClass.name == DETAIL_ACTIVITY }
                ?.let { activity -> main.post { refreshActivity(activity) } }
        }
        main.post { refreshVisibleDetails() }
    }

    /**
     * 首页卡片三段电量浮层。
     *
     * 卡片里能拿到的只有设备名（没有地址），所以用"卡片名 ↔ 广播里的 device_name"做规范化包含匹配
     * （[ColorOsHomeCardBattery.matches]），配不上就什么都不画 —— 宁可不显示，也不能把别的设备电量写上去。
     *
     * 插在 `BatteryLottieView` 同一个父容器里、紧跟它后面：父容器是 `LinearLayout` 才动手，
     * 别的布局插进去会挤坏宿主排版，所以只在 `LinearLayout` 上做，并且只记一次日志说明跳过了什么。
     */
    private fun applyHomeCardBattery(anchor: View) {
        if (!cardBatteryEnabled()) return
        val card = homeCardAncestor(anchor) ?: return
        val nameText = homeCardNameText(card)
        val pod = ColorOsPodBatteryStore.snapshot().firstOrNull {
            ColorOsHomeCardBattery.matches(nameText, it.name)
        }
        val triple = colorOsBatteryTriple(pod)
        val text = triple?.let { ColorOsHomeCardBattery.textFor(it.left, it.right, it.case) }
        val parent = anchor.parent as? ViewGroup ?: return
        val existing = parent.findViewWithTag<View>(HOME_CARD_OVERLAY_TAG)
        // 真机卡片结构（`Device Space card tree`）：singleBatteryContainer(LinearLayout)
        //   = batteryView(BatteryLottieView) + batteryText(TextView="100")
        // 只收宿主那个数字，保留 Lottie 电池图标。
        val hostNumber = homeCardBatteryText(parent, anchor)
        if (text == null) {
            existing?.visibility = View.GONE
            hostNumber?.visibility = View.VISIBLE
            return
        }
        if (existing is TextView) {
            if (existing.text?.toString() != text) existing.text = text
            existing.visibility = View.VISIBLE
            applyOverlayColor(existing, card, triple)
            hostNumber?.visibility = View.GONE
            return
        }
        if (parent !is LinearLayout) {
            if (overlaySkipLogged.compareAndSet(false, true)) {
                Log.i(TAG, "Device Space home overlay skipped parent=${parent.javaClass.name} text=$text")
            }
            return
        }
        val label = TextView(anchor.context).apply {
            tag = HOME_CARD_OVERLAY_TAG
            this.text = text
            textSize = 11f
            isSingleLine = true
            maxLines = 1
            setPadding(6, 0, 0, 0)
        }
        applyOverlayColor(label, card, triple)
        parent.addView(
            label,
            parent.indexOfChild(anchor) + 1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        anchor.visibility = View.VISIBLE
        hostNumber?.visibility = View.GONE
        Log.i(TAG, "Device Space home overlay attached name=$nameText text=$text")
    }

    /** 宿主自己那个纯数字电量文本（`batteryText`，内容形如 `100`），浮层显示时收起来。 */
    private fun homeCardBatteryText(parent: ViewGroup, anchor: View): TextView? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child === anchor || child.tag == HOME_CARD_OVERLAY_TAG) continue
            if (child !is TextView) continue
            val raw = child.text?.toString()?.trim().orEmpty()
            if (raw.isNotEmpty() && raw.all { it.isDigit() }) return child
        }
        return null
    }

    /** 任一耳机低于阈值就把浮层标红，顶掉被收起来的 Lottie 低电配色。 */
    private fun applyOverlayColor(label: TextView, card: ViewGroup, triple: ColorOsBatteryTriple?) {
        val low = triple != null && triple.minimum < ColorOsAirpodsBatteryBlock.LOW_THRESHOLD
        label.setTextColor(if (low) LOW_BATTERY_COLOR else homeCardTextColor(card))
    }

    private fun homeCardAncestor(anchor: View): ViewGroup? {
        var node: View? = anchor
        repeat(8) {
            node = node?.parent as? View
            val current = node ?: return null
            if (current.javaClass.name == HOME_CARD) return current as? ViewGroup
        }
        return null
    }

    /** 卡片里第一个"像设备名"的文本：含字母、长度 ≥ 2，避开电量数字。 */
    private fun homeCardNameText(card: ViewGroup): String? {
        val pending = ArrayDeque<View>()
        pending.add(card)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 128) {
            val view = pending.removeFirst()
            if (view === card) {
                // 容器本身不取
            } else if (view is TextView) {
                val raw = view.text?.toString()?.trim().orEmpty()
                if (raw.length >= 2 && raw.any { it.isLetter() }) return raw
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> pending.add(view.getChildAt(index)) }
            }
        }
        return null
    }

    private fun homeCardTextColor(card: ViewGroup): Int {
        val pending = ArrayDeque<View>()
        pending.add(card)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 128) {
            val view = pending.removeFirst()
            if (view is TextView && view.tag != HOME_CARD_OVERLAY_TAG) return view.currentTextColor
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> pending.add(view.getChildAt(index)) }
            }
        }
        return 0xFF999999.toInt()
    }

    private fun refreshVisibleDetails() {
        if (stopped) return
        runCatching {
            // The COUI detail sheet owns a Dialog window outside Activity.decorView.
            // Inspect only this host process's windows, and match the verified native card class.
            val windowType = Class.forName("android.view.WindowManagerGlobal")
            val manager = windowType.getDeclaredMethod("getInstance").invoke(null)
            val roots = (getObjectField(manager, "mViews") as? List<*>)
                ?.filterIsInstance<View>()?.take(16) ?: return@runCatching
            val count = roots.sumOf(::refreshRoot)
            Log.i(TAG, "Existing detail image recovery roots=${roots.size} cards=$count")
        }.onFailure { Log.w(TAG, "Unable to recover visible Device Space panels", it) }
    }

    /** 只重刷三段电量，不碰图片链路（图片链路在 route 解析失败时会把图还原成宿主原生图）。 */
    private fun refreshVisibleBattery() {
        if (stopped) return
        val shape = batteryShape ?: return
        runCatching {
            val windowType = Class.forName("android.view.WindowManagerGlobal")
            val manager = windowType.getDeclaredMethod("getInstance").invoke(null)
            val roots = (getObjectField(manager, "mViews") as? List<*>)
                ?.filterIsInstance<View>()?.take(16) ?: return@runCatching
            val seen = mutableSetOf<String>()
            var cards = 0
            roots.forEach { root ->
                if (!root.isAttachedToWindow || root.windowVisibility != View.VISIBLE) return@forEach
                val pending = ArrayDeque<View>()
                pending.add(root)
                var visited = 0
                while (pending.isNotEmpty() && visited++ < 512) {
                    val view = pending.removeFirst()
                    if (!viewDumpDone()) {
                        val name = view.javaClass.name
                        if (name.contains("ard", ignoreCase = true) || name.contains("luetooth")) {
                            seen += name
                        }
                    }
                    if (view.javaClass.name == CARD) {
                        cards++
                        applyCardBatteryMode(view, shape)
                    }
                    // 首页卡片：宿主只画一个 Lottie 数字，三段靠自己挂一行文本（见 ColorOsHomeCardBattery）。
                    if (view.javaClass.name == HOME_CARD_BATTERY) applyHomeCardBattery(view)
                    if (view is ViewGroup) {
                        repeat(minOf(view.childCount, (512 - visited - pending.size).coerceAtLeast(0))) { index ->
                            pending.add(view.getChildAt(index))
                        }
                    }
                }
            }
            if (!viewDumpDone()) {
                markViewDumpDone()
                Log.i(TAG, "Device Space view dump cards=$cards classes=${seen.take(40)}")
            }
        }.onFailure { Log.w(TAG, "Unable to refresh Device Space card battery", it) }
    }

    private fun refreshActivity(activity: Activity) {
        if (stopped || activity.javaClass.name != DETAIL_ACTIVITY || activity.isDestroyed || activity.isFinishing) return
        refreshVisibleDetails()
    }

    private fun refreshRoot(root: View): Int {
        if (!root.isAttachedToWindow || root.windowVisibility != View.VISIBLE) return 0
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        var matches = 0
        while (pending.isNotEmpty() && visited++ < 512) {
            val view = pending.removeFirst()
            if (view.javaClass.name == CARD) {
                schedule(view)
                batteryShape?.let { applyCardBatteryMode(view, it) }
                matches++
            }
            if (view is ViewGroup) {
                repeat(minOf(view.childCount, (512 - visited - pending.size).coerceAtLeast(0))) { index ->
                    pending.add(view.getChildAt(index))
                }
            }
        }
        return matches
    }

    private fun schedule(card: View) {
        if (stopped) return
        cards[card]?.get()?.let(requests::invalidate)
        if (!scheduled.add(card)) return
        val reference = WeakReference(card)
        main.post {
            val current = reference.get() ?: return@post
            scheduled.remove(current)
            if (!stopped) runCatching { updateDetailImage(current) }
                .onFailure { Log.w(TAG, "Unable to update detail image", it) }
        }
    }

    /**
     * 卡片里的"英雄图"位图落点。
     *
     * `BlueToothCardView.k()` 在 MULTI 模式下会把字段 `g`（`SINGLE` 的 ImageView）**置为 null**，
     * 然后提前 return（dexdump 实证：`0049: iput-object v0(=null), g`、`0051: return-void`），
     * 此时可见的耳机图来自按需 inflate 的多段容器（字段 `o`，ViewStub）里的 ImageView。
     * 只认 `g` 的话，多段布局下永远贴不上我们的图，屏幕上留着宿主内置的通用 TWS 图。
     */
    private fun heroImage(card: View): ImageView? {
        (getObjectField(card, "g") as? ImageView)?.let { return it }
        val container = getObjectField(card, "o") as? ViewGroup ?: return null
        // 多段块里宿主留了一个 airpods_image（AirPods 产品渲染），按名字取最稳，取不到再按面积兜底。
        (hostView(container, ColorOsAirpodsBatteryBlock.PRODUCT_IMAGE) as? ImageView)
            ?.takeIf { it.isAttachedToWindow }
            ?.let { return it }
        return largestImageView(container)
    }

    private fun largestImageView(root: ViewGroup): ImageView? {
        var best: ImageView? = null
        var bestArea = 0
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 128) {
            val view = pending.removeFirst()
            if (view is ImageView && view.visibility == View.VISIBLE && view.width * view.height > bestArea) {
                best = view
                bestArea = view.width * view.height
            }
            if (view is ViewGroup) {
                repeat(minOf(view.childCount, (128 - visited - pending.size).coerceAtLeast(0))) { index ->
                    pending.add(view.getChildAt(index))
                }
            }
        }
        return best
    }

    private fun updateDetailImage(card: View) {
        val address = getObjectField(card, "c") as? String
        val route = address?.let { routeFor(card.context, it) }
        val image = heroImage(card)
        val previous = cards[card]?.get()
        if (previous != null && (previous !== image || route == null)) restore(previous)
        if (address == null || route == null || image == null) {
            cards.remove(card)
            return
        }
        cards[card] = WeakReference(image)
        val identity = imageUri(address, route)
        val ticket = requests.bind(image, identity) ?: return
        fun apply(bitmap: Bitmap) {
            val state = images.getOrPut(image) { OriginalImage(image.drawable, image.scaleType) }
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setImageBitmap(bitmap)
            state.applied = image.drawable
        }
        cache.get(identity)?.takeUnless(Bitmap::isRecycled)?.let { apply(it); return }
        val reference = WeakReference(image)
        val app = card.context.applicationContext ?: card.context
        runCatching {
            worker.execute {
                val bitmap = runCatching { PodImageLoader.loadDeviceSpaceBitmap(app, address, route) }.getOrNull()
                if (stopped) return@execute
                if (bitmap == null) {
                    Log.w(TAG, "Detail image decode unavailable; retaining native image")
                    return@execute
                }
                cache.put(identity, bitmap)
                main.post {
                    val target = reference.get() ?: return@post
                    if (requests.isCurrent(target, ticket) && !bitmap.isRecycled) {
                        val state = images.getOrPut(target) { OriginalImage(target.drawable, target.scaleType) }
                        target.scaleType = ImageView.ScaleType.FIT_CENTER
                        target.setImageBitmap(bitmap)
                        state.applied = target.drawable
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Image queue unavailable; retaining native image") }
    }

    @SuppressLint("MissingPermission")
    private fun routeFor(context: Context, address: String): HuaweiDeviceRoute? {
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) return null
        // 名字只是辅助：宿主没有 BLUETOOTH_CONNECT 时 getRemoteDevice(...).name 会抛异常，
        // 之前整条 routeFor 因此返回 null，卡片图就退回宿主原生图。
        val rawName = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?.getRemoteDevice(address)?.name
        }.getOrNull()
        return runCatching { DeviceRoutePrefs.resolve(prefs, address, rawName).takeIf { it.isSupported } }
            .getOrNull()
    }

    private fun imageUri(address: String, route: HuaweiDeviceRoute): String {
        val record = PodImagePrefs.find(prefs, address)
        val revision = listOf(record?.boxImagePath, record?.cloudBoxImagePath,
            record?.cloudModelId, record?.cloudSubModelId).hashCode()
        return PodImagePrefs.deviceSpaceImageUri(address, route, revision).toString()
    }

    private fun restore(image: ImageView) {
        requests.invalidate(image)
        images.remove(image)?.let { state ->
            if (image.drawable === state.applied) {
                image.setImageDrawable(state.drawable)
                image.scaleType = state.scaleType
            }
        }
    }

    override fun onCanClose(): Boolean = worker.activeCount == 0 && worker.queue.isEmpty()

    override fun onClose() {
        stopped = true
        batteryReceiver?.let { receiver -> runCatching { batteryReceiverContext?.unregisterReceiver(receiver) } }
        batteryReceiver = null
        batteryReceiverContext = null
        batteryShape = null
        // 先还原宿主图片再等线程退出：等待可能超时，而宿主不能继续挂着我们的 Bitmap。
        val restored = runOnMainThreadBlocking {
            main.removeCallbacksAndMessages(null)
            images.keys.toList().forEach(::restore)
            cards.clear()
            scheduled.clear()
            cache.evictAll()
        }
        if (!restored) Log.w(TAG, "Unable to restore Device Space images on main thread")
        requests.close()
        worker.shutdownNow()
        if (!worker.awaitTermination(1_500L, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Device Space image worker did not stop within 1.5s")
        }
    }

    private data class OriginalImage(val drawable: Drawable?, val scaleType: ImageView.ScaleType,
        var applied: Drawable? = null)
}
