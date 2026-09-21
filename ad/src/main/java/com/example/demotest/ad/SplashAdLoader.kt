package com.example.demotest.ad

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.CSJAdError
import com.bytedance.sdk.openadsdk.CSJSplashAd
import com.bytedance.sdk.openadsdk.TTAdNative

/**
 * 开屏广告加载器
 *
 * 开屏广告返回 View，由业务方挂载到启动页容器中展示；
 * 展示完毕（onAdClosed）后业务方自行移除容器并进入主界面。
 *
 * 缓存与重试：加载成功的广告对象统一存入 [AdCacheManager]，
 * [show] 从缓存按 FIFO 取一条（先缓存先消耗）；展示监听在 [show] 时随展示绑定，
 * 不随广告对象进入缓存；SDK 真实请求失败后会自动重试，以提升加载成功率。
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
     * 交互监听在展示时绑定到该条广告上，事件回调本次展示传入的 listener
     *
     * @param listener 本次展示的事件回调：曝光/点击/关闭
     * @return false 表示缓存未命中（尚未 load、已被展示消耗或缓存过期）
     */
    fun show(container: ViewGroup, listener: SplashAdEventListener): Boolean {
        val ad = AdCacheManager.take<CSJSplashAd>(AdType.SPLASH)
        if (ad == null) {
            Log.w(TAG, "展示失败：开屏缓存未命中")
            return false
        }
        ad.setSplashAdListener(buildSplashListener(listener))
        ad.showSplashView(container)
        return true
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
