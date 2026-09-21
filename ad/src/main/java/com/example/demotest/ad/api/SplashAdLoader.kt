package com.example.demotest.ad.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup

/**
 * 开屏广告加载器（编排层，平台无关）
 *
 * 开屏广告以 View 形式挂载到业务方提供的容器中展示，挂载成功后容器会被置为可见；
 * 展示完毕（onAdClosed）后业务方自行隐藏容器并进入主界面。
 *
 * 一次 [load] 请求内由双管道赛跑完成（见 [AdRaceRunner]）：
 * 竞价臂并行向全部平台取价、瀑布流臂按平台优先级顺序回退，两臂同时执行；
 * 结算后统一比价，ecpm 最高者竞得——写入缓存待展示，落败候选立即释放。
 *
 * 其余职责：本地前置校验、失败自动重试（重试即重跑整个双管道赛跑）、缓存写入与策略取用、
 * "命中即展示、未命中自动加载后展示"（[autoShow]）；
 * 具体 SDK 调用全部委托给 [platforms] 适配器，加载成功的 SDK 广告对象被包装为 [ISplashAd] 协议对象。
 *
 * 示例请通过 [AdsManager.createSplashLoader] 创建，以获得门面统一注入的平台与配置。
 */
class SplashAdLoader internal constructor(
    private val platforms: List<AdPlatform>,
    private val request: SplashAdRequest,
) {

    /** 请求失败后的自动重试编排（重试即重跑整个双管道赛跑），成功或新一次 load 时复位 */
    private val retryHelper = AdRetryHelper()

    /** autoShow 编排中的主线程调度：加载回调不保证在主线程，展示必须切主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 加载开屏广告（竞价 × 瀑布流同时执行，比价取最高者入缓存，不绑定展示监听）
     *
     * @param listener 加载结果回调，仅通知成功/失败
     */
    fun load(context: Context, listener: AdLoadListener) {
        val targets = resolveRacePlatforms(listener) ?: return
        // 新一次加载取代尚未执行的自动重试，避免两条请求串扰
        retryHelper.reset()
        doLoad(context.applicationContext, targets, listener)
    }

    private fun doLoad(context: Context, targets: List<AdPlatform>, listener: AdLoadListener) {
        AdLogger.i(TAG, "开始加载开屏: 参与平台=${targets.joinToString { it.name }}（竞价与瀑布流同时执行）")
        AdRaceRunner<ISplashAd>(targets) { platform, onLoaded, onError ->
            platform.loadSplash(context, request, onLoaded, onError)
        }.run(
            onSettled = { winner, outcome ->
                AdLogger.i(
                    TAG,
                    "开屏竞得: platform=${winner.platform.name}, 渠道=${winner.channel.label}, " +
                        "ecpm=${winner.ad.ecpm ?: "无"}, 耗时=${winner.costMs}ms",
                )
                outcome.releaseLosers()
                retryHelper.reset()
                AdCacheManager.put(AdType.SPLASH, winner.ad)
                listener.onAdLoaded()
            },
            onError = { error ->
                AdLogger.w(TAG, "开屏加载失败: $error")
                // 真实请求失败自动重试（重跑双管道赛跑），重试耗尽后才回调业务失败，中间过程对业务方透明
                if (!retryHelper.retryAfterDelay { doLoad(context, targets, listener) }) {
                    listener.onAdError(error)
                }
            },
        )
    }

    /**
     * 解析本次参与赛跑的平台：需同时满足"已配置广告位 ID"且"SDK 已就绪"
     *
     * @return null 表示无可用平台（已通过 [listener] 回调前置校验错误）
     */
    private fun resolveRacePlatforms(listener: AdLoadListener): List<AdPlatform>? {
        val configured = platforms.filter { !request.adUnitIds[it].isNullOrBlank() }
        if (configured.isEmpty()) {
            listener.onAdError(AdError(AdError.CODE_EMPTY_AD_UNIT, "开屏未配置任何平台的广告位 ID（adUnitIds）"))
            return null
        }
        val (ready, notReady) = configured.partition { it.isReady() }
        notReady.forEach { AdLogger.w(TAG, "平台 ${it.name} 尚未初始化成功，本次不参与竞价与瀑布流") }
        if (ready.isEmpty()) {
            listener.onAdError(AdError(AdError.CODE_SDK_NOT_READY, "已配置的平台均未初始化成功，禁止发起广告请求"))
            return null
        }
        return ready
    }

    /**
     * 将缓存的开屏广告挂载到容器展示（命中即从 [AdCacheManager] 消耗一条）
     *
     * 事件监听在展示时绑定到该条广告上，事件回调本次展示传入的 listener；
     * 挂载成功后容器会被置为可见
     *
     * @param listener 本次展示的事件回调：曝光/点击/关闭
     * @param strategy 本次取用缓存的覆盖策略，null 表示使用 AdConfig 配置的默认策略
     * @return false 表示缓存未命中（尚未 load、已被展示消耗或缓存过期）
     */
    fun show(
        container: ViewGroup,
        listener: SplashAdEventListener,
        strategy: AdSelectStrategy? = null,
    ): Boolean {
        val ad = AdCacheManager.take<ISplashAd>(AdType.SPLASH, strategy)
        if (ad == null) {
            AdLogger.w(TAG, "展示失败：开屏缓存未命中")
            return false
        }
        AdLogger.i(TAG, "展示开屏: ecpm=${ad.ecpm ?: "无"}")
        ad.setEventListener(listener)
        ad.show(container)
        container.visibility = View.VISIBLE
        return true
    }

    /**
     * 缓存优先自动展示：命中缓存则立即挂载，未命中则自动发起现场加载、加载成功后自动挂载
     *
     * 把"先查缓存、再回退现场加载"的编排沉淀在 Loader 内部，业务方一次调用即可完成展示链路
     *
     * @param container 全屏展示容器（同时作为现场加载的 Context 来源）
     * @param listener 本次展示的事件回调，展示时绑定
     * @param strategy 本次取用缓存的覆盖策略，null 表示使用 AdConfig 配置的默认策略
     * @param loadListener 未命中时现场加载的结果回调，默认 null 仅内部日志
     * @return true 表示缓存命中已直接展示；false 表示未命中、已启动现场加载，展示结果异步回调
     */
    fun autoShow(
        container: ViewGroup,
        listener: SplashAdEventListener,
        strategy: AdSelectStrategy? = null,
        loadListener: AdLoadListener? = null,
    ): Boolean {
        if (show(container, listener, strategy)) {
            return true
        }
        AdLogger.i(TAG, "缓存未命中，现场加载成功后自动展示")
        load(container.context, object : AdLoadListener {
            override fun onAdError(error: AdError) {
                AdLogger.w(TAG, "现场加载失败，无法自动展示: $error")
                loadListener?.onAdError(error)
            }

            override fun onAdLoaded() {
                loadListener?.onAdLoaded()
                mainHandler.post { show(container, listener, strategy) }
            }
        })
        return false
    }

    /** 释放缓存中的开屏广告引用，并取消尚未执行的自动重试 */
    fun destroy() {
        retryHelper.reset()
        AdCacheManager.remove<ISplashAd>(AdType.SPLASH)
    }

    private companion object {
        private const val TAG = "SplashAdLoader"
    }
}
