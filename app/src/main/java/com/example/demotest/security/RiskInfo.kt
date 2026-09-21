package com.example.demotest.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native 层返回的单项风险实体。
 *
 * @param detected   该风险维度是否命中特征（Native 层命中 = 高风险，服务端权重极高）
 * @param hitDetails 命中明细（如具体恶意 SO 名、SU 路径、TracerPid 值），供服务端细粒度打分
 */
data class RiskInfo(
    val detected: Boolean,
    val hitDetails: List<String> = emptyList(),
)

/** 序列化为上报 JSON 对象 */
fun RiskInfo.toJson(): JSONObject = JSONObject().apply {
    put("detected", detected)
    put("hitDetails", JSONArray(hitDetails))
}
