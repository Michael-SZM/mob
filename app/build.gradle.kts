plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.demotest"
    compileSdk {
        version = release(36)
    }

    // 使用本机已安装的 NDK 版本编译 Native 风控核心层
    ndkVersion = "23.1.7779620"

    defaultConfig {
        applicationId = "com.example.demotest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // 纯 C 实现，不依赖 STL
                arguments += listOf("-DANDROID_STL=none")
            }
        }

        ndk {
            // 全 ABI 覆盖，保证任意设备均可加载 Native 检测层
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // 关联 cpp 目录，编译生成 libsecuritydetect.so
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":ad"))
    // GroMore 平台实现按需引入：移除本行即不再打包穿山甲 SDK（so 体积），减小包体积；
    // 业务如接入其他平台，只需替换为对应平台模块
    implementation(project(":ad-gromore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}