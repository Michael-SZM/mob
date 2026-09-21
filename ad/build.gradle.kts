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
    // GroMore 融合 SDK：api 方式透出，宿主可直接访问 SDK 原生类型
    api(libs.pangle.ads.sdk)
    // 官方工程配置要求：SDK 下载库依赖项
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
}
