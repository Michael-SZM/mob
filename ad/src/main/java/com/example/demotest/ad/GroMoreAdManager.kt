package com.example.demotest.ad

import android.content.Context
import android.util.Log
import com.bytedance.sdk.openadsdk.TTAdConfig
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTAdSdk

/**
 * GroMore 融合 SDK 初始化门面
 *
 * 初始化分两步：
 * 1. [TTAdSdk.init] 仅保存配置，不获取任何个人信息；
 * 2. [TTAdSdk.start] 真正启动 SDK 并在回调中给出初始化结果。
 *
 * 必须在 start 的 success 回调之后才能发起广告请求（通过 [isReady] 判断）。
 * 建议在用户同意隐私协议后再调用 init，以满足合规要求。
 * useMediation 仅可设置一次，开启后即具备 GroMore 聚合能力。
 */
object GroMoreAdManager {

    @Volatile
    private var initialized = false

    /**
     * 初始化 GroMore SDK
     *
     * @param context 任意 Context，内部取 applicationContext
     * @param config 广告配置
     * @param onResult 初始化结果回调（success / failure），回调线程由 SDK 决定，
     *                 如需更新 UI 请自行切换到主线程
     */
    fun init(context: Context, config: AdConfig, onResult: ((Result<Unit>) -> Unit)? = null) {
        if (initialized) {
            // 单进程下多次初始化以首次配置为准，这里直接视为成功，避免重复 start
            onResult?.invoke(Result.success(Unit))
            return
        }
        if (config.appId.isBlank()) {
            onResult?.invoke(Result.failure(IllegalArgumentException("appId 不能为空，请在穿山甲 GroMore 后台申请")))
            return
        }
        // 初始化时把 AdConfig 配置的缓存取用策略注入为全局默认（show/attach 可按次覆盖）
        AdCacheManager.setDefaultStrategy(config.selectStrategy)
        val ttConfig = TTAdConfig.Builder()
            .appId(config.appId)
            .appName(config.appName)
            .useMediation(true) // 开启 GroMore 聚合能力，融合 SDK 必开且仅可设置一次
            .debug(config.debug)
            .allowShowNotify(true) // 允许通知栏展示下载进度，关闭存在合规风险
            .supportMultiProcess(false) // 本工程为单进程应用，保持 false
            .build()
        if (!TTAdSdk.init(context.applicationContext, ttConfig)) {
            onResult?.invoke(Result.failure(IllegalStateException("TTAdSdk.init 调用失败")))
            return
        }
        TTAdSdk.start(object : TTAdSdk.Callback {
            override fun success() {
                initialized = true
                Log.i(TAG, "GroMore SDK 初始化成功, version=${TTAdSdk.getAdManager().getSDKVersion()}")
                onResult?.invoke(Result.success(Unit))
            }

            override fun fail(code: Int, errorMsg: String?) {
                Log.w(TAG, "GroMore SDK 初始化失败: code=$code, msg=$errorMsg")
                onResult?.invoke(
                    Result.failure(IllegalStateException("GroMore 初始化失败: code=$code, msg=$errorMsg"))
                )
            }
        })
    }

    /** SDK 是否已初始化完成并可用，发起广告请求前的前置检查 */
    fun isReady(): Boolean = runCatching { TTAdSdk.isSdkReady() }.getOrDefault(false)

    /** 创建广告请求入口，仅内部使用；调用前需确保已初始化成功 */
    internal fun createAdNative(context: Context): TTAdNative =
        TTAdSdk.getAdManager().createAdNative(context.applicationContext)

    private const val TAG = "GroMoreAdManager"
}
