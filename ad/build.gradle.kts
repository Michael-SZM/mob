plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.demotest.ad"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24

        // 穿山甲 GroMore 融合 SDK 仅提供 arm64-v8a 架构 so 库，
        // 与宿主 app 的全 ABI 配置取并集打包，其余架构设备上广告能力不可用
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
    // GroMore 融合 SDK：以 implementation 隔离，宿主只能访问本模块的协议类型（api 包），
    // 无法直接依赖具体广告 SDK，从而支持横向替换/新增广告平台
    implementation(libs.pangle.ads.sdk)
    // 官方工程配置要求：SDK 下载库依赖项
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
}
