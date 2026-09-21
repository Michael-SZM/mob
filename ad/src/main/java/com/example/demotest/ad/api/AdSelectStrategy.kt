package com.example.demotest.ad.api

/**
 * 广告选择策略
 *
 * 缓存中存在多条候选广告时，决定本次展示/挂载使用哪一条；
 * 内置 [AdSelectStrategy.Fifo]（先进先出，默认）与 [AdSelectStrategy.HighestEcpm]（出价最高）两个实现，
 * 业务方也可通过 lambda 或接口实现自定义策略，例如"取最新缓存的一条"：
 * ```
 * val newest = AdSelectStrategy { candidates -> candidates.lastOrNull() }
 * ```
 *
 * 与平台无关：候选为协议广告对象 [IAd]，出价 [AdCandidate.ecpm] 由各平台适配器包装时提供。
 * 生效路径：AdConfig.selectStrategy 配置全局默认（初始化时注入 [AdCacheManager]），
 * Loader 的 show/attach 传入 strategy 可按次覆盖。
 */
fun interface AdSelectStrategy {

    /**
     * 从候选中选出本次要使用的一条
     *
     * @param candidates 当前全部可用候选（已过滤过期条目），按入缓存先后排列，首个为最早入缓存
     * @return 选中的候选；返回 null 等效于本次无可用缓存
     */
    fun select(candidates: List<AdCandidate>): AdCandidate?

    companion object {

        /** 先进先出：选最早入缓存的候选（与缓存容量淘汰方向对称，默认策略） */
        val Fifo: AdSelectStrategy = AdSelectStrategy { candidates -> candidates.firstOrNull() }

        /** 出价最高：按 [AdCandidate.ecpm] 选最高者；读取不到出价（ecpm 为 null）的候选视为最低垫底 */
        val HighestEcpm: AdSelectStrategy = AdSelectStrategy { candidates ->
            candidates.maxByOrNull { it.ecpm ?: Double.NEGATIVE_INFINITY }
        }
    }
}

/**
 * 参与策略选择的候选广告
 *
 * 由缓存管理器在取用时生成（构造器 internal，策略实现只读不构造）
 *
 * @param ad 缓存的协议广告对象（[IAd] 子类型），各平台 SDK 广告对象经适配器包装后进入缓存
 * @param ecpm 入缓存时快照的最优出价（单位：分）；平台读取不到时为 null
 */
class AdCandidate internal constructor(
    val ad: IAd,
    val ecpm: Double?,
)
