package com.example.demotest.ad.gromore

/**
 * 解析 GroMore 出价字符串为数值（单位：分）
 *
 * GroMore 聚合管理器 MediationAdEcpmInfo.getEcpm() 返回 String 而非数值，
 * 此处统一转 Double 供策略比较；非数字、空串返回 null（视为无出价信息）
 */
internal fun parseEcpm(raw: String?): Double? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
