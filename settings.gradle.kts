pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 穿山甲 GroMore 融合广告 SDK（ads-sdk-pro）专用仓库
        maven { url = uri("https://artifact.bytedance.com/repository/pangle/") }
    }
}

rootProject.name = "DemoTest"
include(":app")
include(":ad")
// GroMore 平台实现模块：业务按需引入（不引入则不打包穿山甲 SDK）
include(":ad-gromore")
 