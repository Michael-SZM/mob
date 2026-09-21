package com.example.demotest.ad.api

import android.os.SystemClock

/**
 * 瀑布流模块（Waterfall）
 *
 * 按平台优先级（[platforms] 注册顺序，越靠前越先请求）依次发起广告请求：
 * 上一梯队失败才请求下一梯队，首个成功即止并以其出价作为瀑布流臂结果；全部失败则结果为 null。
 *
 * 与竞价臂（[BiddingPipeline]）由 [AdRaceRunner] 同时启动：
 * 瀑布流保证“确定性回退”（总有平台按序兜底），竞价保证“取最高价”，两臂结果统一比价择优。
 *
 * 只负责“顺序回退 + 首个成功”，不关心缓存、展示与重试——由编排层 Loader 统一处理。
 *
 * @param platforms 本次参与瀑布流的平台（已按业务配置过滤：有广告位 ID 且已就绪），顺序即优先级
 * @param load 向单个平台发起一次加载（由具体类型的 Loader 绑定到对应 loadXxx 调用）
 * @param onSettled 结算回调：首个成功出价（全失败为 null）、最后一条错误
 */
internal class WaterfallPipeline<T : IAd>(
    private val platforms: List<AdPlatform>,
    private val load: (AdPlatform, onLoaded: (T) -> Unit, onError: (AdError) -> Unit) -> Unit,
    private val onSettled: (best: AdBid<T>?, lastError: AdError?) -> Unit,
) {

    fun start() {
        if (platforms.isEmpty()) {
            onSettled(null, null)
            return
        }
        attempt(0)
    }

    /** 请求第 [index] 梯队平台；失败则回退到下一梯队 */
    private fun attempt(index: Int) {
        val platform = platforms[index]
        val startAt = SystemClock.elapsedRealtime()
        AdLogger.i(TAG, "瀑布流臂 → ${platform.name} 发起请求（第 ${index + 1}/${platforms.size} 梯队）")
        try {
            load(
                platform,
                { ad ->
                    val costMs = SystemClock.elapsedRealtime() - startAt
                    AdLogger.i(
                        TAG,
                        "瀑布流臂 ← ${platform.name} 出价成功: ecpm=${ad.ecpm ?: "无"}, 耗时=${costMs}ms（首个成功即止）",
                    )
                    onSettled(AdBid(AdBidChannel.WATERFALL, platform, ad, costMs), null)
                },
                { error ->
                    val costMs = SystemClock.elapsedRealtime() - startAt
                    AdLogger.w(TAG, "瀑布流臂 ← ${platform.name} 出价失败: $error, 耗时=${costMs}ms")
                    fallback(index, error)
                },
            )
        } catch (t: Throwable) {
            // 适配器实现异常不应中断瀑布流，按该梯队失败继续回退
            AdLogger.e(TAG, "瀑布流臂 ${platform.name} 请求异常", t)
            fallback(index, AdError(AdError.CODE_REQUEST_EXCEPTION, "适配器调用异常: ${t.message}"))
        }
    }

    /** 当前梯队失败：还有下一梯队则继续，否则全部失败收尾 */
    private fun fallback(index: Int, lastError: AdError) {
        if (index + 1 < platforms.size) {
            attempt(index + 1)
        } else {
            AdLogger.w(TAG, "瀑布流臂全部梯队失败（共 ${platforms.size} 个）")
            onSettled(null, lastError)
        }
    }

    private companion object {
        private const val TAG = "WaterfallPipeline"
    }
}
