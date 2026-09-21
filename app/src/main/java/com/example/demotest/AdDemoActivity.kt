package com.example.demotest

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.demotest.ad.AdCacheManager
import com.example.demotest.ad.AdConfig
import com.example.demotest.ad.AdEventListener
import com.example.demotest.ad.AdError
import com.example.demotest.ad.AdLoadListener
import com.example.demotest.ad.AdType
import com.example.demotest.ad.BannerAdLoader
import com.example.demotest.ad.GroMoreAdManager
import com.example.demotest.ad.InterstitialAdEventListener
import com.example.demotest.ad.InterstitialAdLoader
import com.example.demotest.ad.RewardedAdEventListener
import com.example.demotest.ad.RewardedAdLoader
import com.example.demotest.ad.SplashAdEventListener
import com.example.demotest.ad.SplashAdLoader
import com.example.demotest.databinding.ActivityAdDemoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GroMore 广告加载演示页
 *
 * 演示 :ad 模块的完整接入链路：初始化 SDK → 预加载/按类型加载广告（经 AdCacheManager 缓存）
 * → 展示时缓存优先命中、未命中回退现场加载 → 全屏广告消耗后自动补位预加载。
 * 广告位 ID 默认为空占位，点击按钮会走本地前置校验并输出提示；
 * 替换为穿山甲 GroMore 后台申请的真实 ID 后即可体验完整广告流程。
 */
class AdDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdDemoBinding

    private val rewardedLoader =
        RewardedAdLoader(DEMO_AD_CONFIG.rewardedAdUnitId, rewardName = "金币", rewardAmount = 1)
    private val interstitialLoader = InterstitialAdLoader(DEMO_AD_CONFIG.interstitialAdUnitId)
    private val splashLoader = SplashAdLoader(DEMO_AD_CONFIG.splashAdUnitId)
    private val bannerLoader = BannerAdLoader(DEMO_AD_CONFIG.bannerAdUnitId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 实际项目中请在用户同意隐私协议后再初始化广告 SDK，满足合规要求
        appendLog("开始初始化 GroMore SDK（appId=$DEMO_APP_ID）...")
        GroMoreAdManager.init(this, DEMO_AD_CONFIG) { result ->
            result
                .onSuccess { postLog("GroMore SDK 初始化成功，可发起广告请求") }
                .onFailure { postLog("GroMore SDK 初始化失败: ${it.message}") }
        }

        // 激励视频扩容到 2 条：配合连续预加载实现"展示一条、备一条"的缓存水位
        AdCacheManager.setMaxCacheSize(AdType.REWARDED, 2)

        binding.btnRewarded.setOnClickListener { showOrLoadRewardedAd() }
        binding.btnInterstitial.setOnClickListener { showOrLoadInterstitialAd() }
        binding.btnSplash.setOnClickListener { showOrLoadSplashAd() }
        binding.btnBanner.setOnClickListener { showOrLoadBannerAd() }
        binding.btnPreload.setOnClickListener { preloadAllAds() }
    }

    /** 激励视频缓存优先：命中直接展示（秒开），未命中回退现场加载 */
    private fun showOrLoadRewardedAd() {
        if (rewardedLoader.show(this, rewardedShowListener())) {
            appendLog("激励视频缓存命中，直接展示")
            return
        }
        appendLog("激励视频缓存未命中，现场加载...")
        rewardedLoader.load(
            this,
            adLoadListener("激励视频") {
                postLog("激励视频现场加载成功，调起展示")
                runOnUiThread { rewardedLoader.show(this@AdDemoActivity, rewardedShowListener()) }
            },
        )
    }

    /** 激励视频展示事件回调：在 show 时随本次展示传入，不随广告对象进入缓存 */
    private fun rewardedShowListener(): RewardedAdEventListener = object : RewardedAdEventListener {
        override fun onAdShown() = postLog("激励视频曝光")

        override fun onAdClosed() {
            postLog("激励视频关闭（广告已消耗），自动补位预加载下一条")
            preloadRewardedAd()
        }

        override fun onRewarded(isValid: Boolean, rewardName: String?, rewardAmount: Int) =
            postLog("激励发放: isValid=$isValid, 奖励=$rewardName x$rewardAmount")

        override fun onVideoCompleted() = postLog("激励视频播放完毕")
    }

    /** 预加载一条激励视频写入缓存：后续点击按钮可秒开命中 */
    private fun preloadRewardedAd() {
        rewardedLoader.load(
            this,
            adLoadListener("激励视频") {
                postLog("激励视频预加载成功，已写入缓存（当前 ${AdCacheManager.size(AdType.REWARDED)} 条）")
            },
        )
    }

    /** 插屏缓存优先：命中直接展示（秒开），未命中回退现场加载 */
    private fun showOrLoadInterstitialAd() {
        if (interstitialLoader.show(this, interstitialShowListener())) {
            appendLog("插屏缓存命中，直接展示")
            return
        }
        appendLog("插屏缓存未命中，现场加载...")
        interstitialLoader.load(
            this,
            adLoadListener("插屏") {
                postLog("插屏现场加载成功，调起展示")
                runOnUiThread { interstitialLoader.show(this@AdDemoActivity, interstitialShowListener()) }
            },
        )
    }

    /** 插屏展示事件回调：在 show 时随本次展示传入，不随广告对象进入缓存 */
    private fun interstitialShowListener(): InterstitialAdEventListener = object : InterstitialAdEventListener {
        override fun onAdShown() = postLog("插屏曝光")

        override fun onAdClosed() {
            postLog("插屏关闭（广告已消耗），自动补位预加载下一条")
            preloadInterstitialAd()
        }

        override fun onVideoCompleted() = postLog("插屏视频播放完毕")
    }

    /** 预加载一条插屏写入缓存：后续点击按钮可秒开命中 */
    private fun preloadInterstitialAd() {
        interstitialLoader.load(
            this,
            adLoadListener("插屏") {
                postLog("插屏预加载成功，已写入缓存（当前 ${AdCacheManager.size(AdType.INTERSTITIAL)} 条）")
            },
        )
    }

    /** 开屏缓存优先：命中直接挂载展示，未命中回退现场加载 */
    private fun showOrLoadSplashAd() {
        if (splashLoader.show(binding.splashContainer, splashShowListener())) {
            binding.splashContainer.visibility = View.VISIBLE
            appendLog("开屏缓存命中，直接展示")
            return
        }
        appendLog("开屏缓存未命中，现场加载...")
        splashLoader.load(
            this,
            adLoadListener("开屏") {
                postLog("开屏现场加载成功，挂载到全屏容器")
                runOnUiThread {
                    binding.splashContainer.visibility = View.VISIBLE
                    splashLoader.show(binding.splashContainer, splashShowListener())
                }
            },
        )
    }

    /** 开屏展示事件回调：在 show 时随本次展示传入，不随广告对象进入缓存 */
    private fun splashShowListener(): SplashAdEventListener = object : SplashAdEventListener {
        override fun onAdShown() = postLog("开屏曝光")

        override fun onAdClosed() {
            postLog("开屏关闭，隐藏容器")
            runOnUiThread { binding.splashContainer.visibility = View.GONE }
        }
    }

    /** 预加载一条开屏写入缓存：后续点击按钮可秒开命中 */
    private fun preloadSplashAd() {
        splashLoader.load(
            this,
            adLoadListener("开屏") {
                postLog("开屏预加载成功，已写入缓存（当前 ${AdCacheManager.size(AdType.SPLASH)} 条）")
            },
        )
    }

    /** Banner 缓存优先：命中直接挂载，未命中回退现场加载 */
    private fun showOrLoadBannerAd() {
        if (bannerLoader.attach(binding.bannerContainer, bannerShowListener())) {
            binding.bannerContainer.visibility = View.VISIBLE
            appendLog("Banner 缓存命中，直接挂载")
            return
        }
        appendLog("Banner 缓存未命中，现场加载...")
        bannerLoader.load(
            this,
            adLoadListener("Banner") {
                postLog("Banner 现场加载渲染成功，挂载到底部容器")
                runOnUiThread {
                    binding.bannerContainer.visibility = View.VISIBLE
                    bannerLoader.attach(binding.bannerContainer, bannerShowListener())
                }
            },
        )
    }

    /** Banner 展示事件回调：在 attach 时随本次展示传入，不随广告对象进入缓存 */
    private fun bannerShowListener(): AdEventListener = object : AdEventListener {
        override fun onAdShown() = postLog("Banner 曝光")

        override fun onAdClosed() = postLog("Banner 关闭")
    }

    /** 预加载一条 Banner（加载并完成模板渲染）写入缓存 */
    private fun preloadBannerAd() {
        bannerLoader.load(
            this,
            adLoadListener("Banner") {
                postLog("Banner 预加载渲染成功，已写入缓存")
            },
        )
    }

    /** 一键预加载四类广告：均只写入缓存不展示，后续点击各按钮可秒开命中 */
    private fun preloadAllAds() {
        appendLog("开始预加载全部广告（写入缓存，触发时直接命中）...")
        preloadRewardedAd()
        preloadInterstitialAd()
        preloadSplashAd()
        preloadBannerAd()
    }

    /**
     * 加载结果回调工厂：load 时传入，仅关心"这次加载"的结果，不涉及展示事件
     *
     * @param tag 日志前缀（如"激励视频"）
     * @param onLoaded 加载成功后的动作；回调线程不保证在主线程，涉及 UI 或展示请自行切主线程
     */
    private fun adLoadListener(tag: String, onLoaded: () -> Unit): AdLoadListener = object : AdLoadListener {
        override fun onAdError(error: AdError) = postLog("$tag 失败: $error")

        override fun onAdLoaded() = onLoaded()
    }

    /** 广告回调线程不保证在主线程，统一经主线程写入日志 */
    private fun postLog(msg: String) {
        runOnUiThread { appendLog(msg) }
    }

    @SuppressLint("SetTextI18n")
    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        binding.tvLog.append("[$time] $msg\n")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放各加载器持有的广告对象，避免泄漏
        rewardedLoader.destroy()
        interstitialLoader.destroy()
        splashLoader.destroy()
        bannerLoader.destroy()
    }

    private companion object {
        /**
         * 穿山甲官方 Demo 应用 ID，仅用于演示初始化链路；
         * 正式接入请替换为自己在穿山甲 GroMore 后台申请的 appId（5 开头的 7 位数字）
         */
        private const val DEMO_APP_ID = "5205181"

        /**
         * TODO: 广告位 ID 均为空占位，请替换为 GroMore 后台创建的广告位 ID（1 开头）
         */
        private val DEMO_AD_CONFIG = AdConfig(
            appId = DEMO_APP_ID,
            appName = "DemoTest",
            debug = true,
            splashAdUnitId = "",
            rewardedAdUnitId = "",
            interstitialAdUnitId = "",
            bannerAdUnitId = "",
        )
    }
}
