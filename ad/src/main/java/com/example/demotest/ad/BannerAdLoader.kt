package com.example.demotest.ad

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTNativeExpressAd

/**
 * 模板 Banner 广告加载器
 *
 * 模板 Banner 采用"先加载渲染、后挂载"的两段式流程：
 * [load] 内部会自动触发 [TTNativeExpressAd.render]，渲染成功后回调
 * [AdEventListener.onAdLoaded]，此时调用 [attach] 挂载到容器即可。
 *
 * 缓存与重试：加载成功的广告对象统一存入 [AdCacheManager]；SDK 要求渲染回调必须在
 * render 前绑定，因此固定绑定一个无状态桥接器——渲染结果转发给本次 load 的回调，
 * 交互事件在 [attach] 时转发给本次展示传入的 listener，业务回调不随广告进缓存；
 * SDK 真实请求失败后会自动重试，以提升加载成功率；
 * [autoShow] 进一步封装"命中即挂载、未命中自动加载渲染后挂载"的一站式编排。
 *
 * @param adUnitId GroMore 模板 Banner 广告位 ID（1 开头）
 * @param widthDp 期望模板宽度（dp），须与 GroMore 后台创建代码位时选择的模板尺寸一致
 * @param heightDp 期望模板高度（dp），同上
 */
class BannerAdLoader(
    private val adUnitId: String,
    private val widthDp: Float = DEFAULT_WIDTH_DP,
    private val heightDp: Float = DEFAULT_HEIGHT_DP,
) {

    /** SDK 请求失败后的自动重试编排，成功或新一次 load 时复位 */
    private val retryHelper = AdRetryHelper()

    /** autoShow 编排中的主线程调度：加载/渲染回调不保证在主线程，挂载必须切主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本次加载的结果回调，渲染成功/失败后置空；桥接器据此转发渲染结果 */
    private var pendingLoadListener: AdLoadListener? = null

    /** 本次展示的事件回调，[attach] 时设置、[destroy] 时清空；桥接器据此转发交互事件 */
    private var activeListener: AdEventListener? = null

    /**
     * SDK 交互监听桥接器：模板渲染回调要求在 render 前必须绑定，无法延迟到展示时，
     * 因此固定绑定本无状态桥接器，由它负责把事件转发到对应的业务回调
     */
    private val interactionBridge = object : TTNativeExpressAd.ExpressAdInteractionListener {

        override fun onAdClicked(view: View?, type: Int) {
            activeListener?.onAdClicked()
        }

        override fun onAdShow(view: View?, type: Int) {
            activeListener?.onAdShown()
        }

        override fun onRenderFail(view: View?, msg: String?, code: Int) {
            Log.w(TAG, "Banner 渲染失败: code=$code, msg=$msg")
            // 渲染失败发生在加载成功之后，重试无意义；对象不可用，同步从缓存移除
            AdCacheManager.remove<TTNativeExpressAd>(AdType.BANNER)
            pendingLoadListener?.onAdError(AdError(code, msg.orEmpty()))
            pendingLoadListener = null
        }

        override fun onRenderSuccess(view: View?, width: Float, height: Float) {
            Log.i(TAG, "Banner 渲染成功: ${width}x$height")
            pendingLoadListener?.onAdLoaded()
            pendingLoadListener = null
        }
    }

    /** 加载模板 Banner 并触发渲染（仅入缓存，不绑定展示监听） */
    fun load(context: Context, listener: AdLoadListener) {
        if (!GroMoreAdManager.isReady()) {
            listener.onAdError(AdError(AdError.CODE_SDK_NOT_READY, "GroMore SDK 尚未初始化成功，禁止发起广告请求"))
            return
        }
        if (adUnitId.isBlank()) {
            listener.onAdError(AdError(AdError.CODE_EMPTY_AD_UNIT, "Banner 广告位 ID 为空，请在 GroMore 后台申请"))
            return
        }
        // 新一次加载取代尚未执行的自动重试，避免两条请求串扰
        retryHelper.reset()
        requestAd(context.applicationContext, buildAdSlot(), listener)
    }

    private fun buildAdSlot(): AdSlot = AdSlot.Builder()
        .setCodeId(adUnitId)
        .setExpressViewAcceptedSize(widthDp, heightDp) // 模板期望尺寸，与后台代码位保持一致
        .setAdCount(1)
        .build()

    private fun requestAd(context: Context, slot: AdSlot, listener: AdLoadListener) {
        GroMoreAdManager.createAdNative(context)
            .loadBannerExpressAd(slot, object : TTAdNative.NativeExpressAdListener {
                override fun onError(code: Int, message: String?) {
                    Log.w(TAG, "Banner 加载失败: code=$code, msg=$message")
                    // 真实请求失败自动重试，重试耗尽后才回调业务失败，中间过程对业务方透明
                    if (retryHelper.retryAfterDelay { requestAd(context, slot, listener) }) {
                        return
                    }
                    listener.onAdError(AdError(code, message.orEmpty()))
                }

                override fun onNativeExpressAdLoad(ads: MutableList<TTNativeExpressAd>?) {
                    retryHelper.reset()
                    val ad = ads?.firstOrNull()
                    if (ad == null) {
                        listener.onAdError(AdError(AdError.CODE_AD_NOT_LOADED, "Banner 返回列表为空"))
                        return
                    }
                    Log.i(TAG, "Banner 加载成功，开始渲染模板")
                    pendingLoadListener = listener
                    AdCacheManager.put(AdType.BANNER, ad)
                    ad.setExpressInteractionListener(interactionBridge)
                    // 触发模板渲染，成功后 getExpressAdView 才可用
                    ad.render()
                }
            })
    }

    /**
     * 将渲染成功的 Banner 挂载到容器（会先清空容器，不消耗缓存）
     *
     * 挂载成功后容器会被置为可见
     *
     * @param listener 本次展示的事件回调：曝光/点击/关闭
     * @param strategy 本次取用缓存的覆盖策略，null 表示使用 AdConfig 配置的默认策略
     * @return false 表示缓存未命中或渲染尚未完成（尚未 load、加载失败或渲染未完成）
     */
    fun attach(
        container: ViewGroup,
        listener: AdEventListener,
        strategy: AdSelectStrategy? = null,
    ): Boolean {
        val ad = AdCacheManager.peek<TTNativeExpressAd>(AdType.BANNER, strategy)
        val view = ad?.expressAdView
        if (view == null) {
            Log.w(TAG, "挂载失败：Banner 缓存未命中或渲染未完成")
            return false
        }
        // 展示时才确定事件回调对象，交互事件由桥接器转发到这里
        activeListener = listener
        container.removeAllViews()
        container.addView(view)
        container.visibility = View.VISIBLE
        return true
    }

    /**
     * 缓存优先自动挂载：命中缓存则立即挂载，未命中或渲染未完成则自动加载、渲染成功后自动挂载
     *
     * 把"先查缓存、再回退现场加载"的编排沉淀在 Loader 内部，业务方一次调用即可完成展示链路
     *
     * @param container 展示容器（同时作为现场加载的 Context 来源）
     * @param listener 本次展示的事件回调，展示时绑定
     * @param strategy 本次取用缓存的覆盖策略，null 表示使用 AdConfig 配置的默认策略
     * @param loadListener 未命中时现场加载的结果回调（Banner 以渲染成功为加载成功），默认 null 仅内部日志
     * @return true 表示缓存命中已直接挂载；false 表示未命中、已启动现场加载，展示结果异步回调
     */
    fun autoShow(
        container: ViewGroup,
        listener: AdEventListener,
        strategy: AdSelectStrategy? = null,
        loadListener: AdLoadListener? = null,
    ): Boolean {
        if (attach(container, listener, strategy)) {
            return true
        }
        Log.i(TAG, "缓存未命中或渲染未完成，现场加载成功后自动挂载")
        load(container.context, object : AdLoadListener {
            override fun onAdError(error: AdError) {
                Log.w(TAG, "现场加载失败，无法自动挂载: $error")
                loadListener?.onAdError(error)
            }

            override fun onAdLoaded() {
                loadListener?.onAdLoaded()
                mainHandler.post { attach(container, listener, strategy) }
            }
        })
        return false
    }

    /** 销毁缓存的 Banner 并释放 SDK 资源，离开页面时必须调用 */
    fun destroy() {
        retryHelper.reset()
        pendingLoadListener = null
        activeListener = null
        AdCacheManager.remove<TTNativeExpressAd>(AdType.BANNER).forEach { it.destroy() }
    }

    private companion object {
        private const val TAG = "BannerAdLoader"

        /** 穿山甲标准模板 Banner 尺寸（600x400 dp） */
        private const val DEFAULT_WIDTH_DP = 600f
        private const val DEFAULT_HEIGHT_DP = 400f
    }
}
