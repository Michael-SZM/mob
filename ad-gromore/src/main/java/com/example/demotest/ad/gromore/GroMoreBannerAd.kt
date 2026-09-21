package com.example.demotest.ad.gromore

import android.view.View
import com.bytedance.sdk.openadsdk.TTNativeExpressAd
import com.example.demotest.ad.api.AdError
import com.example.demotest.ad.api.AdEventListener
import com.example.demotest.ad.api.AdLogger
import com.example.demotest.ad.api.IBannerAd

/**
 * GroMore 模板 Banner 协议包装：把 TTNativeExpressAd 适配为 [IBannerAd]
 *
 * 职责：出价读取、SDK 交互监听桥接、模板渲染触发（[render]）、渲染视图暴露与资源释放
 *
 * 模板 Banner 为"先加载渲染、后挂载"的两段式流程：SDK 加载成功仅表示广告数据就绪，
 * 必须调用 [render] 且渲染成功（onRenderSuccess）后 [adView] 才可用
 */
internal class GroMoreBannerAd(private val ad: TTNativeExpressAd) : IBannerAd {

    override val ecpm: Double? = runCatching {
        parseEcpm(ad.mediationManager?.bestEcpm?.ecpm)
    }.getOrNull()

    /** 本次展示的事件回调，挂载时经 [setEventListener] 设置，桥接器据此转发交互事件 */
    private var listener: AdEventListener? = null

    /** 本次渲染的结果回调，由 [render] 设置、结果回调后置空 */
    private var renderCallback: ((AdError?) -> Unit)? = null

    override val adView: View?
        get() = ad.expressAdView

    /**
     * SDK 交互监听桥接器：模板渲染回调要求在 render 前必须绑定，无法延迟到展示时，
     * 因此固定绑定本桥接器，由它把事件转发到对应的业务回调
     */
    private val interactionBridge = object : TTNativeExpressAd.ExpressAdInteractionListener {

        override fun onAdClicked(view: View?, type: Int) {
            listener?.onAdClicked()
        }

        override fun onAdShow(view: View?, type: Int) {
            listener?.onAdShown()
        }

        override fun onRenderFail(view: View?, msg: String?, code: Int) {
            AdLogger.w(TAG, "Banner 渲染失败: code=$code, msg=$msg")
            val callback = renderCallback
            renderCallback = null
            callback?.invoke(AdError(code, msg.orEmpty()))
        }

        override fun onRenderSuccess(view: View?, width: Float, height: Float) {
            AdLogger.i(TAG, "Banner 渲染成功: ${width}x$height")
            val callback = renderCallback
            renderCallback = null
            callback?.invoke(null)
        }
    }

    override fun setEventListener(listener: AdEventListener) {
        this.listener = listener
    }

    /**
     * 触发模板渲染（两段式流程的第二段）
     *
     * @param onResult 渲染结果：null 表示成功（[adView] 此时可用），非 null 为渲染失败错误
     */
    fun render(onResult: (AdError?) -> Unit) {
        renderCallback = onResult
        ad.setExpressInteractionListener(interactionBridge)
        ad.render()
    }

    override fun destroy() {
        listener = null
        renderCallback = null
        ad.destroy()
    }

    private companion object {
        private const val TAG = "GroMoreBannerAd"
    }
}
