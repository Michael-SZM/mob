package com.example.demotest.ad.api

/**
 * 广告通用配置（与平台无关）
 *
 * @param platforms 注册的平台适配器列表（可同时接入多个平台）：初始化时全部拉起；
 *                  列表顺序即瀑布流优先级（越靠前越先请求）；
 *                  每次加载请求会同时启动竞价与瀑布流两条管道、覆盖全部平台。
 *                  平台自身参数（如 GroMore 的 appId/appName）在构造平台实例时传入
 * @param selectStrategy 缓存取用策略：同类型存在多条候选广告时选中哪条展示（默认先进先出）；
 *                       初始化时注入为全局默认，可在 show/attach 时按次覆盖或传入自定义策略
 */
data class AdConfig(
    val platforms: List<AdPlatform>,
    val selectStrategy: AdSelectStrategy = AdSelectStrategy.Fifo,
)
