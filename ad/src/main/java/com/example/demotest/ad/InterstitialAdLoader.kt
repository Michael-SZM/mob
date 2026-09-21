package com.example.demotest.ad

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.TTAdConstant
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTFullScreenVideoAd

/**
 * 插屏（全屏视频）广告加载器
 *
 * 新模板插屏统一走全屏视频接口（loadFullScreenVideoAd），
 * 与激励视频相同：一次加载对应一次展示，关闭后需重新 [load]。
 *
 * 缓存与重试：加载成功的广告对象统一存入 [AdCacheManager]（可预加载多条），
 * [show] 从缓存按 [AdSelectStrategy] 取一条（默认先进先出，可配置或按次覆盖）；展示监听在 [show] 时随展示绑定，
 * 不随广告对象进入缓存；SDK 真实请求失败后会自动重试，以提升加载成功率；
 * [autoShow] 进一步封装"命中即展示、未命中自动加载后展示"的一站式编排；
 * show/autoShow 的 autoReloadOnClose 控制广告关闭（已消耗）后是否自动补位加载下一条。
 *
 * @param adUnitId GroMore 插屏/全屏视频广告位 ID（1 开头）
 */
class InterstitialAdLoader(private val adUnitId: String) {

    /** SDK 请求失败后的自动重试编排，成功或新一次 load 时复位 */
    private val retryHelper = AdRetryHelper()

    /** autoShow 编排中的主线程调度：加载回调不保证在主线程，展示必须切主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 加载插屏广告（仅入缓存，不绑定展示监听） */
    fun load(context: Context, listener: AdLoadListener) {
        if (!GroMoreAdManager.isReady()) {
            listener.onAdError(AdError(AdError.CODE_SDK_NOT_READY, "GroMore SDK 尚未初始化成功，禁止发起广告请求"))
            return
        }
        if (adUnitId.isBlank()) {
            listener.onAdError(AdError(AdError.CODE_EMPTY_AD_UNIT, "插屏广告位 ID 为空，请在 GroMore 后台申请"))
            return
        }
        // 新一次加载取代尚未执行的自动重试，避免两条请求串扰
        retryHelper.reset()
        requestAd(context.applicationContext, buildAdSlot(), listener)
    }

    private fun buildAdSlot(): AdSlot = AdSlot.Builder()
        .setCodeId(adUnitId)
        .setOrientation(TTAdConstant.ORIENTATION_VERTICAL)
        .build()

    private fun requestAd(context: Context, slot: AdSlot, listener: AdLoadListener) {
        GroMoreAdManager.createAdNative(context)
            .loadFullScreenVideoAd(slot, object : TTAdNative.FullScreenVideoAdListener {
                override fun onError(code: Int, message: String?) {
                    Log.w(TAG, "插屏加载失败: code=$code, msg=$message")
                    // 真实请求失败自动重试，重试耗尽后才回调业务失败，中间过程对业务方透明
                    if (retryHelper.retryAfterDelay { requestAd(context, slot, listener) }) {
                        return
                    }
                    listener.onAdError(AdError(code, message.orEmpty()))
                }

                override fun onFullScreenVideoAdLoad(ad: TTFullScreenVideoAd?) {
                    Log.i(TAG, "插屏加载成功")
                    retryHelper.reset()
                    if (ad != null) {
                        AdCacheManager.put(AdType.INTERSTITIAL, ad)
                    }
                    listener.onAdLoaded()
                }

                override fun onFullScreenVideoCached() {
                    // 即将废弃的旧回调，统一走带参版本
                }

                override fun onFullScreenVideoCached(ad: TTFullScreenVideoAd?) {
                    Log.i(TAG, "插屏素材缓存完成")
                }
            })
    }

    /**
     * 构建 SDK 交互监听：在 [show] 时绑定到取出的广告对象上，
     * 事件直接转发给本次展示传入的 listener，不随广告对象进入缓存
     *
     * [autoReloadOnClose] 为 true 时，在转发关闭事件后自动补位加载下一条广告
     */
    private fun buildInteractionListener(
        context: Context,
        listener: InterstitialAdEventListener,
        autoReloadOnClose: Boolean,
    ): TTFullScreenVideoAd.FullScreenVideoAdInteractionListener =
        object : TTFullScreenVideoAd.FullScreenVideoAdInteractionListener {
            override fun onAdShow() = listener.onAdShown()

            override fun onAdVideoBarClick() = listener.onAdClicked()

            override fun onAdClose() {
                listener.onAdClosed()
                if (autoReloadOnClose) {
                    Log.i(TAG, "广告关闭（已消耗），自动补位加载下一条")
                    load(context, replenishListener)
                }
            }

            override fun onVideoComplete() = listener.onVideoCompleted()

            override fun onSkippedVideo() {
                Log.i(TAG, "用户跳过插屏视频")
            }
        }

    /**
     * 展示缓存的插屏广告（命中即从 [AdCacheManager] 消耗一条）
     *
     * 交互监听在展示时绑定到该条广告上，事件回调本次展示传入的 listener
     *
     * @param listener 本次展示的事件回调：曝光/点击/关闭/视频完成
     * @param autoReloadOnClose true 表示广告关闭（已消耗）后自动补位加载下一条，维持缓存水位
     * @param strategy 本次取用缓存的覆盖策略，null 表示使用 AdConfig 配置的默认策略
     * @return false 表示缓存未命中（尚未 load、已被展示消耗或缓存过期）
     */
    fun show(
        activity: Activity,
        listener: InterstitialAdEventListener,
        autoReloadOnClose: Boolean = false,
        strategy: AdSelectStrategy? = null,
    ): Boolean {
        val ad = AdCacheManager.take<TTFullScreenVideoAd>(AdType.INTERSTITIAL, strategy)
        if (ad == null) {
            Log.w(TAG, "展示失败：插屏缓存未命中")
            return false
        }
        ad.setFullScreenVideoAdInteractionListener(
            buildInteractionListener(activity.applicationContext, listener, autoReloadOnClose),
        )
        ad.showFullScreenVideoAd(activity)
        return true
    }

    /**
     * 缓存优先自动展示：命中缓存则立即展示，未命中则自动发起现场加载、加载成功后自动展示
     *
     * 把"先查缓存、再回退现场加载"的编排沉淀在 Loader 内部，业务方一次调用即可完成展示链路；
     * 默认开启关闭后自动补位，形成"展示消耗 → 关闭补位"的缓存水位自维持闭环
     *
     * @param listener 本次展示的事件回调，展示时绑定
     * @param autoReloadOnClose true 表示广告关闭（已消耗）后自动补位加载下一条，维持缓存水位
     * @param strategy 本次取用缓存的覆盖策略，null 表示使用 AdConfig 配置的默认策略
     * @param loadListener 未命中时现场加载的结果回调，默认 null 仅内部日志
     * @return true 表示缓存命中已直接展示；false 表示未命中、已启动现场加载，展示结果异步回调
     */
    fun autoShow(
        activity: Activity,
        listener: InterstitialAdEventListener,
        autoReloadOnClose: Boolean = true,
        strategy: AdSelectStrategy? = null,
        loadListener: AdLoadListener? = null,
    ): Boolean {
        if (show(activity, listener, autoReloadOnClose, strategy)) {
            return true
        }
        Log.i(TAG, "缓存未命中，现场加载成功后自动展示")
        load(activity, object : AdLoadListener {
            override fun onAdError(error: AdError) {
                Log.w(TAG, "现场加载失败，无法自动展示: $error")
                loadListener?.onAdError(error)
            }

            override fun onAdLoaded() {
                loadListener?.onAdLoaded()
                mainHandler.post { show(activity, listener, autoReloadOnClose, strategy) }
            }
        })
        return false
    }

    /** 释放缓存中的插屏广告引用，并取消尚未执行的自动重试 */
    fun destroy() {
        retryHelper.reset()
        AdCacheManager.remove<TTFullScreenVideoAd>(AdType.INTERSTITIAL)
    }

    private companion object {
        private const val TAG = "InterstitialAdLoader"

        /** 关闭后自动补位加载的结果回调：后台行为，仅打日志，不对业务暴露 */
        private val replenishListener = object : AdLoadListener {
            override fun onAdError(error: AdError) {
                Log.w(TAG, "关闭后自动补位加载失败: $error")
            }

            override fun onAdLoaded() {
                Log.i(TAG, "关闭后自动补位加载成功，缓存水位已恢复")
            }
        }
    }
}
