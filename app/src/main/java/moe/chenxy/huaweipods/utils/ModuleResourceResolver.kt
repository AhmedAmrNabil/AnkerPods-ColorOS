package moe.chenxy.huaweipods.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import moe.chenxy.huaweipods.BuildConfig

/** 检测 APK 覆盖安装后，宿主进程是否仍在运行旧版 Hook dex。 */
object ModuleResourceResolver {
    @Volatile
    private var hotReloadApplicationInfo: ApplicationInfo? = null

    /** API 102 的新代 Hook 使用框架交付的 ApplicationInfo 校验版本，避开宿主 PM 缓存。 */
    fun initialize(moduleApplicationInfo: ApplicationInfo, hotReloadEnabled: Boolean) {
        hotReloadApplicationInfo = moduleApplicationInfo.takeIf { hotReloadEnabled }
    }

    /**
     * 资源也固定到 API 102 当前代 APK，避免长期宿主进程复用旧资源缓存。
     *
     * 覆盖安装后宿主进程里这两条路径都可能抛异常（日志里表现为
     * `skip notification: module resources unavailable` 而通知一直不出现）。
     * 早期实现用 `runCatching{}.getOrNull()` 把异常吞掉了，只能看到"拿不到"，
     * 看不到**为什么**拿不到。这里改成：每条路径各自尝试、各自记录异常，
     * 同一条路径只在首次失败时打一条日志（避免电量刷新时刷屏）。
     */
    fun resources(hostContext: Context): Resources? {
        val applicationInfo = hotReloadApplicationInfo
        if (applicationInfo != null) {
            attempt("framework ApplicationInfo", hostContext) {
                hostContext.packageManager.getResourcesForApplication(applicationInfo)
            }?.let { return it }
        }
        attempt("createPackageContext", hostContext) {
            hostContext.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY,
            ).resources
        }?.let { return it }
        // 最后一条兜底：直接按包名问 PM 要资源，绕过 createPackageContext 的 LoadedApk 缓存。
        return attempt("getResourcesForApplication", hostContext) {
            hostContext.packageManager.getResourcesForApplication(BuildConfig.APPLICATION_ID)
        }
    }

    private inline fun attempt(
        path: String,
        hostContext: Context,
        block: () -> Resources,
    ): Resources? = try {
        block()
    } catch (error: Throwable) {
        if (reported.add(path)) {
            android.util.Log.w(
                TAG,
                "Module resources via $path failed for host ${hostContext.packageName}",
                error,
            )
        }
        null
    }

    fun isCurrentModuleBuild(hostContext: Context): Boolean {
        hotReloadApplicationInfo?.let { applicationInfo ->
            if (applicationInfo.packageName != BuildConfig.APPLICATION_ID) return false
            if (applicationInfo.sourceDir.isNullOrBlank()) return false
            val frameworkBuildId = applicationInfo.metaData?.getString(MODULE_BUILD_ID_META_DATA)
            return frameworkBuildId == null || moduleBuildMatches(
                frameworkBuildId,
                BuildConfig.MODULE_BUILD_ID,
            )
        }
        val installedBuildId = runCatching {
                hostContext.packageManager.getApplicationInfo(
                    BuildConfig.APPLICATION_ID,
                    PackageManager.GET_META_DATA,
                ).metaData?.getString(MODULE_BUILD_ID_META_DATA)
            }.getOrNull()
        return moduleBuildMatches(installedBuildId, BuildConfig.MODULE_BUILD_ID)
    }

    internal fun moduleBuildMatches(installedBuildId: String?, hookBuildId: String): Boolean =
        !installedBuildId.isNullOrBlank() && installedBuildId == hookBuildId

    private const val TAG = "HuaweiPods-Resources"

    /** 同一进程内每条取资源路径只抱怨一次。 */
    private val reported = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    private const val MODULE_BUILD_ID_META_DATA =
        "io.github.artifical0.huaweipods.coloros.MODULE_BUILD_ID"
}
