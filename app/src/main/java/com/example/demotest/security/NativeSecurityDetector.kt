package com.example.demotest.security

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 风控统一采集入口：Native C 层核心检测 + Kotlin 层辅助特征采集。
 *
 * 设计原则（《Android 客户端风控检测方案》）：
 * 1. 分层检测：核心高安全检测逻辑在 Native C 层实现，防 Java 层 Hook；
 * 2. 不上本地强拦截：客户端只采集全量风险特征并上报服务端，由服务端权重打分判定；
 * 3. 多维交叉校验：进程、文件、端口、内存 maps、ptrace 状态、挂载点、应用包名多维度组合。
 *
 * 调用方式（App 启动、关键业务接口前置）：
 * ```
 * val report = NativeSecurityDetector.collectAllRiskData()
 * // 直接将 report.toJson() 完整 JSON 上传服务端
 * ```
 *
 * 注意：采集包含端口探测与 /proc 遍历等耗时 IO，请在后台线程调用。
 */
object NativeSecurityDetector {

    private const val TAG = "NativeSecurityDetector"

    /** Frida 默认监听端口 */
    private const val FRIDA_DEFAULT_PORT = 27042

    /** 端口连通性探测超时（毫秒） */
    private const val PORT_CONNECT_TIMEOUT_MS = 200

    /** Root 管理工具特征包名（Magisk / SuperSU 等，方案 5.3 节强制清单） */
    private val ROOT_APP_PACKAGES = listOf(
        "com.topjohnwu.magisk",       // Magisk
        "eu.chainfire.supersu",       // SuperSU
        "com.noshufou.android.su",    // Superuser
        "com.koushikdutta.superuser", // Koushik Dutta Superuser
        "com.thirdparty.superuser",   // 第三方 Superuser
        "com.kingroot.kingroot",      // KingRoot
        "com.kingo.root",             // KingoRoot
    )

    /** Kotlin 层二次校验的系统 SU 路径（与 Native 层 SU_PATHS 清单保持一致） */
    private val SU_FILE_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/system/usr/we-need-root/su",
        "/system/bin/.ext/.su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
    )

    init {
        // 加载 Native 核心安全层（libsecuritydetect.so）
        System.loadLibrary("securitydetect")
    }

    /** Native 层统一风险采集入口，返回结构化 JSON（fridaRisk / xposedRisk / rootNativeRisk） */
    private external fun nativeCollectRiskJson(): String

    /**
     * 采集全量风控特征（Native 核心检测 + Kotlin 辅助特征），生成上报实体。
     * 禁止在主线程调用。
     */
    fun collectAllRiskData(): SecurityFullReport {
        // 1. Native C 层核心检测：TracerPid / 内存恶意 SO / SU 路径（最终风险判定核心逻辑）
        val nativeRisk = runCatching { parseNativeRisk(nativeCollectRiskJson()) }
            .onFailure { Log.w(TAG, "Native 核心检测异常", it) }
            .getOrElse {
                NativeRisk(RiskInfo(false), RiskInfo(false), RiskInfo(false))
            }

        // 2. Kotlin 辅助层：只补充弱特征，丰富服务端风控维度，不做主判定
        return SecurityFullReport(
            nativeRisk = nativeRisk,
            javaSuFileExist = checkSuFilesExist(),
            fridaPortOpen = isFridaPortOpen(),
            fridaProcessFound = findFridaProcess(),
            rootAppPackageList = findInstalledRootApps(),
            collectTimestampMs = System.currentTimeMillis(),
        )
    }

    // ============================ Native 结果解析 ============================

    private fun parseNativeRisk(json: String): NativeRisk {
        val root = JSONObject(json)
        return NativeRisk(
            fridaRisk = root.optJSONObject("fridaRisk").toRiskInfo(),
            xposedRisk = root.optJSONObject("xposedRisk").toRiskInfo(),
            rootNativeRisk = root.optJSONObject("rootNativeRisk").toRiskInfo(),
        )
    }

    private fun JSONObject?.toRiskInfo(): RiskInfo {
        if (this == null) return RiskInfo(detected = false)
        val details = optJSONArray("hitDetails")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    add(array.optString(i))
                }
            }
        }.orEmpty()
        return RiskInfo(
            detected = optBoolean("detected", false),
            hitDetails = details,
        )
    }

    // ============================ Kotlin 辅助特征（方案 5.3 节强制清单） ============================

    /** 辅助特征 1：Frida 默认 27042 端口连通性检测 */
    private fun isFridaPortOpen(): Boolean = try {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress("127.0.0.1", FRIDA_DEFAULT_PORT),
                PORT_CONNECT_TIMEOUT_MS,
            )
            true
        }
    } catch (e: Exception) {
        false
    }

    /** 辅助特征 2：遍历 /proc 目录，按进程 cmdline 匹配 frida-server 进程 */
    private fun findFridaProcess(): Boolean {
        val pidDirs = runCatching { File("/proc").listFiles() }.getOrNull() ?: return false
        for (dir in pidDirs) {
            // 仅处理数字命名的 PID 目录
            if (dir.name.isEmpty() || !dir.name.all { it.isDigit() }) continue
            val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: continue
            if (cmdline.contains("frida", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /** 辅助特征 3：检测设备已安装的 Root 管理工具（Magisk / SuperSU 等）包名 */
    private fun findInstalledRootApps(): List<String> {
        val context = currentApplicationContext() ?: return emptyList()
        val pm = context.packageManager
        return ROOT_APP_PACKAGES.filter { packageName ->
            try {
                pm.getPackageInfo(packageName, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** 辅助特征 4：系统 SU 文件二次校验（与 Native 层路径清单交叉印证） */
    private fun checkSuFilesExist(): Boolean =
        SU_FILE_PATHS.any { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }

    // ============================ 工具方法 ============================

    /** 反射获取当前 Application，保持 collectAllRiskData() 无参调用约定 */
    private fun currentApplicationContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (e: Throwable) {
        null
    }
}
