package com.example.demotest.ad.gromore

import android.content.Context
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.CSJAdError
import com.bytedance.sdk.openadsdk.CSJSplashAd
import com.bytedance.sdk.openadsdk.TTAdConfig
import com.bytedance.sdk.openadsdk.TTAdConstant
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTAdSdk
import com.bytedance.sdk.openadsdk.TTFullScreenVideoAd
import com.bytedance.sdk.openadsdk.TTNativeExpressAd
import com.bytedance.sdk.openadsdk.TTRewardVideoAd
import com.example.demotest.ad.api.AdError
import com.example.demotest.ad.api.AdLogger
import com.example.demotest.ad.api.AdPlatform
import com.example.demotest.ad.api.BannerAdRequest
import com.example.demotest.ad.api.IBannerAd
import com.example.demotest.ad.api.IInterstitialAd
import com.example.demotest.ad.api.IRewardedAd
import com.example.demotest.ad.api.ISplashAd
import com.example.demotest.ad.api.InterstitialAdRequest
import com.example.demotest.ad.api.RewardedAdRequest
import com.example.demotest.ad.api.SplashAdRequest

/**
 * GroMore 平台适配器：把穿山甲 GroMore 融合 SDK 适配为 [AdPlatform] 协议
 *
 * 职责边界（协议翻译）：
 * 1. SDK 初始化与就绪判断；
 * 2. 把协议请求对象（XxxAdRequest）翻译为 GroMore 的 AdSlot；
 * 3. 把 SDK 广告对象包装为 [com.example.demotest.ad.api.IAd] 协议对象（gromore 包内包装类）；
 * 4. 把 SDK 错误统一为 [AdError] 回传。
 *
 * 不做缓存、重试与展示编排——这些通用能力由 api 包编排层 Loader 统一提供，
 * 接入新平台时只需按本类模式实现 [AdPlatform] 即可。
 *
 * @param appId 穿山甲 GroMore 应用 ID（在 GroMore 后台创建应用后获得）
 * @param appName 应用名称，仅用于 SDK 初始化标识
 * @param debug 是否开启 debug 模式（测试阶段 true，正式发布请置为 false）
 */
class GroMorePlatform(
    private val appId: String,
    private val appName: String,
    private val debug: Boolean = false,
) : AdPlatform {

    override val name: String = "GroMore"

    @Volatile
    private var initialized = false

    /**
     * 初始化 GroMore SDK（TTAdSdk.init 保存配置 + TTAdSdk.start 真正启动）
     *
     * 建议在用户同意隐私协议后再调用，以满足合规要求；
     * useMediation 仅可设置一次，开启后即具备 GroMore 聚合能力
     */
    override fun init(context: Context, onResult: (Result<Unit>) -> Unit) {
        if (initialized) {
            // 单进程下多次初始化以首次配置为准，这里直接视为成功，避免重复 start
            onResult(Result.success(Unit))
            return
        }
        if (appId.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("appId 不能为空，请在穿山甲 GroMore 后台申请")))
            return
        }
        val ttConfig = TTAdConfig.Builder()
            .appId(appId)
            .appName(appName)
            .useMediation(true) // 开启 GroMore 聚合能力，融合 SDK 必开且仅可设置一次
            .debug(debug)
            .allowShowNotify(true) // 允许通知栏展示下载进度，关闭存在合规风险
            .supportMultiProcess(false) // 本工程为单进程应用，保持 false
            .build()
        if (!TTAdSdk.init(context.applicationContext, ttConfig)) {
            onResult(Result.failure(IllegalStateException("TTAdSdk.init 调用失败")))
            return
        }
        TTAdSdk.start(object : TTAdSdk.Callback {
            override fun success() {
                initialized = true
                AdLogger.i(TAG, "GroMore SDK 初始化成功, version=${TTAdSdk.getAdManager().getSDKVersion()}")
                onResult(Result.success(Unit))
            }

            override fun fail(code: Int, errorMsg: String?) {
                AdLogger.w(TAG, "GroMore SDK 初始化失败: code=$code, msg=$errorMsg")
                onResult(Result.failure(IllegalStateException("GroMore 初始化失败: code=$code, msg=$errorMsg")))
            }
        })
    }

    override fun isReady(): Boolean = runCatching { TTAdSdk.isSdkReady() }.getOrDefault(false)

    override fun loadRewarded(
        context: Context,
        request: RewardedAdRequest,
        onLoaded: (IRewardedAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = request.adUnitIds[this]
        if (unitId.isNullOrBlank()) {
            onError(AdError(AdError.CODE_EMPTY_AD_UNIT, "GroMore 未配置激励视频广告位 ID"))
            return
        }
        val slot = AdSlot.Builder()
            .setCodeId(unitId) // GroMore 广告位 ID，SDK 自行请求瀑布流下配置的代码位
            .setUserID(request.userId)
            .setRewardName(request.rewardName)
            .setRewardAmount(request.rewardAmount)
            .setOrientation(TTAdConstant.ORIENTATION_VERTICAL) // 与后台创建代码位的横竖屏保持一致
            .build()
        createAdNative(context).loadRewardVideoAd(slot, object : TTAdNative.RewardVideoAdListener {
            override fun onError(code: Int, message: String?) {
                AdLogger.w(TAG, "激励视频加载失败: code=$code, msg=$message")
                onError(AdError(code, message.orEmpty()))
            }

            override fun onRewardVideoAdLoad(ad: TTRewardVideoAd?) {
                AdLogger.i(TAG, "激励视频加载成功")
                if (ad == null) {
                    onError(AdError(AdError.CODE_AD_NOT_LOADED, "激励视频返回对象为空"))
                } else {
                    onLoaded(GroMoreRewardedAd(ad))
                }
            }

            override fun onRewardVideoCached() {
                // 即将废弃的旧回调，统一走带参版本
            }

            override fun onRewardVideoCached(ad: TTRewardVideoAd?) {
                AdLogger.i(TAG, "激励视频素材缓存完成")
            }
        })
    }

    override fun loadInterstitial(
        context: Context,
        request: InterstitialAdRequest,
        onLoaded: (IInterstitialAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = request.adUnitIds[this]
        if (unitId.isNullOrBlank()) {
            onError(AdError(AdError.CODE_EMPTY_AD_UNIT, "GroMore 未配置插屏广告位 ID"))
            return
        }
        val slot = AdSlot.Builder()
            .setCodeId(unitId)
            .setOrientation(TTAdConstant.ORIENTATION_VERTICAL)
            .build()
        createAdNative(context).loadFullScreenVideoAd(slot, object : TTAdNative.FullScreenVideoAdListener {
            override fun onError(code: Int, message: String?) {
                AdLogger.w(TAG, "插屏加载失败: code=$code, msg=$message")
                onError(AdError(code, message.orEmpty()))
            }

            override fun onFullScreenVideoAdLoad(ad: TTFullScreenVideoAd?) {
                AdLogger.i(TAG, "插屏加载成功")
                if (ad == null) {
                    onError(AdError(AdError.CODE_AD_NOT_LOADED, "插屏返回对象为空"))
                } else {
                    onLoaded(GroMoreInterstitialAd(ad))
                }
            }

            override fun onFullScreenVideoCached() {
                // 即将废弃的旧回调，统一走带参版本
            }

            override fun onFullScreenVideoCached(ad: TTFullScreenVideoAd?) {
                AdLogger.i(TAG, "插屏素材缓存完成")
            }
        })
    }

    override fun loadSplash(
        context: Context,
        request: SplashAdRequest,
        onLoaded: (ISplashAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = request.adUnitIds[this]
        if (unitId.isNullOrBlank()) {
            onError(AdError(AdError.CODE_EMPTY_AD_UNIT, "GroMore 未配置开屏广告位 ID"))
            return
        }
        val slot = AdSlot.Builder()
            .setCodeId(unitId)
            .build()
        createAdNative(context).loadSplashAd(
            slot,
            object : TTAdNative.CSJSplashAdListener {
                override fun onSplashLoadSuccess(ad: CSJSplashAd?) {
                    AdLogger.i(TAG, "开屏加载成功")
                    if (ad == null) {
                        onError(AdError(AdError.CODE_AD_NOT_LOADED, "开屏返回对象为空"))
                    } else {
                        onLoaded(GroMoreSplashAd(ad))
                    }
                }

                override fun onSplashLoadFail(error: CSJAdError?) {
                    AdLogger.w(TAG, "开屏加载失败: code=${error?.code}, msg=${error?.msg}")
                    onError(AdError(error?.code ?: -1, error?.msg.orEmpty()))
                }

                override fun onSplashRenderSuccess(ad: CSJSplashAd?) {
                    AdLogger.i(TAG, "开屏渲染成功")
                }

                override fun onSplashRenderFail(ad: CSJSplashAd?, error: CSJAdError?) {
                    AdLogger.w(TAG, "开屏渲染失败: code=${error?.code}, msg=${error?.msg}")
                    onError(AdError(error?.code ?: -1, error?.msg.orEmpty()))
                }
            },
            request.timeoutMs,
        )
    }

    override fun loadBanner(
        context: Context,
        request: BannerAdRequest,
        onLoaded: (IBannerAd) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        val unitId = request.adUnitIds[this]
        if (unitId.isNullOrBlank()) {
            onError(AdError(AdError.CODE_EMPTY_AD_UNIT, "GroMore 未配置 Banner 广告位 ID"))
            return
        }
        val slot = AdSlot.Builder()
            .setCodeId(unitId)
            .setExpressViewAcceptedSize(request.widthDp, request.heightDp) // 模板期望尺寸，与后台代码位保持一致
            .setAdCount(1)
            .build()
        createAdNative(context).loadBannerExpressAd(slot, object : TTAdNative.NativeExpressAdListener {
            override fun onError(code: Int, message: String?) {
                AdLogger.w(TAG, "Banner 加载失败: code=$code, msg=$message")
                onError(AdError(code, message.orEmpty()))
            }

            override fun onNativeExpressAdLoad(ads: MutableList<TTNativeExpressAd>?) {
                val ad = ads?.firstOrNull()
                if (ad == null) {
                    onError(AdError(AdError.CODE_AD_NOT_LOADED, "Banner 返回列表为空"))
                    return
                }
                AdLogger.i(TAG, "Banner 加载成功，开始渲染模板")
                val bannerAd = GroMoreBannerAd(ad)
                // 两段式：渲染成功才算加载成功，成功的对象才交由编排层入缓存
                bannerAd.render { error ->
                    if (error == null) {
                        onLoaded(bannerAd)
                    } else {
                        onError(error)
                    }
                }
            }
        })
    }

    /** 创建广告请求入口；调用前编排层已确保 [isReady] 为 true */
    private fun createAdNative(context: Context): TTAdNative =
        TTAdSdk.getAdManager().createAdNative(context.applicationContext)

    private companion object {
        private const val TAG = "GroMorePlatform"
    }
}
