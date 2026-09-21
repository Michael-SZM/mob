package com.example.demotest.ad.gromore

import android.app.Activity
import com.bytedance.sdk.openadsdk.TTFullScreenVideoAd
import com.example.demotest.ad.api.AdLogger
import com.example.demotest.ad.api.IInterstitialAd
import com.example.demotest.ad.api.InterstitialAdEventListener

/**
 * GroMore 插屏协议包装：把 TTFullScreenVideoAd 适配为 [IInterstitialAd]
 *
 * 职责：出价读取、SDK 交互监听构建与事件转换、展示调用
 */
internal class GroMoreInterstitialAd(private val ad: TTFullScreenVideoAd) : IInterstitialAd {

    override val ecpm: Double? = runCatching {
        parseEcpm(ad.mediationManager?.bestEcpm?.ecpm)
    }.getOrNull()

    override fun setEventListener(listener: InterstitialAdEventListener) {
        ad.setFullScreenVideoAdInteractionListener(
            object : TTFullScreenVideoAd.FullScreenVideoAdInteractionListener {
                override fun onAdShow() = listener.onAdShown()

                override fun onAdVideoBarClick() = listener.onAdClicked()

                override fun onAdClose() = listener.onAdClosed()

                override fun onVideoComplete() = listener.onVideoCompleted()

                override fun onSkippedVideo() {
                    AdLogger.i(TAG, "用户跳过插屏视频")
                }
            },
        )
    }

    override fun show(activity: Activity) {
        ad.showFullScreenVideoAd(activity)
    }

    override fun destroy() {
        // 插屏对象无显式释放接口，交由 GC 回收
    }

    private companion object {
        private const val TAG = "GroMoreInterstitialAd"
    }
}
