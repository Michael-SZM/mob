package com.example.demotest.ad

/**
 * 通用广告加载结果回调，所有方法均提供默认空实现，业务方按需覆写
 *
 * "加载"这一次动作的结果通知，在 Loader.load 时传入；
 * 约定：收到 [onAdLoaded] 即表示新广告已就绪并写入缓存，可稍后调用 show/attach 展示
 */
interface AdLoadListener {

    /** 加载失败（本地前置校验失败或 SDK 请求失败；自动重试耗尽后才会回调） */
    fun onAdError(error: AdError) {}

    /** 广告加载成功（Banner 在模板渲染成功后才回调） */
    fun onAdLoaded() {}
}

/**
 * 通用广告展示事件回调，所有方法均提供默认空实现，业务方按需覆写
 *
 * 在展示时通过 Loader 的 show/attach 传入，属于"本次展示"的回调上下文；
 * 回调不随广告对象进入缓存，展示哪条广告就回调本次展示传入的 listener
 */
interface AdEventListener {

    /** 广告曝光展示 */
    fun onAdShown() {}

    /** 广告被点击 */
    fun onAdClicked() {}

    /** 广告关闭 */
    fun onAdClosed() {}
}

/** 激励视频事件回调：在通用事件基础上补充激励发放与视频播放完成 */
interface RewardedAdEventListener : AdEventListener {

    /**
     * 激励发放结果回调（onRewardArrived）
     *
     * @param isValid 奖励是否有效；仅当为 true 时业务方才应下发奖励
     * @param rewardName 奖励名称，可能为空
     * @param rewardAmount 奖励数量
     */
    fun onRewarded(isValid: Boolean, rewardName: String?, rewardAmount: Int) {}

    /** 视频播放完毕 */
    fun onVideoCompleted() {}
}

/** 插屏（全屏视频）事件回调 */
interface InterstitialAdEventListener : AdEventListener {

    /** 视频播放完毕 */
    fun onVideoCompleted() {}
}

/** 开屏广告事件回调，复用通用事件 */
interface SplashAdEventListener : AdEventListener
