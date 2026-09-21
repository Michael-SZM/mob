package com.example.demotest.ad.api

/**
 * 各类型广告的平台无关加载参数
 *
 * 由编排层构造并透传给 [AdPlatform] 的 loadXxx 方法，平台适配器按需读取：
 * 平台不支持的参数会被忽略，不阻断加载流程；
 * 广告位 ID 统一使用 [adUnitIds]（键为平台实例、值为该平台自己的代码位 ID），
 * 未配置或配置为空串的平台不参与本次竞价与瀑布流。
 */

/**
 * 激励视频加载参数
 *
 * @param adUnitIds 各平台的广告位 ID（键为平台实例）：GroMore 传 GroMore 广告位 ID，其他平台传对应代码位 ID；
 *                  未配置或为空串的平台将被跳过
 * @param userId 服务端奖励验证场景下的用户唯一标识，会在奖励回调 URL 中透传，非必填
 * @param rewardName 奖励名称（如"金币"），配合穿山甲"广告中显示奖励内容"功能
 * @param rewardAmount 奖励数量
 */
data class RewardedAdRequest(
    val adUnitIds: Map<AdPlatform, String>,
    val userId: String? = null,
    val rewardName: String? = null,
    val rewardAmount: Int = 0,
)

/**
 * 插屏（全屏视频）加载参数
 *
 * @param adUnitIds 各平台的广告位 ID（键为平台实例）；未配置或为空串的平台将被跳过
 */
data class InterstitialAdRequest(
    val adUnitIds: Map<AdPlatform, String>,
)

/**
 * 开屏加载参数
 *
 * @param adUnitIds 各平台的广告位 ID（键为平台实例）；未配置或为空串的平台将被跳过
 * @param timeoutMs 加载超时时间（毫秒），超时走加载失败，业务方应直接进入主界面
 */
data class SplashAdRequest(
    val adUnitIds: Map<AdPlatform, String>,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        /** 开屏等待超时默认值：避免长时间阻塞启动流程 */
        const val DEFAULT_TIMEOUT_MS = 5000
    }
}

/**
 * 模板 Banner 加载参数
 *
 * @param adUnitIds 各平台的广告位 ID（键为平台实例）；未配置或为空串的平台将被跳过
 * @param widthDp 期望模板宽度（dp），须与平台后台创建代码位时选择的模板尺寸一致
 * @param heightDp 期望模板高度（dp），同上
 */
data class BannerAdRequest(
    val adUnitIds: Map<AdPlatform, String>,
    val widthDp: Float = DEFAULT_WIDTH_DP,
    val heightDp: Float = DEFAULT_HEIGHT_DP,
) {
    companion object {
        /** 穿山甲标准模板 Banner 尺寸（600x400 dp） */
        const val DEFAULT_WIDTH_DP = 600f
        const val DEFAULT_HEIGHT_DP = 400f
    }
}
