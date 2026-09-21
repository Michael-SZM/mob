package com.example.demotest.ad.api

import android.content.Context

/**
 * 广告平台适配器协议（适配器模式）
 *
 * 每个接入的广告平台（如 GroMore）实现本接口，职责边界：
 * 1. SDK 初始化与就绪判断（[init]/[isReady]）；
 * 2. 将各类型广告的"加载"请求翻译为平台 SDK 调用（loadXxx），
 *    广告位 ID 从请求对象的 adUnitIds 映射中按 `adUnitIds[this]` 取本平台的绑定；
 * 3. 把 SDK 广告对象包装成 [IAd] 协议对象回传，并把 SDK 错误码统一为 [AdError]。
 *
 * 通用能力（本地前置校验、失败重试、缓存、策略取用、竞价×瀑布流双管道赛跑、autoShow 编排、关闭补位）
 * 由编排层 Loader 统一实现，适配器不做缓存与重试、不感知展示编排，
 * 因此新增平台只需实现本接口（协议翻译），即可获得全部通用能力。
 *
 * 多平台同时接入：编排层把参与本次请求的全部 [AdPlatform] 同时投入竞价臂与瀑布流臂，
 * 适配器实现无需感知其他平台的存在。
 */
interface AdPlatform {

    /** 平台名称，用于日志与问题定位（如 "GroMore"） */
    val name: String

    /**
     * 初始化平台 SDK
     *
     * @param onResult 初始化结果；成功后 [isReady] 才返回 true，方可发起广告请求。
     *                 回调线程由平台 SDK 决定，如需更新 UI 请自行切换到主线程
     */
    fun init(context: Context, onResult: (Result<Unit>) -> Unit)

    /** 平台 SDK 是否已初始化完成并可用 */
    fun isReady(): Boolean

    /**
     * 加载激励视频（广告位 ID 从 `request.adUnitIds[this]` 读取；未绑定的平台不会被编排层调用）
     *
     * @param onLoaded 加载成功，回传包装后的协议广告对象
     * @param onError 加载失败（平台原始错误；失败自动重试由编排层负责，适配器不重试）
     */
    fun loadRewarded(
        context: Context,
        request: RewardedAdRequest,
        onLoaded: (IRewardedAd) -> Unit,
        onError: (AdError) -> Unit,
    )

    /** 加载插屏（全屏视频）广告（广告位 ID 从 `request.adUnitIds[this]` 读取） */
    fun loadInterstitial(
        context: Context,
        request: InterstitialAdRequest,
        onLoaded: (IInterstitialAd) -> Unit,
        onError: (AdError) -> Unit,
    )

    /** 加载开屏广告（广告位 ID 从 `request.adUnitIds[this]` 读取） */
    fun loadSplash(
        context: Context,
        request: SplashAdRequest,
        onLoaded: (ISplashAd) -> Unit,
        onError: (AdError) -> Unit,
    )

    /** 加载模板 Banner 广告（广告位 ID 从 `request.adUnitIds[this]` 读取；以模板渲染成功、[IBannerAd.adView] 可用为加载成功） */
    fun loadBanner(
        context: Context,
        request: BannerAdRequest,
        onLoaded: (IBannerAd) -> Unit,
        onError: (AdError) -> Unit,
    )
}
