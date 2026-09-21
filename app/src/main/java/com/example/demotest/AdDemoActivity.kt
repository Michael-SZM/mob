package com.example.demotest

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.demotest.ad.api.AdCacheManager
import com.example.demotest.ad.api.AdConfig
import com.example.demotest.ad.api.AdEventListener
import com.example.demotest.ad.api.AdError
import com.example.demotest.ad.api.AdLoadListener
import com.example.demotest.ad.api.AdLogger
import com.example.demotest.ad.api.AdSelectStrategy
import com.example.demotest.ad.api.AdsManager
import com.example.demotest.ad.api.AdType
import com.example.demotest.ad.api.BannerAdLoader
import com.example.demotest.ad.api.InterstitialAdEventListener
import com.example.demotest.ad.api.InterstitialAdLoader
import com.example.demotest.ad.api.RewardedAdEventListener
import com.example.demotest.ad.api.RewardedAdLoader
import com.example.demotest.ad.api.SplashAdEventListener
import com.example.demotest.ad.api.SplashAdLoader
import com.example.demotest.ad.gromore.GroMorePlatform
import com.example.demotest.databinding.ActivityAdDemoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 广告加载演示页
 *
 * 演示 :ad 模块改造后的完整接入链路：经 [AdsManager] 门面初始化多平台并创建加载器
 * → 预加载/按类型加载广告（一次请求 = 竞价 × 瀑布流双管道赛跑，比价取 ecpm 最高者入缓存）
 * → 展示时缓存优先命中、未命中回退现场加载 → 全屏广告消耗后自动补位预加载。
 * 业务代码只依赖 api 包的协议类型：GroMore 与 MockA/MockB（演示用）都只是
 * [AdConfig] 中注入的平台适配器实现，更换或新增平台业务代码零改动。
 * :ad 模块全链路日志经 [AdLogger] 同时输出到 Logcat 与页面日志区，
 * 可直观观察各平台出价、双管道比价与竞得结果。
 * GroMore 广告位 ID 默认为空占位（自动跳过、不参与赛跑），
 * 替换为穿山甲后台申请的真实 ID 后即可参与竞价。
 */
class AdDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdDemoBinding

    // 加载器经门面创建，必须懒初始化：字段初始化早于 onCreate 中的 AdsManager.init；
    // 广告位 ID 按平台传入映射（GroMore 空串占位会被自动跳过，仅 Mock 平台参与）
    private val rewardedLoader by lazy {
        AdsManager.createRewardedLoader(DEMO_REWARDED_UNIT_IDS, rewardName = "金币", rewardAmount = 1)
    }
    private val interstitialLoader by lazy { AdsManager.createInterstitialLoader(DEMO_INTERSTITIAL_UNIT_IDS) }
    private val splashLoader by lazy { AdsManager.createSplashLoader(DEMO_SPLASH_UNIT_IDS) }
    private val bannerLoader by lazy { AdsManager.createBannerLoader(DEMO_BANNER_UNIT_IDS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 日志模块接入：把 :ad 模块全链路日志同时输出到 Logcat 与页面日志区，方便调试
        AdLogger.minLevel = AdLogger.Level.VERBOSE
        AdLogger.printer = AdLogger.Printer { level, tag, message, throwable ->
            Log.println(level.toLogcatPriority(), tag, message + (throwable?.let { ": $it" } ?: ""))
            postLog("[${level.label}][$tag] $message")
        }

        // 实际项目中请在用户同意隐私协议后再初始化广告 SDK，满足合规要求；
        // 业务方只依赖门面与协议，GroMore/MockA/MockB 均在此作为平台适配器注入（更换平台只改这一行）
        appendLog("开始初始化广告平台（GroMore + MockA + MockB）...")
        AdsManager.init(this, DEMO_AD_CONFIG) { result ->
            result
                .onSuccess { postLog("广告平台初始化成功，可发起广告请求") }
                .onFailure { postLog("广告平台初始化失败: ${it.message}") }
        }

        // 激励视频扩容到 2 条：配合连续预加载实现"展示一条、备一条"的缓存水位
        AdCacheManager.setMaxCacheSize(AdType.REWARDED, 2)

        binding.btnRewarded.setOnClickListener { showOrLoadRewardedAd() }
        binding.btnInterstitial.setOnClickListener { showOrLoadInterstitialAd() }
        binding.btnSplash.setOnClickListener { showOrLoadSplashAd() }
        binding.btnBanner.setOnClickListener { showOrLoadBannerAd() }
        binding.btnPreload.setOnClickListener { preloadAllAds() }
    }

    /** 激励视频缓存优先自动展示：命中秒开，未命中则由 Loader 自动加载后展示 */
    private fun showOrLoadRewardedAd() {
        // autoReloadOnClose=true：广告关闭消耗后由 Loader 自动补位加载下一条；传 false 则关闭该行为
        // strategy 按次覆盖：本次改用"出价最高"策略（不传则用 AdConfig 配置的默认策略）
        val fromCache = rewardedLoader.autoShow(
            this,
            rewardedShowListener(),
            autoReloadOnClose = true,
            strategy = AdSelectStrategy.HighestEcpm,
            loadListener = adLoadListener("激励视频"),
        )
        appendLog(if (fromCache) "激励视频缓存命中，直接展示" else "激励视频缓存未命中，现场加载...")
    }

    /** 激励视频展示事件回调：在 show 时随本次展示传入，不随广告对象进入缓存 */
    private fun rewardedShowListener(): RewardedAdEventListener = object : RewardedAdEventListener {
        override fun onAdShown() = postLog("激励视频曝光")

        override fun onAdClosed() = postLog("激励视频关闭（广告已消耗）")

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

    /** 插屏缓存优先自动展示：命中秒开，未命中则由 Loader 自动加载后展示 */
    private fun showOrLoadInterstitialAd() {
        // autoReloadOnClose=true：广告关闭消耗后由 Loader 自动补位加载下一条；传 false 则关闭该行为
        val fromCache = interstitialLoader.autoShow(
            this,
            interstitialShowListener(),
            autoReloadOnClose = true,
            loadListener = adLoadListener("插屏"),
        )
        appendLog(if (fromCache) "插屏缓存命中，直接展示" else "插屏缓存未命中，现场加载...")
    }

    /** 插屏展示事件回调：在 show 时随本次展示传入，不随广告对象进入缓存 */
    private fun interstitialShowListener(): InterstitialAdEventListener = object : InterstitialAdEventListener {
        override fun onAdShown() = postLog("插屏曝光")

        override fun onAdClosed() = postLog("插屏关闭（广告已消耗）")

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

    /** 开屏缓存优先自动展示：命中直接挂载（容器自动置可见），未命中则由 Loader 自动加载后挂载 */
    private fun showOrLoadSplashAd() {
        val fromCache = splashLoader.autoShow(
            binding.splashContainer,
            splashShowListener(),
            loadListener = adLoadListener("开屏"),
        )
        appendLog(if (fromCache) "开屏缓存命中，直接展示" else "开屏缓存未命中，现场加载...")
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

    /** Banner 缓存优先自动挂载：命中直接挂载（容器自动置可见），未命中则由 Loader 自动加载渲染后挂载 */
    private fun showOrLoadBannerAd() {
        val fromCache = bannerLoader.autoShow(
            binding.bannerContainer,
            bannerShowListener(),
            loadListener = adLoadListener("Banner"),
        )
        appendLog(if (fromCache) "Banner 缓存命中，直接挂载" else "Banner 缓存未命中，现场加载...")
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
    private fun adLoadListener(tag: String, onLoaded: () -> Unit = {}): AdLoadListener = object : AdLoadListener {
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

    /** AdLogger 级别 → Logcat 优先级映射（自定义 printer 中转发到 Logcat 用） */
    private fun AdLogger.Level.toLogcatPriority(): Int = when (this) {
        AdLogger.Level.VERBOSE -> Log.VERBOSE
        AdLogger.Level.DEBUG -> Log.DEBUG
        AdLogger.Level.INFO -> Log.INFO
        AdLogger.Level.WARN -> Log.WARN
        AdLogger.Level.ERROR -> Log.ERROR
        AdLogger.Level.NONE -> Log.INFO
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
         * TODO: GroMore 广告位 ID 均为空占位，请替换为 GroMore 后台创建的广告位 ID（1 开头）；
         * 留空时 GroMore 平台自动跳过、不参与本次竞价与瀑布流
         */
        private const val DEMO_SPLASH_ID = ""
        private const val DEMO_REWARDED_ID = ""
        private const val DEMO_INTERSTITIAL_ID = ""
        private const val DEMO_BANNER_ID = ""

        /** GroMore 平台适配器：真实 SDK 接入（广告位 ID 留空占位） */
        private val groMorePlatform = GroMorePlatform(appId = DEMO_APP_ID, appName = "DemoTest", debug = true)

        /**
         * Mock 平台 A/B：仅实现 api 协议、无真实 SDK，演示"多平台同时接入 + 双管道比价"；
         * baseEcpm 模拟多家平台出价差异（MockA 30 分 / MockB 45 分，每次请求 0~15 分浮动），
         * latencyMs 模拟请求耗时差异（MockB 更慢，便于观察瀑布流等待与回退）
         */
        private val mockPlatformA = MockAdPlatform(platformName = "MockA", baseEcpm = 30.0, latencyMs = 300)
        private val mockPlatformB = MockAdPlatform(platformName = "MockB", baseEcpm = 45.0, latencyMs = 700)

        /**
         * 通用配置：注入平台适配器列表与缓存取用策略
         * （列表顺序即瀑布流优先级：GroMore → MockA → MockB；缓存默认先进先出）
         */
        private val DEMO_AD_CONFIG = AdConfig(
            platforms = listOf(groMorePlatform, mockPlatformA, mockPlatformB),
            selectStrategy = AdSelectStrategy.Fifo,
        )

        /** 各类型广告的平台 → 广告位 ID 映射：键为平台实例，值为该平台自己的代码位 ID */
        private val DEMO_REWARDED_UNIT_IDS = mapOf(
            groMorePlatform to DEMO_REWARDED_ID,
            mockPlatformA to "mock-rewarded-a",
            mockPlatformB to "mock-rewarded-b",
        )
        private val DEMO_INTERSTITIAL_UNIT_IDS = mapOf(
            groMorePlatform to DEMO_INTERSTITIAL_ID,
            mockPlatformA to "mock-interstitial-a",
            mockPlatformB to "mock-interstitial-b",
        )
        private val DEMO_SPLASH_UNIT_IDS = mapOf(
            groMorePlatform to DEMO_SPLASH_ID,
            mockPlatformA to "mock-splash-a",
            mockPlatformB to "mock-splash-b",
        )
        private val DEMO_BANNER_UNIT_IDS = mapOf(
            groMorePlatform to DEMO_BANNER_ID,
            mockPlatformA to "mock-banner-a",
            mockPlatformB to "mock-banner-b",
        )
    }
}
