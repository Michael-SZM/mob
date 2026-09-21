package com.example.demotest

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.demotest.ad.api.AdError
import com.example.demotest.ad.api.AdEventListener
import com.example.demotest.ad.api.AdPlatform
import com.example.demotest.ad.api.BannerAdRequest
import com.example.demotest.ad.api.IBannerAd
import com.example.demotest.ad.api.IInterstitialAd
import com.example.demotest.ad.api.IRewardedAd
import com.example.demotest.ad.api.ISplashAd
import com.example.demotest.ad.api.InterstitialAdEventListener
import com.example.demotest.ad.api.InterstitialAdRequest
import com.example.demotest.ad.api.RewardedAdEventListener
import com.example.demotest.ad.api.RewardedAdRequest
import com.example.demotest.ad.api.SplashAdEventListener
import com.example.demotest.ad.api.SplashAdRequest
import kotlin.random.Random

/**
 * Mock 广告平台适配器（演示用，无真实 SDK）
 *
 * 仅实现 :ad 模块的 [AdPlatform] 协议，与真实平台（如 gromore 包的 GroMorePlatform）
 * 拥有完全相同的接入方式，用于演示：
 * 1. 多平台同时接入：与 GroMore 一起注册进 AdConfig.platforms；
 * 2. 竞价 × 瀑布流双管道赛跑：模拟真实请求耗时与出价（[baseEcpm] + 每次 0~15 分随机浮动），
 *    可在日志中观察各平台出价、双管道比价与竞得结果。
 *
 * @param platformName 平台名称（日志与广告位映射键使用）
 * @param baseEcpm 基准出价（分），每次请求在此基础上随机浮动，模拟多家平台出价差异
 * @param latencyMs 模拟请求耗时（毫秒），各平台耗时不同可观察瀑布流的顺序回退
 */
class MockAdPlatform(
    private val platformName: String,
    private val baseEcpm: Double,
    private val latencyMs: Long,
) : AdPlatform {

    override val name: String get() = platformName

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false

    /** 模拟 SDK 初始化耗时后成功 */
    override fun init(context: Context, onResult: (Result<Unit>) -> Unit) {
        handler.postDelayed({
            initialized = true
            onResult(Result.success(Unit))
        }, INIT_DELAY_MS)
    }

    override fun isReady(): Boolean = initialized

    override fun loadRewarded(
        context: Context,
        request: RewardedAdRequest,
        onLoaded: (IRewardedAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = requireUnitId(request.adUnitIds[this], "激励视频", onError) ?: return
        handler.postDelayed({ onLoaded(MockRewardedAd(unitId, nextEcpm())) }, latencyMs)
    }

    override fun loadInterstitial(
        context: Context,
        request: InterstitialAdRequest,
        onLoaded: (IInterstitialAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = requireUnitId(request.adUnitIds[this], "插屏", onError) ?: return
        handler.postDelayed({ onLoaded(MockInterstitialAd(unitId, nextEcpm())) }, latencyMs)
    }

    override fun loadSplash(
        context: Context,
        request: SplashAdRequest,
        onLoaded: (ISplashAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = requireUnitId(request.adUnitIds[this], "开屏", onError) ?: return
        handler.postDelayed({ onLoaded(MockSplashAd(unitId, nextEcpm())) }, latencyMs)
    }

    override fun loadBanner(
        context: Context,
        request: BannerAdRequest,
        onLoaded: (IBannerAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = requireUnitId(request.adUnitIds[this], "Banner", onError) ?: return
        handler.postDelayed({ onLoaded(MockBannerAd(context, unitId, nextEcpm())) }, latencyMs)
    }

    /** 读取本平台广告位 ID，未配置（null/空串）时按协议约定回调失败，不参与本次请求 */
    private fun requireUnitId(unitId: String?, adLabel: String, onError: (AdError) -> Unit): String? {
        if (unitId.isNullOrBlank()) {
            onError(AdError(AdError.CODE_EMPTY_AD_UNIT, "$platformName 未配置$adLabel 广告位 ID"))
            return null
        }
        return unitId
    }

    /** 模拟平台出价：基准价 + 0~15 分随机浮动 */
    private fun nextEcpm(): Double = baseEcpm + Random.nextInt(0, ECPM_JITTER_RANGE)

    private companion object {
        const val INIT_DELAY_MS = 100L
        const val ECPM_JITTER_RANGE = 16
    }
}

/** Mock 激励视频：show 后依次模拟 曝光 → 播放完成 → 激励发放 → 关闭 的完整事件序列 */
private class MockRewardedAd(unitId: String, override val ecpm: Double?) : IRewardedAd {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: RewardedAdEventListener? = null

    override fun setEventListener(listener: RewardedAdEventListener) {
        this.listener = listener
    }

    override fun show(activity: Activity) {
        listener?.onAdShown()
        handler.postDelayed({
            listener?.onVideoCompleted()
            listener?.onRewarded(true, "金币", 1)
            listener?.onAdClosed()
        }, SHOW_DURATION_MS)
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        listener = null
    }

    private companion object {
        const val SHOW_DURATION_MS = 1200L
    }
}

/** Mock 插屏：show 后模拟 曝光 → 播放完成 → 关闭 */
private class MockInterstitialAd(unitId: String, override val ecpm: Double?) : IInterstitialAd {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: InterstitialAdEventListener? = null

    override fun setEventListener(listener: InterstitialAdEventListener) {
        this.listener = listener
    }

    override fun show(activity: Activity) {
        listener?.onAdShown()
        handler.postDelayed({
            listener?.onVideoCompleted()
            listener?.onAdClosed()
        }, SHOW_DURATION_MS)
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        listener = null
    }

    private companion object {
        const val SHOW_DURATION_MS = 1200L
    }
}

/** Mock 开屏：show 时向容器挂载纯色 TextView 模拟开屏视图，短暂展示后回调关闭 */
private class MockSplashAd(private val unitId: String, override val ecpm: Double?) : ISplashAd {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: SplashAdEventListener? = null

    override fun setEventListener(listener: SplashAdEventListener) {
        this.listener = listener
    }

    override fun show(container: ViewGroup) {
        val mockView = TextView(container.context).apply {
            text = "Mock 开屏广告\n$unitId | ecpm=$ecpm"
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC1F3864"))
            setTextColor(Color.WHITE)
            setOnClickListener { listener?.onAdClicked() }
        }
        container.removeAllViews()
        container.addView(mockView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        listener?.onAdShown()
        handler.postDelayed({ listener?.onAdClosed() }, SHOW_DURATION_MS)
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        listener = null
    }

    private companion object {
        const val SHOW_DURATION_MS = 1500L
    }
}

/** Mock Banner：加载成功即视图可用（模拟渲染完成），挂载（绑定监听）后短暂延时回调曝光 */
private class MockBannerAd(context: Context, unitId: String, override val ecpm: Double?) : IBannerAd {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: AdEventListener? = null

    override val adView: View = TextView(context).apply {
        text = "Mock Banner 广告 | $unitId | ecpm=$ecpm"
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#CC1B5E20"))
        setTextColor(Color.WHITE)
        minimumHeight = (BANNER_HEIGHT_DP * resources.displayMetrics.density).toInt()
        setOnClickListener { listener?.onAdClicked() }
    }

    override fun setEventListener(listener: AdEventListener) {
        this.listener = listener
        // setEventListener 由编排层在挂载时调用，此处模拟挂载后短暂延时曝光
        handler.postDelayed({ this.listener?.onAdShown() }, SHOWN_DELAY_MS)
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        listener = null
    }

    private companion object {
        const val BANNER_HEIGHT_DP = 100
        const val SHOWN_DELAY_MS = 300L
    }
}
