plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.demotest.ad.gromore"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24

        // 穿山甲 GroMore 融合 SDK 仅提供 arm64-v8a 架构 so 库，
        // 与宿主 app 的全 ABI 配置取并集打包，其余架构设备上广告能力不可用；
        // SDK 混淆规则随本模块消费（consumer-rules），宿主无需重复配置
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // 协议层：平台适配器依托 :ad 的 AdPlatform/IAd 协议实现，api 方式透出便于业务同一处声明
    api(project(":ad"))
    // GroMore 融合 SDK：以 implementation 隔离在本模块内部，不向业务与 :ad 透出；
    // 业务不引入本模块即不打包 SDK（so 体积），实现按需选平台、减小包体积
    implementation(libs.pangle.ads.sdk)
    // 官方工程配置要求：SDK 下载库依赖项
    implementation(libs.okhttp)
}
