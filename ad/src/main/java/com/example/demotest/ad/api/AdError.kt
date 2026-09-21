package com.example.demotest.ad.api

/**
 * 统一广告错误模型
 *
 * 真实广告请求失败时，code 为平台 SDK 原始错误码；
 * 负数 code 为本模块前置校验产生的本地错误，未发起真实广告请求
 */
data class AdError(val code: Int, val message: String) {

    override fun toString(): String = "[code=$code] $message"

    companion object {
        /** 平台 SDK 尚未初始化成功（未调用或初始化未回调成功） */
        const val CODE_SDK_NOT_READY = -1

        /** 广告位 ID 为空 */
        const val CODE_EMPTY_AD_UNIT = -2

        /** 广告尚未加载完成就尝试展示 */
        const val CODE_AD_NOT_LOADED = -3

        /** 竞价与瀑布流均未取得有效广告 */
        const val CODE_ALL_FAILED = -4

        /** 平台适配器调用抛出异常（非 SDK 正常失败路径） */
        const val CODE_REQUEST_EXCEPTION = -5
    }
}
