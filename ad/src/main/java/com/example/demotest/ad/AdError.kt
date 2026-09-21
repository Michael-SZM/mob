package com.example.demotest.ad

/**
 * 统一广告错误模型
 *
 * 正数 code 为 GroMore/穿山甲 SDK 原始错误码；
 * 负数 code 为本模块前置校验产生的本地错误，未发起真实广告请求
 */
data class AdError(val code: Int, val message: String) {

    override fun toString(): String = "[code=$code] $message"

    companion object {
        /** GroMore SDK 尚未初始化成功（未调用或 start 未回调 success） */
        const val CODE_SDK_NOT_READY = -1

        /** 广告位 ID 为空 */
        const val CODE_EMPTY_AD_UNIT = -2

        /** 广告尚未加载完成就尝试展示 */
        const val CODE_AD_NOT_LOADED = -3
    }
}
