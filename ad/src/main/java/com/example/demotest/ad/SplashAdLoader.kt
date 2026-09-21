package com.example.demotest.ad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.CSJAdError
import com.bytedance.sdk.openadsdk.CSJSplashAd
import com.bytedance.sdk.openadsdk.TTAdNative

/**
 * 开屏广告加载器
 *
 * 开屏广告返回 View，挂载到业务方提供的容器中展示，挂载成功后容器会被置为可见；
 * 展示完毕（onAdClosed）后业务方自行隐藏容器并进入主界面。
 *
 * 缓存与重试：加载成功的广告对象统一存入 [AdCacheManager]，
 * [show] 从缓存按 [AdSelectStrategy] 取一条（默认先进先出，可配置或按次覆盖）；展示监听在 [show] 时随展示绑定，
 * 不随广告对象进入缓存；SDK 真实请求失败后会自动重试，以提升加载成功率；
 * [autoShow] 进一步封装"命中即展示、未命中自动加载后展示"的一站式编排。
 *
 * @param adUnitId GroMore 开屏广告位 ID（1 开头）
 * @param timeoutMs 加载超时时间（毫秒），超时后走 onSplashLoadFail，业务方应直接进入主界面
 */
class SplashAdLoader(
    private val adUnitId: String,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

    /** SDK 请求失败后的自动重试编排，成功或新一次 load 时复位 */
    private val retryHelper = AdRetryHelper()

    /** autoShow 编排中的主线程调度：加载回调不保证在主线程，展示必须切主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 加载开屏广告（仅入缓存，不绑定展示监听） */
    fun load(context: Context, listener: AdLoadListener) {
        if (!GroMoreAdManager.isReady()) {
            listener.onAdError(AdError(AdError.CODE_SDK_NOT_READY, "GroMore SDK 尚未初始化成功，禁止发起广告请求"))
            return
        }
        if (adUnitId.isBlank()) {
            listener.onAdError(AdError(AdError.CODE_EMPTY_AD_UNIT, "开屏广告位 ID 为空，请在 GroMore 后台申请"))
            return
        }
        // 新一次加载取代尚未执行的自动重试，避免两条请求串扰
        retryHelper.reset()
        requestAd(context.applicationContext, buildAdSlot(), listener)
    }

    private fun buildAdSlot(): AdSlot = AdSlot.Builder()
        .setCodeId(adUnitId)
        .build()

    private fun requestAd(context: Context, slot: AdSlot, listener: AdLoadListener) {
        GroMoreAdManager.createAdNative(context).loadSplashAd(
            slot,
            object : TTAdNative.CSJSplashAdListener {
                override fun onSplashLoadSuccess(ad: CSJSplashAd?) {
                    Log.i(TAG, "开屏加载成功")
                    retryHelper.reset()
                    if (ad != null) {
                        AdCacheManager.put(AdType.SPLASH, ad)
                    }
                    listener.onAdLoaded()
                }

                override fun onSplashLoadFail(error: CSJAdError?) {
                    Log.w(TAG, "开屏加载失败: code=${error?.code}, msg=${error?.msg}")
                    // 真实请求失败自动重试，重试耗尽后才回调业务失败，中间过程对业务方透明
                    if (retryHelper.retryAfterDelay { requestAd(context, slot, listener) }) {
                        return
                    }
                    listener.onAdError(AdError(error?.code ?: -1, error?.msg.orEmpty()))
                }

                override fun onSplashRenderSuccess(ad: CSJSplashAd?) {
                    Log.i(TAG, "开屏渲染成功")
                }

                override fun onSplashRenderFail(ad: CSJSplashAd?, error: CSJAdError?) {
                    // 渲染失败发生在加载成功之后，重试无意义，直接回调业务方
                    Log.w(TAG, "开屏渲染失败: code=${error?.code}, msg=${error?.msg}")
                    listener.onAdError(AdError(error?.code ?: -1, error?.msg.orEmpty()))
                }
            },
            timeoutMs,
        )
    }

    /**
     * 构建 SDK 交互监听：在 [show] 时绑定到取出的广告对象上，
     * 事件直接转发给本次展示传入的 listener，不随广告对象进入缓存
     */
    private fun buildSplashListener(listener: SplashAdEventListener): CSJSplashAd.SplashAdListener =
        object : CSJSplashAd.SplashAdListener {
            override fun onSplashAdShow(ad: CSJSplashAd?) = listener.onAdShown()

            override fun onSplashAdClick(ad: CSJSplashAd?) = listener.onAdClicked()

            override fun onSplashAdClose(ad: CSJSplashAd?, closeType: Int) = listener.onAdClosed()
        }

    /**
     * 将缓存的开屏广告挂载到容器展示（命中即从 [AdCacheManager] 消耗一条）
     *
     * 交互监听在展示时绑定到该条广告上，事件回调本次展示传入的 listener；
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
        val ad = AdCacheManager.take<CSJSplashAd>(AdType.SPLASH, strategy)
        if (ad == null) {
            Log.w(TAG, "展示失败：开屏缓存未命中")
            return false
        }
        ad.setSplashAdListener(buildSplashListener(listener))
        ad.showSplashView(container)
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
        Log.i(TAG, "缓存未命中，现场加载成功后自动展示")
        load(container.context, object : AdLoadListener {
            override fun onAdError(error: AdError) {
                Log.w(TAG, "现场加载失败，无法自动展示: $error")
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
        AdCacheManager.remove<CSJSplashAd>(AdType.SPLASH)
    }

    private companion object {
        private const val TAG = "SplashAdLoader"

        /** 开屏等待超时默认值：避免长时间阻塞启动流程 */
        private const val DEFAULT_TIMEOUT_MS = 5000
    }
}
