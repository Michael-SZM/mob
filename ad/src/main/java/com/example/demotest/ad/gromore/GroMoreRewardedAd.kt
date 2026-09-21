package com.example.demotest.ad.gromore

import android.app.Activity
import android.os.Bundle
import com.bytedance.sdk.openadsdk.TTRewardVideoAd
import com.example.demotest.ad.api.AdLogger
import com.example.demotest.ad.api.IRewardedAd
import com.example.demotest.ad.api.RewardedAdEventListener

/**
 * GroMore 激励视频协议包装：把 TTRewardVideoAd 适配为 [IRewardedAd]
 *
 * 职责：出价读取、SDK 交互监听构建与事件格式转换（含奖励信息 Bundle 解析）、展示调用
 */
internal class GroMoreRewardedAd(private val ad: TTRewardVideoAd) : IRewardedAd {

    override val ecpm: Double? = runCatching {
        parseEcpm(ad.mediationManager?.bestEcpm?.ecpm)
    }.getOrNull()

    override fun setEventListener(listener: RewardedAdEventListener) {
        ad.setRewardAdInteractionListener(
            object : TTRewardVideoAd.RewardAdInteractionListener {
                override fun onAdShow() = listener.onAdShown()

                override fun onAdVideoBarClick() = listener.onAdClicked()

                override fun onAdClose() = listener.onAdClosed()

                override fun onVideoComplete() = listener.onVideoCompleted()

                override fun onVideoError() {
                    AdLogger.w(TAG, "激励视频播放出错")
                }

                override fun onRewardVerify(
                    isValid: Boolean,
                    rewardType: Int,
                    rewardName: String?,
                    rewardAmount: Int,
                    errorMsg: String?,
                ) {
                    // 旧版激励回调，统一走 onRewardArrived，这里仅打日志
                    AdLogger.i(TAG, "激励校验(旧回调): isValid=$isValid, name=$rewardName, amount=$rewardAmount")
                }

                override fun onRewardArrived(isValid: Boolean, rewardType: Int, extra: Bundle?) {
                    val name = extra?.getString(TTRewardVideoAd.REWARD_EXTRA_KEY_REWARD_NAME)
                    val amount = extra?.getInt(TTRewardVideoAd.REWARD_EXTRA_KEY_REWARD_AMOUNT) ?: 0
                    AdLogger.i(TAG, "激励发放: isValid=$isValid, name=$name, amount=$amount")
                    listener.onRewarded(isValid, name, amount)
                }

                override fun onSkippedVideo() {
                    AdLogger.i(TAG, "用户跳过激励视频")
                }
            },
        )
    }

    override fun show(activity: Activity) {
        ad.showRewardVideoAd(activity)
    }

    override fun destroy() {
        // 激励视频对象无显式释放接口，交由 GC 回收
    }

    private companion object {
        private const val TAG = "GroMoreRewardedAd"
    }
}
