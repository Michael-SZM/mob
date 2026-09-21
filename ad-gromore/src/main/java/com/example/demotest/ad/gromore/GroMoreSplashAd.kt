package com.example.demotest.ad.gromore

import android.view.ViewGroup
import com.bytedance.sdk.openadsdk.CSJSplashAd
import com.example.demotest.ad.api.ISplashAd
import com.example.demotest.ad.api.SplashAdEventListener

/**
 * GroMore 开屏协议包装：把 CSJSplashAd 适配为 [ISplashAd]
 *
 * 职责：出价读取、SDK 交互监听构建与事件转换、挂载调用
 */
internal class GroMoreSplashAd(private val ad: CSJSplashAd) : ISplashAd {

    override val ecpm: Double? = runCatching {
        parseEcpm(ad.mediationManager?.bestEcpm?.ecpm)
    }.getOrNull()

    override fun setEventListener(listener: SplashAdEventListener) {
        ad.setSplashAdListener(
            object : CSJSplashAd.SplashAdListener {
                override fun onSplashAdShow(ad: CSJSplashAd?) = listener.onAdShown()

                override fun onSplashAdClick(ad: CSJSplashAd?) = listener.onAdClicked()

                override fun onSplashAdClose(ad: CSJSplashAd?, closeType: Int) = listener.onAdClosed()
            },
        )
    }

    override fun show(container: ViewGroup) {
        ad.showSplashView(container)
    }

    override fun destroy() {
        // 开屏对象无显式释放接口，交由 GC 回收
    }
}
