package com.example.demotest.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native C 层三大核心风险聚合实体（fridaRisk / xposedRisk / rootNativeRisk）。
 * 由 Native 层输出的结构化 JSON 解析而来，核心逻辑不可被 Java 层 Hook 篡改。
 */
data class NativeRisk(
    val fridaRisk: RiskInfo,
    val xposedRisk: RiskInfo,
    val rootNativeRisk: RiskInfo,
)

/**
 * 客户端本地风险评估等级（仅用于展示/调试，正式判定以服务端权重打分为准）。
 */
enum class RiskLevel(val label: String) {
    /** 未命中任何风险特征 */
    NONE("无风险"),

    /** 仅命中单个辅助特征，中低风险 */
    LOW("低风险"),

    /** 多个辅助特征命中，中风险 */
    MEDIUM("中风险"),

    /** Native 层核心检测命中，高风险（权重极高） */
    HIGH("高风险"),
}

/**
 * 本地检测结果汇总。
 *
 * @param riskLevel         综合风险等级
 * @param nativeHitCount    Native C 层核心检测命中项数
 * @param auxiliaryHitCount Kotlin 辅助特征命中项数
 * @param hitSummary        命中特征明细（中文描述）
 */
data class DetectionResult(
    val riskLevel: RiskLevel,
    val nativeHitCount: Int,
    val auxiliaryHitCount: Int,
    val hitSummary: List<String>,
) {
    /** 是否命中任意风险特征 */
    val isRisky: Boolean get() = riskLevel != RiskLevel.NONE
}

/**
 * 客户端全量风控上报实体（方案第 6 节固定上报字段）。
 *
 * 安全原则：客户端不做任何拦截、弹窗、封禁逻辑，仅完整采集全部特征上报，
 * 由服务端配置权重打分判定风险等级（Native 命中 = 高风险；辅助特征 = 中低风险加权累计）。
 *
 * @param nativeRisk         Native C 层三大核心风险
 * @param javaSuFileExist    Kotlin 层二次校验：系统 SU 文件是否存在
 * @param fridaPortOpen      Kotlin 层辅助特征：Frida 默认 27042 端口是否开放
 * @param fridaProcessFound  Kotlin 层辅助特征：/proc 中是否发现 frida-server 进程
 * @param rootAppPackageList Kotlin 层辅助特征：设备已安装的 Root 管理工具包名
 * @param collectTimestampMs 特征采集时间戳（服务端用于时效校验）
 */
data class SecurityFullReport(
    val nativeRisk: NativeRisk,
    val javaSuFileExist: Boolean,
    val fridaPortOpen: Boolean,
    val fridaProcessFound: Boolean,
    val rootAppPackageList: List<String>,
    val collectTimestampMs: Long,
) {

    /**
     * 基于已采集特征计算本地检测结果。
     *
     * 权重规则（对应方案 3.3 节服务端打分逻辑的客户端本地映射）：
     * - Native 层任一命中 = 高风险（权重极高）；
     * - 辅助特征命中 ≥ 2 项 = 中风险（多特征交叉命中，疑似作弊/改机设备）；
     * - 辅助特征命中 1 项 = 低风险（加权累计）；
     * - 全部未命中 = 无风险。
     *
     * 注意：结果仅用于本地展示与调试，客户端不做任何拦截，最终决策以服务端打分为准。
     */
    fun getDetectionResult(): DetectionResult {
        val summary = mutableListOf<String>()

        // 1. Native C 层核心检测（高风险维度）
        var nativeHitCount = 0
        if (nativeRisk.fridaRisk.detected) {
            nativeHitCount++
            summary.add("[Native] Frida 注入风险：${nativeRisk.fridaRisk.hitDetails.joinToString("、")}")
        }
        if (nativeRisk.xposedRisk.detected) {
            nativeHitCount++
            summary.add("[Native] Xposed/LSPosed 注入风险：${nativeRisk.xposedRisk.hitDetails.joinToString("、")}")
        }
        if (nativeRisk.rootNativeRisk.detected) {
            nativeHitCount++
            summary.add("[Native] Root 环境风险：${nativeRisk.rootNativeRisk.hitDetails.joinToString("、")}")
        }

        // 2. Kotlin 辅助特征（中低风险维度）
        var auxiliaryHitCount = 0
        if (javaSuFileExist) {
            auxiliaryHitCount++
            summary.add("[辅助] 系统存在 SU 文件")
        }
        if (fridaPortOpen) {
            auxiliaryHitCount++
            summary.add("[辅助] Frida 默认端口 27042 开放")
        }
        if (fridaProcessFound) {
            auxiliaryHitCount++
            summary.add("[辅助] 发现 frida-server 进程")
        }
        if (rootAppPackageList.isNotEmpty()) {
            auxiliaryHitCount++
            summary.add("[辅助] 已安装 Root 管理工具：${rootAppPackageList.joinToString("、")}")
        }

        val riskLevel = when {
            nativeHitCount > 0 -> RiskLevel.HIGH
            auxiliaryHitCount >= 2 -> RiskLevel.MEDIUM
            auxiliaryHitCount == 1 -> RiskLevel.LOW
            else -> RiskLevel.NONE
        }

        return DetectionResult(
            riskLevel = riskLevel,
            nativeHitCount = nativeHitCount,
            auxiliaryHitCount = auxiliaryHitCount,
            hitSummary = summary,
        )
    }

    /** 序列化为上报服务端的完整 JSON（直接作为上报报文体） */
    fun toJson(): String = JSONObject().apply {
        put("nativeRisk", JSONObject().apply {
            put("fridaRisk", nativeRisk.fridaRisk.toJson())
            put("xposedRisk", nativeRisk.xposedRisk.toJson())
            put("rootNativeRisk", nativeRisk.rootNativeRisk.toJson())
        })
        put("javaSuFileExist", javaSuFileExist)
        put("fridaPortOpen", fridaPortOpen)
        put("fridaProcessFound", fridaProcessFound)
        put("rootAppPackageList", JSONArray(rootAppPackageList))
        put("collectTimestampMs", collectTimestampMs)
    }.toString()
}
