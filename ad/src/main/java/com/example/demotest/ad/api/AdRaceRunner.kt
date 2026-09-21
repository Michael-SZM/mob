package com.example.demotest.ad.api

import java.util.concurrent.atomic.AtomicInteger

/**
 * 双管道赛跑编排（竞价 × 瀑布流）
 *
 * 一次加载请求同时启动两条管道，两者并行、互不阻塞：
 * 1. 竞价管道（[BiddingPipeline]）：并行向全部平台取价，得到 ecpm 最高的一条；
 * 2. 瀑布流管道（[WaterfallPipeline]）：按平台优先级顺序回退，得到首个成功的一条。
 * 两臂均结算后统一比价：ecpm 更高者为“竞得者”（同价时优先竞价臂）；
 * 竞得者由编排层写入缓存待展示，落败候选经 [AdRaceOutcome.releaseLosers] 释放。
 *
 * 两臂均无有效出价时回调合并错误（优先透出瀑布流最后一条原始错误，便于定位真实失败原因）。
 *
 * 实例为“一次 load 一个”：创建后调用一次 [run]，不要复用。
 *
 * @param platforms 本次参与赛跑的平台（已过滤：有广告位 ID 且已就绪）
 * @param load 向单个平台发起一次加载（由具体类型的 Loader 绑定到对应 loadXxx 调用）
 */
internal class AdRaceRunner<T : IAd>(
    private val platforms: List<AdPlatform>,
    private val load: (AdPlatform, onLoaded: (T) -> Unit, onError: (AdError) -> Unit) -> Unit,
) {

    private val lock = Any()

    /** 两条管道各结算一次，全部结算后统一比价 */
    private val pendingArms = AtomicInteger(ARMS)

    private var biddingBest: AdBid<T>? = null
    private var biddingAll: List<AdBid<T>> = emptyList()
    private var biddingError: AdError? = null
    private var waterfallBest: AdBid<T>? = null
    private var waterfallError: AdError? = null

    /**
     * 启动竞价与瀑布流两条管道并等待双方结算
     *
     * @param onSettled 两臂均结算且存在有效出价：回调竞得者与结算明细
     * @param onError 两臂均无有效出价：回调合并错误
     */
    fun run(
        onSettled: (winner: AdBid<T>, outcome: AdRaceOutcome<T>) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        if (platforms.isEmpty()) {
            onError(AdError(AdError.CODE_ALL_FAILED, "没有可参与请求的平台"))
            return
        }
        val bidding = BiddingPipeline(platforms, load) { best, all, lastError ->
            synchronized(lock) {
                biddingBest = best
                biddingAll = all
                biddingError = lastError
                settleIfDoneLocked(onSettled, onError)
            }
        }
        val waterfall = WaterfallPipeline(platforms, load) { best, lastError ->
            synchronized(lock) {
                waterfallBest = best
                waterfallError = lastError
                settleIfDoneLocked(onSettled, onError)
            }
        }
        AdLogger.i(TAG, "双管道赛跑启动: 平台=${platforms.joinToString { it.name }}（竞价与瀑布流同时执行）")
        bidding.start()
        waterfall.start()
    }

    /** 在锁内累计两臂结果，双方均结算后进行统一比价 */
    private fun settleIfDoneLocked(
        onSettled: (winner: AdBid<T>, outcome: AdRaceOutcome<T>) -> Unit,
        onError: (AdError) -> Unit,
    ) {
        if (pendingArms.decrementAndGet() != 0) {
            return
        }
        val winner = listOfNotNull(biddingBest, waterfallBest).maxByOrNull { it.ecpmForCompare }
        if (winner == null) {
            val error = waterfallError ?: biddingError
                ?: AdError(AdError.CODE_ALL_FAILED, "竞价与瀑布流均未取得有效广告")
            AdLogger.w(TAG, "双管道均未取得有效出价: $error")
            onError(error)
            return
        }
        AdLogger.i(
            TAG,
            "双管道比价结果: 竞价臂=${describe(biddingBest)}, 瀑布流臂=${describe(waterfallBest)} → " +
                "竞得者=渠道[${winner.channel.label}] platform=${winner.platform.name} " +
                "ecpm=${winner.ad.ecpm ?: "无"} 耗时=${winner.costMs}ms",
        )
        onSettled(
            winner,
            AdRaceOutcome(winner, biddingBest, waterfallBest, biddingAll + listOfNotNull(waterfallBest)),
        )
    }

    private fun describe(bid: AdBid<T>?): String =
        bid?.let { "${it.platform.name}(ecpm=${it.ad.ecpm ?: "无"})" } ?: "无有效出价"

    private companion object {
        private const val TAG = "AdRaceRunner"

        /** 管道数量：竞价 + 瀑布流 */
        private const val ARMS = 2
    }
}

/**
 * 双管道赛跑的结算明细，供编排层记录日志与释放落败候选
 *
 * @param winner 竞得者（两臂比价最高）
 * @param biddingBest 竞价臂最高出价，无成功为 null
 * @param waterfallBest 瀑布流臂首个成功出价，全失败为 null
 * @param allBids 本次全部有效出价（竞价臂全部 + 瀑布流臂成功者）
 */
internal class AdRaceOutcome<T : IAd>(
    val winner: AdBid<T>,
    val biddingBest: AdBid<T>?,
    val waterfallBest: AdBid<T>?,
    val allBids: List<AdBid<T>>,
) {

    /** 释放除竞得者外的落败候选广告对象，避免 SDK 资源滞留（竞得者交由缓存管理） */
    fun releaseLosers() {
        allBids.map { it.ad }.filter { it !== winner.ad }.forEach { ad ->
            AdLogger.d(TAG, "释放落败候选: ecpm=${ad.ecpm ?: "无"}")
            runCatching { ad.destroy() }
        }
    }

    private companion object {
        private const val TAG = "AdRaceRunner"
    }
}
