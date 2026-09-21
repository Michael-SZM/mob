package com.example.demotest.ad

/**
 * 广告类型：缓存管理与加载重试等横切能力的统一维度
 */
enum class AdType {

    /** 激励视频 */
    REWARDED,

    /** 插屏（全屏视频） */
    INTERSTITIAL,

    /** 开屏 */
    SPLASH,

    /** 模板 Banner */
    BANNER,
}
