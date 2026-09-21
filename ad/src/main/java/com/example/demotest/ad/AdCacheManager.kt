package com.example.demotest.ad

import android.os.SystemClock
import android.util.Log

/**
 * 广告缓存管理器
 *
 * 按 [AdType] 维度缓存"已加载成功、尚未展示"的广告对象，配合各 Loader 使用：
 * 加载成功后 [put] 入缓存，展示时 [take]/[peek] 取出，从而把"用户触发时现场加载"
 * 提前为"后台预加载、触发时直接命中"，提升广告加载（触达）成功率。
 *
 * 设计要点：
 * - 线程安全：广告回调线程不固定，所有读写均在全局锁内完成；
 * - 容量淘汰：每类型默认缓存 1 条，超出容量按 FIFO 淘汰最早条目，
 *   可用 [setMaxCacheSize] 扩容实现"预加载多条"（如激励视频常配 2~3 条）；
 * - 过期保护：广告对象加载后有存活时效（官方建议 30 分钟内展示完毕），
 *   [take]/[peek]/[size] 会顺带清理并忽略过期条目，避免展示失效广告；
 * - FIFO 取用：同类型缓存多条时优先返回最早缓存的广告（也最先过期），
 *   与 [put] 满容量时淘汰最旧条目对称，保证缓存池滚动消耗、无条目饿死浪费。
 */
object AdCacheManager {

    /** 单条缓存条目：广告对象与过期时间戳 */
    private class CacheEntry(val ad: Any, val expireAtMillis: Long) {
        val isExpired: Boolean get() = SystemClock.elapsedRealtime() >= expireAtMillis
    }

    private val lock = Any()

    /** 各类型缓存队列：队头最早、队尾最新 */
    private val caches = mutableMapOf<AdType, ArrayDeque<CacheEntry>>()

    /** 各类型容量上限，未配置时取 [DEFAULT_MAX_CACHE_SIZE] */
    private val maxSizes = mutableMapOf<AdType, Int>()

    /** 各类型过期时长（毫秒），未配置时取 [DEFAULT_EXPIRE_MILLIS] */
    private val expireMillis = mutableMapOf<AdType, Long>()

    /**
     * 缓存一条加载成功的广告对象，超出容量时淘汰最早缓存的条目
     */
    fun put(type: AdType, ad: Any) {
        synchronized(lock) {
            val queue = caches.getOrPut(type) { ArrayDeque() }
            queue.addLast(CacheEntry(ad, SystemClock.elapsedRealtime() + expireOf(type)))
            while (queue.size > maxSizeOf(type)) {
                queue.removeFirst()
            }
            Log.i(TAG, "广告入缓存: type=${type.name}, 当前${queue.size}条")
        }
    }

    /**
     * 取出某类型最早缓存的一条有效广告（消耗缓存，FIFO）
     *
     * @return null 表示无有效缓存（尚未 load、已被展示消耗或缓存过期）
     */
    fun <T : Any> take(type: AdType): T? {
        return synchronized(lock) {
            val queue = caches[type] ?: return@synchronized null
            // 从队头（最早缓存、最先过期）开始取用，顺带清理过期条目
            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                if (!entry.isExpired) {
                    @Suppress("UNCHECKED_CAST")
                    return@synchronized entry.ad as T
                }
                Log.i(TAG, "丢弃过期缓存: type=${type.name}")
            }
            null
        }
    }

    /**
     * 查看某类型最早缓存的一条有效广告（不消耗缓存，与 [take] 取用顺序一致），
     * 供 Banner 等页面级展示场景使用
     *
     * @return null 表示无有效缓存或渲染尚未完成
     */
    fun <T : Any> peek(type: AdType): T? {
        return synchronized(lock) {
            val queue = caches[type] ?: return@synchronized null
            while (queue.isNotEmpty()) {
                val entry = queue.first()
                if (!entry.isExpired) {
                    @Suppress("UNCHECKED_CAST")
                    return@synchronized entry.ad as T
                }
                queue.removeFirst()
                Log.i(TAG, "丢弃过期缓存: type=${type.name}")
            }
            null
        }
    }

    /**
     * 移除某类型全部缓存并返回其中未过期的广告对象，供业务方释放 SDK 资源
     */
    fun <T : Any> remove(type: AdType): List<T> {
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
        Log.i(TAG, "广告缓存已全部清空")
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

    private fun maxSizeOf(type: AdType): Int = maxSizes[type] ?: DEFAULT_MAX_CACHE_SIZE

    private fun expireOf(type: AdType): Long = expireMillis[type] ?: DEFAULT_EXPIRE_MILLIS

    private const val TAG = "AdCacheManager"

    /** 每类型默认容量 1 条：重新加载即覆盖，需要预加载多条时通过 setMaxCacheSize 扩容 */
    private const val DEFAULT_MAX_CACHE_SIZE = 1

    /** 默认过期时长 30 分钟：穿山甲广告对象建议在加载后 30 分钟内展示完毕 */
    private const val DEFAULT_EXPIRE_MILLIS = 30 * 60 * 1000L
}
