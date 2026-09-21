package com.example.demotest.ad.api

import android.content.Context
import android.os.SystemClock
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 广告 SDK 门面（Facade）
 *
 * 业务方唯一入口：注册并初始化平台（支持同时接入多个平台）、创建各类型广告加载器。
 * 对外只暴露本模块协议类型（[AdConfig]/[AdPlatform]/[IAd]/监听器/策略等），
 * 不暴露任何具体广告 SDK 的类型：更换或新增广告平台时业务代码零改动，
 * 只需在 [AdConfig.platforms] 中传入对应平台的 [AdPlatform] 实现。
 *
 * 每次加载请求内部由双管道赛跑完成：竞价臂并行向全部平台取价、瀑布流臂按注册顺序回退，
 * 两臂同时执行、结算后比价取 ecpm 最高者（见 [AdRaceRunner]），业务方无需感知；
 * 完整链路日志由 [AdLogger] 统一输出，方便调试。
 *
 * 典型用法：
 * ```
 * // 1. 初始化：注册平台（可多个，顺序即瀑布流优先级）
 * AdsManager.init(context, AdConfig(platforms = listOf(GroMorePlatform(appId, appName)))) { ... }
 *
 * // 2. 创建加载器（需在 init 之后；广告位 ID 按平台配置）
 * val loader = AdsManager.createRewardedLoader(mapOf(groMorePlatform to "激励视频广告位 ID"))
 *
 * // 3. 预加载 / 缓存优先自动展示
 * loader.load(context, loadListener)
 * loader.autoShow(activity, eventListener)
 * ```
 */
object AdsManager {

    /** 当前注册的平台适配器，[init] 时注入；创建 Loader 时使用 */
    @Volatile
    private var platforms: List<AdPlatform> = emptyList()

    /**
     * 初始化广告 SDK 门面
     *
     * 并行初始化全部注册平台：只要有一个平台初始化成功即回调成功（失败的平台不参与请求，
     * 后续可通过 [isReady] 与日志观察）；全部失败回调失败
     *
     * @param context 任意 Context，内部取 applicationContext 交给平台
     * @param config 通用配置：平台列表 + 缓存取用策略
     * @param onResult 初始化结果回调，回调线程由平台 SDK 决定
     */
    fun init(context: Context, config: AdConfig, onResult: ((Result<Unit>) -> Unit)? = null) {
        AdCacheManager.setDefaultStrategy(config.selectStrategy)
        val registered = config.platforms.distinct()
        platforms = registered
        if (registered.isEmpty()) {
            AdLogger.e(TAG, "初始化失败：platforms 为空")
            onResult?.invoke(
                Result.failure(IllegalArgumentException("AdConfig.platforms 不能为空，至少注册一个广告平台")),
            )
            return
        }
        AdLogger.i(TAG, "开始初始化 ${registered.size} 个广告平台: ${registered.joinToString { it.name }}")
        val remaining = AtomicInteger(registered.size)
        val results = Collections.synchronizedList(mutableListOf<Pair<AdPlatform, Result<Unit>>>())
        registered.forEach { platform ->
            val startAt = SystemClock.elapsedRealtime()
            platform.init(context.applicationContext) { result ->
                val costMs = SystemClock.elapsedRealtime() - startAt
                results.add(platform to result)
                result
                    .onSuccess { AdLogger.i(TAG, "平台 ${platform.name} 初始化成功, 耗时=${costMs}ms") }
                    .onFailure { AdLogger.w(TAG, "平台 ${platform.name} 初始化失败: ${it.message}, 耗时=${costMs}ms") }
                if (remaining.decrementAndGet() == 0) {
                    settleInit(registered.size, results.toList(), onResult)
                }
            }
        }
    }

    /** 全部平台初始化结算：部分成功视为整体成功，全部失败才回调失败 */
    private fun settleInit(
        total: Int,
        results: List<Pair<AdPlatform, Result<Unit>>>,
        onResult: ((Result<Unit>) -> Unit)?,
    ) {
        val succeeded = results.count { it.second.isSuccess }
        if (succeeded == 0) {
            val detail = results.joinToString("; ") { "${it.first.name}=${it.second.exceptionOrNull()?.message}" }
            AdLogger.e(TAG, "全部平台初始化失败: $detail")
            onResult?.invoke(Result.failure(IllegalStateException("全部广告平台初始化失败: $detail")))
            return
        }
        if (succeeded < total) {
            AdLogger.w(TAG, "部分平台初始化失败（$succeeded/$total 成功），将以成功平台参与请求")
        }
        AdLogger.i(TAG, "广告平台初始化完成（$succeeded/$total 成功）")
        onResult?.invoke(Result.success(Unit))
    }

    /** 是否存在已初始化成功、可参与请求的平台 */
    fun isReady(): Boolean = platforms.any { runCatching { it.isReady() }.getOrDefault(false) }

    /**
     * 创建激励视频加载器（一次请求 = 竞价 × 瀑布流双管道赛跑、比价取最高）
     *
     * @param adUnitIds 各平台的广告位 ID（键为平台实例）；未配置或为空串的平台不参与本次请求
     */
    fun createRewardedLoader(
        adUnitIds: Map<AdPlatform, String>,
        userId: String? = null,
        rewardName: String? = null,
        rewardAmount: Int = 0,
    ): RewardedAdLoader = RewardedAdLoader(
        platforms,
        RewardedAdRequest(adUnitIds, userId, rewardName, rewardAmount),
    )

    /**
     * 创建插屏（全屏视频）加载器（一次请求 = 竞价 × 瀑布流双管道赛跑、比价取最高）
     *
     * @param adUnitIds 各平台的广告位 ID（键为平台实例）；未配置或为空串的平台不参与本次请求
     */
    fun createInterstitialLoader(
        adUnitIds: Map<AdPlatform, String>,
    ): InterstitialAdLoader = InterstitialAdLoader(
        platforms,
        InterstitialAdRequest(adUnitIds),
    )

    /**
     * 创建开屏加载器（一次请求 = 竞价 × 瀑布流双管道赛跑、比价取最高）
     *
     * @param adUnitIds 各平台的广告位 ID（键为平台实例）；未配置或为空串的平台不参与本次请求
     * @param timeoutMs 加载超时时间（毫秒），透传给各平台
     */
    fun createSplashLoader(
        adUnitIds: Map<AdPlatform, String>,
        timeoutMs: Int = SplashAdRequest.DEFAULT_TIMEOUT_MS,
    ): SplashAdLoader = SplashAdLoader(
        platforms,
        SplashAdRequest(adUnitIds, timeoutMs),
    )

    /**
     * 创建模板 Banner 加载器（一次请求 = 竞价 × 瀑布流双管道赛跑、比价取最高）
     *
     * @param adUnitIds 各平台的广告位 ID（键为平台实例）；未配置或为空串的平台不参与本次请求
     */
    fun createBannerLoader(
        adUnitIds: Map<AdPlatform, String>,
        widthDp: Float = BannerAdRequest.DEFAULT_WIDTH_DP,
        heightDp: Float = BannerAdRequest.DEFAULT_HEIGHT_DP,
    ): BannerAdLoader = BannerAdLoader(
        platforms,
        BannerAdRequest(adUnitIds, widthDp, heightDp),
    )

    private const val TAG = "AdsManager"
}
