package com.example.demotest.ad.api

import android.os.SystemClock

/**
 * 广告缓存管理器
 *
 * 按 [AdType] 维度缓存“已加载成功、尚未展示”的协议广告对象（[IAd]，由各平台适配器包装产出），配合各 Loader 使用：
 * 加载成功后 [put] 入缓存，展示时 [take]/[peek] 取出，从而把"用户触发时现场加载"
 * 提前为"后台预加载、触发时直接命中"，提升广告加载（触达）成功率。
 *
 * 设计要点：
 * - 线程安全：广告回调线程不固定，所有读写均在全局锁内完成；
 * - 容量淘汰：每类型默认缓存 1 条，超出容量按 FIFO 淘汰最早条目，
 *   可用 [setMaxCacheSize] 扩容实现"预加载多条"（如激励视频常配 2~3 条）；
 * - 过期保护：广告对象加载后有存活时效（官方建议 30 分钟内展示完毕），
 *   [take]/[peek]/[size] 会顺带清理并忽略过期条目，避免展示失效广告；
 * - 取用策略：同类型缓存多条时，由 [AdSelectStrategy] 从全部未过期候选中选出要使用的一条；
 *   默认 [AdSelectStrategy.Fifo] 先进先出（与 [put] 满容量淘汰最旧条目对称，无条目饿死浪费），
 *   可在 AdConfig 中配置默认策略、show/attach 时按次覆盖或传入自定义策略，见 [setDefaultStrategy]。
 */
object AdCacheManager {

    /** 单条缓存条目：协议广告对象、过期时间戳与入缓存时快照的最优出价 */
    private class CacheEntry(val ad: IAd, val expireAtMillis: Long, val ecpm: Double?) {
        val isExpired: Boolean get() = SystemClock.elapsedRealtime() >= expireAtMillis
    }

    private val lock = Any()

    /** 各类型缓存队列：队头最早、队尾最新 */
    private val caches = mutableMapOf<AdType, ArrayDeque<CacheEntry>>()

    /** 各类型容量上限，未配置时取 [DEFAULT_MAX_CACHE_SIZE] */
    private val maxSizes = mutableMapOf<AdType, Int>()

    /** 各类型过期时长（毫秒），未配置时取 [DEFAULT_EXPIRE_MILLIS] */
    private val expireMillis = mutableMapOf<AdType, Long>()

    /** 全局默认的广告选择策略：show/attach 未按次覆盖时生效，默认先进先出 */
    private var defaultStrategy: AdSelectStrategy = AdSelectStrategy.Fifo

    /**
     * 缓存一条加载成功的广告对象，超出容量时淘汰最早缓存的条目
     */
    fun put(type: AdType, ad: IAd) {
        synchronized(lock) {
            val queue = caches.getOrPut(type) { ArrayDeque() }
            // 入缓存时快照出价，供“出价最高”等策略在取用时比较（平台读取不到为 null）
            queue.addLast(CacheEntry(ad, SystemClock.elapsedRealtime() + expireOf(type), ad.ecpm))
            while (queue.size > maxSizeOf(type)) {
                queue.removeFirst()
            }
            AdLogger.i(TAG, "广告入缓存: type=${type.name}, ecpm=${ad.ecpm ?: "无"}, 当前${queue.size}条")
        }
    }

    /**
     * 取出某类型缓存中被策略选中的一条有效广告（消耗缓存）
     *
     * 候选为该类型全部未过期条目，选择规则由 [strategy] 或全局默认策略决定
     *
     * @param strategy 本次取用的覆盖策略，null 表示使用全局默认策略（见 [setDefaultStrategy]）
     * @return null 表示无有效缓存（尚未 load、已被展示消耗或缓存过期）
     */
    fun <T : IAd> take(type: AdType, strategy: AdSelectStrategy? = null): T? {
        return synchronized(lock) {
            val queue = caches[type] ?: return@synchronized null
            val entry = selectEntryLocked(queue, type, strategy) ?: return@synchronized null
            queue.remove(entry)
            @Suppress("UNCHECKED_CAST")
            entry.ad as T
        }
    }

    /**
     * 查看某类型缓存中被策略选中的一条有效广告（不消耗缓存，与 [take] 选择规则一致），
     * 供 Banner 等页面级展示场景使用
     *
     * @param strategy 本次取用的覆盖策略，null 表示使用全局默认策略（见 [setDefaultStrategy]）
     * @return null 表示无有效缓存或渲染尚未完成
     */
    fun <T : IAd> peek(type: AdType, strategy: AdSelectStrategy? = null): T? {
        return synchronized(lock) {
            val queue = caches[type] ?: return@synchronized null
            val entry = selectEntryLocked(queue, type, strategy) ?: return@synchronized null
            @Suppress("UNCHECKED_CAST")
            entry.ad as T
        }
    }

    /**
     * 移除某类型全部缓存并返回其中未过期的广告对象，供业务方释放 SDK 资源
     */
    fun <T : IAd> remove(type: AdType): List<T> {
        return synchronized(lock) {
            val queue = caches.remove(type) ?: return@synchronized emptyList()
            queue.filterNot { it.isExpired }.map { entry ->
                @Suppress("UNCHECKED_CAST")
                entry.ad as T
            }
        }
    }

    /** 清空全部类型的缓存 */
    fun clear() {
        synchronized(lock) { caches.clear() }
        AdLogger.i(TAG, "广告缓存已全部清空")
    }

    /** 清理所有类型的过期条目 */
    fun cleanExpired() {
        synchronized(lock) {
            caches.values.forEach { queue -> queue.removeAll { it.isExpired } }
        }
    }

    /** 某类型当前有效缓存条数（顺带清理过期条目） */
    fun size(type: AdType): Int {
        return synchronized(lock) {
            val queue = caches[type] ?: return@synchronized 0
            queue.removeAll { it.isExpired }
            queue.size
        }
    }

    /**
     * 调整某类型容量上限并立即收缩多余条目；调大可支持预加载多条
     */
    fun setMaxCacheSize(type: AdType, maxSize: Int) {
        require(maxSize >= 1) { "maxSize 必须 >= 1" }
        synchronized(lock) {
            maxSizes[type] = maxSize
            caches[type]?.let { queue ->
                while (queue.size > maxSize) {
                    queue.removeFirst()
                }
            }
        }
    }

    /** 调整某类型过期时长（毫秒） */
    fun setExpireMillis(type: AdType, expireMillis: Long) {
        require(expireMillis > 0) { "expireMillis 必须 > 0" }
        synchronized(lock) { this.expireMillis[type] = expireMillis }
    }

    /**
     * 设置全局默认的广告选择策略
     *
     * 一般在初始化时按 AdConfig.selectStrategy 注入；show/attach 传入 strategy 可按次覆盖
     */
    fun setDefaultStrategy(strategy: AdSelectStrategy) {
        synchronized(lock) { defaultStrategy = strategy }
    }

    /**
     * 在锁内按策略从队列选出条目：先清理过期条目，再由策略从候选中选择
     *
     * @return null 表示无未过期候选或策略未选中任何候选
     */
    private fun selectEntryLocked(
        queue: ArrayDeque<CacheEntry>,
        type: AdType,
        strategy: AdSelectStrategy?,
    ): CacheEntry? {
        purgeExpiredLocked(queue, type)
        if (queue.isEmpty()) {
            return null
        }
        val effectiveStrategy = strategy ?: defaultStrategy
        val candidates = queue.map { AdCandidate(it.ad, it.ecpm) }
        val selected = effectiveStrategy.select(candidates)
        if (selected == null) {
            AdLogger.i(TAG, "策略未选中候选: type=${type.name}, 候选${candidates.size}条")
            return null
        }
        // 策略应返回候选之一（AdCandidate 构造器 internal，模块外无法伪造）；
        // 防御性处理：异常实现返回列表外对象时回退为最早一条
        val index = candidates.indexOfFirst { it === selected }
        if (index < 0) {
            AdLogger.w(TAG, "策略返回的候选不在当前候选中，回退为最早一条: type=${type.name}")
            return queue.first()
        }
        AdLogger.i(TAG, "策略选中缓存: type=${type.name}, 第${index + 1}/${candidates.size}条, ecpm=${queue[index].ecpm}")
        return queue[index]
    }

    /** 在锁内清理队列中的过期条目 */
    private fun purgeExpiredLocked(queue: ArrayDeque<CacheEntry>, type: AdType) {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isExpired) {
                iterator.remove()
                AdLogger.i(TAG, "丢弃过期缓存: type=${type.name}")
            }
        }
    }

    private fun maxSizeOf(type: AdType): Int = maxSizes[type] ?: DEFAULT_MAX_CACHE_SIZE

    private fun expireOf(type: AdType): Long = expireMillis[type] ?: DEFAULT_EXPIRE_MILLIS

    private const val TAG = "AdCacheManager"

    /** 每类型默认容量 1 条：重新加载即覆盖，需要预加载多条时通过 setMaxCacheSize 扩容 */
    private const val DEFAULT_MAX_CACHE_SIZE = 1

    /** 默认过期时长 30 分钟：穿山甲广告对象建议在加载后 30 分钟内展示完毕 */
    private const val DEFAULT_EXPIRE_MILLIS = 30 * 60 * 1000L
}
