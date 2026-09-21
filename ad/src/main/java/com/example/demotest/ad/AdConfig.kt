package com.example.demotest.ad

/**
 * GroMore 广告配置
 *
 * @param appId 穿山甲 GroMore 后台申请的应用 ID（5 开头的 7 位数字），必填
 * @param appName 应用名称，用于 SDK 统计维度，非必填
 * @param debug 是否开启 SDK 调试日志，接入联调阶段建议开启，上线前关闭
 * @param splashAdUnitId 开屏广告位 ID（GroMore 广告位维度，1 开头）
 * @param rewardedAdUnitId 激励视频广告位 ID（GroMore 广告位维度，1 开头）
 * @param interstitialAdUnitId 插屏/全屏视频广告位 ID（GroMore 广告位维度，1 开头）
 * @param bannerAdUnitId 模板 Banner 广告位 ID（GroMore 广告位维度，1 开头）
 *
 * 注意：聚合场景下请求传的是"广告位 ID"而非各 ADN 的"代码位 ID"，
 * SDK 会按 GroMore 后台瀑布流配置自行请求对应代码位
 */
data class AdConfig(
    val appId: String,
    val appName: String,
    val debug: Boolean = false,
    val splashAdUnitId: String = "",
    val rewardedAdUnitId: String = "",
    val interstitialAdUnitId: String = "",
    val bannerAdUnitId: String = "",
)
