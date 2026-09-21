package com.example.demotest.ad.api

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 竞价模块（Bidding）
 *
 * 并行向本次全部目标平台发起广告请求：每个平台加载成功后回传的广告即视为一次“出价”
 * （出价 = [IAd.ecpm]，读取不到视为最低）；
 * 等全部平台结算后取 ecpm 最高的一条作为竞价臂结果（同价时优先平台注册顺序靠前者）。
 *
 * 只负责“并行发起 + 全部结算取最高价”，不关心缓存、展示与重试——由编排层 Loader 统一处理。
 * 与 [WaterfallPipeline] 由 [AdRaceRunner] 同时启动，两臂结果再统一比价择优。
 *
 * @param platforms 本次参与竞价的平台（已按业务配置过滤：有广告位 ID 且已就绪）
 * @param load 向单个平台发起一次加载（由具体类型的 Loader 绑定到对应 loadXxx 调用）
 * @param onSettled 全部平台结算后回调：最高出价（无成功为 null）、全部成功出价、最后一条错误
 */
internal class BiddingPipeline<T : IAd>(
    private val platforms: List<AdPlatform>,
    private val load: (AdPlatform, onLoaded: (T) -> Unit, onError: (AdError) -> Unit) -> Unit,
    private val onSettled: (best: AdBid<T>?, all: List<AdBid<T>>, lastError: AdError?) -> Unit,
) {

    fun start() {
        if (platforms.isEmpty()) {
            onSettled(null, emptyList(), null)
            return
        }
        val remaining = AtomicInteger(platforms.size)
        val bids = CopyOnWriteArrayList<AdBid<T>>()
        val lastErrorRef = AtomicReference<AdError?>(null)

        platforms.forEach { platform ->
            val startAt = SystemClock.elapsedRealtime()
            AdLogger.i(TAG, "竞价臂 → ${platform.name} 发起竞价请求")
            val onLoaded: (T) -> Unit = { ad ->
                val costMs = SystemClock.elapsedRealtime() - startAt
                bids.add(AdBid(AdBidChannel.BIDDING, platform, ad, costMs))
                AdLogger.i(TAG, "竞价臂 ← ${platform.name} 出价成功: ecpm=${ad.ecpm ?: "无"}, 耗时=${costMs}ms")
                if (remaining.decrementAndGet() == 0) {
                    settle(bids, lastErrorRef.get())
                }
            }
            val onError: (AdError) -> Unit = { error ->
                val costMs = SystemClock.elapsedRealtime() - startAt
                lastErrorRef.set(error)
                AdLogger.w(TAG, "竞价臂 ← ${platform.name} 出价失败: $error, 耗时=${costMs}ms")
                if (remaining.decrementAndGet() == 0) {
                    settle(bids, lastErrorRef.get())
                }
            }
            try {
                load(platform, onLoaded, onError)
            } catch (t: Throwable) {
                // 适配器实现异常不应拖垮整个竞价臂，按该平台失败处理
                AdLogger.e(TAG, "竞价臂 ${platform.name} 请求异常", t)
                lastErrorRef.set(AdError(AdError.CODE_REQUEST_EXCEPTION, "适配器调用异常: ${t.message}"))
                if (remaining.decrementAndGet() == 0) {
                    settle(bids, lastErrorRef.get())
                }
            }
        }
    }

    /** 全部平台结算后：汇总成功出价并取最高（无成功则为 null） */
    private fun settle(bids: List<AdBid<T>>, lastError: AdError?) {
        val all = bids.toList()
        val best = all.maxByOrNull { it.ecpmForCompare }
        val bestDesc = best?.let { ", 最高出价=${it.platform.name}(ecpm=${it.ad.ecpm ?: "无"})" } ?: ""
        AdLogger.i(TAG, "竞价臂结算: ${all.size}/${platforms.size} 家出价成功$bestDesc")
        onSettled(best, all, lastError)
    }

    private companion object {
        private const val TAG = "BiddingPipeline"
    }
}
