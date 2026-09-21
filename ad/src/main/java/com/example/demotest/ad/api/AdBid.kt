package com.example.demotest.ad.api

/** 出价来源渠道：竞价臂（并行取最高价）或瀑布流臂（顺序回退取首个成功） */
internal enum class AdBidChannel(val label: String) {
    BIDDING("竞价"),
    WATERFALL("瀑布流"),
}

/**
 * 一次有效“出价”：某平台加载成功后回传的广告对象及其出价信息
 *
 * @param channel 出价来自竞价臂还是瀑布流臂
 * @param platform 产出该广告的平台适配器
 * @param ad 平台包装后的协议广告对象
 * @param costMs 该平台本次请求耗时（毫秒），供日志定位慢平台
 */
internal class AdBid<T : IAd>(
    val channel: AdBidChannel,
    val platform: AdPlatform,
    val ad: T,
    val costMs: Long,
) {

    /** 比价用出价：读取不到出价（ecpm 为 null）视为最低，与 [AdSelectStrategy.HighestEcpm] 语义一致 */
    val ecpmForCompare: Double get() = ad.ecpm ?: Double.NEGATIVE_INFINITY
}
