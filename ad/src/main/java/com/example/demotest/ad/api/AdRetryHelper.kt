package com.example.demotest.ad.api

import android.os.Handler
import android.os.Looper

/**
 * 广告加载失败自动重试编排
 *
 * 供各 Loader 内部使用：SDK 真实请求失败后按固定间隔自动重试，提升最终加载成功率。
 * 本地前置校验失败（SDK 未初始化、广告位 ID 为空等配置问题）重试无意义，
 * 由调用方直接回调业务失败，不走本类。
 *
 * 一次新的 load 调用应先 [reset]，避免新旧请求的重试任务相互串扰。
 */
internal class AdRetryHelper(
    private val maxRetryCount: Int = DEFAULT_MAX_RETRY_COUNT,
    private val retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    /**
     * 若仍有剩余重试次数，则在主线程延迟调度 [retry] 并返回 true；
     * 重试次数耗尽返回 false，调用方应直接回调业务失败
     */
    fun retryAfterDelay(retry: () -> Unit): Boolean {
        if (retryCount >= maxRetryCount) {
            return false
        }
        retryCount++
        AdLogger.i(TAG, "${retryIntervalMs}ms 后自动重试（第 $retryCount/$maxRetryCount 次）")
        handler.postDelayed(retry, retryIntervalMs)
        return true
    }

    /** 加载成功或发起新一次加载前调用：复位计数并移除尚未执行的重试任务 */
    fun reset() {
        retryCount = 0
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        private const val TAG = "AdRetryHelper"

        /** 默认最多自动重试 1 次：过多重试易触发 SDK 频控，反而降低成功率 */
        private const val DEFAULT_MAX_RETRY_COUNT = 1

        /** 默认重试间隔（毫秒）：给上游网络/填充恢复留出时间 */
        private const val DEFAULT_RETRY_INTERVAL_MS = 1500L
    }
}
