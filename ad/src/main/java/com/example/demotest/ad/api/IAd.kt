package com.example.demotest.ad.api

import android.app.Activity
import android.view.View
import android.view.ViewGroup

/**
 * 广告对象协议：各平台 SDK 广告对象的跨平台统一视图
 *
 * 由平台适配器（[AdPlatform]）在加载成功后包装产出，缓存、策略、编排层只面对本协议，
 * 不感知具体 SDK 类型；展示监听在展示/挂载时经 setEventListener 绑定，不随对象进入缓存
 */
interface IAd {

    /**
     * 本条广告的最优出价（单位：分），供 [AdSelectStrategy.HighestEcpm] 等策略比较使用；
     * 平台读取不到时为 null
     */
    val ecpm: Double?

    /** 释放 SDK 侧占用资源（如 Banner 的渲染视图）；缓存移除后由编排层调用 */
    fun destroy()
}

/** 激励视频广告协议 */
interface IRewardedAd : IAd {

    /** 绑定本次展示的事件回调（展示时调用，覆盖上一次绑定） */
    fun setEventListener(listener: RewardedAdEventListener)

    /** 全屏展示激励视频 */
    fun show(activity: Activity)
}

/** 插屏（全屏视频）广告协议 */
interface IInterstitialAd : IAd {

    /** 绑定本次展示的事件回调（展示时调用，覆盖上一次绑定） */
    fun setEventListener(listener: InterstitialAdEventListener)

    /** 全屏展示插屏广告 */
    fun show(activity: Activity)
}

/** 开屏广告协议 */
interface ISplashAd : IAd {

    /** 绑定本次展示的事件回调（展示时调用，覆盖上一次绑定） */
    fun setEventListener(listener: SplashAdEventListener)

    /** 将广告视图挂载到容器展示（是否置容器可见由编排层统一处理） */
    fun show(container: ViewGroup)
}

/** 模板 Banner 广告协议 */
interface IBannerAd : IAd {

    /** 绑定本次展示的事件回调（挂载时调用，覆盖上一次绑定） */
    fun setEventListener(listener: AdEventListener)

    /** 渲染完成的广告视图；渲染尚未完成时为 null（此时不可挂载） */
    val adView: View?
}
