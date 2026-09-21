---
kind: build_system
name: 基于 Gradle 8.13 + Kotlin DSL 的 Android 单模块构建系统
category: build_system
scope:
    - '**'
source_files:
    - settings.gradle.kts
    - build.gradle.kts
    - gradle/libs.versions.toml
    - app/build.gradle.kts
    - gradle.properties
    - gradle/wrapper/gradle-wrapper.properties
---

## 1. 使用的系统与工具

本项目采用 **Gradle 8.13**（通过 Wrapper 固定版本，下载地址经腾讯云镜像加速）配合 **Kotlin DSL**（`*.gradle.kts`）进行构建。Android 插件版本为 AGP 8.13.0，Kotlin 编译器版本 2.0.21。项目为单模块结构（仅 `:app`），无 CI、Dockerfile 或自定义发布脚本。

## 2. 关键文件与职责

- `settings.gradle.kts`：声明根工程名 `DemoTest`，通过 `include(":app")` 聚合唯一子模块；集中配置 `pluginManagement` 与 `dependencyResolutionManagement`，强制使用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行声明仓库，统一从 Google、Maven Central 及阿里云公共镜像拉取依赖。
- `gradle/libs.versions.toml`：采用 **Version Catalog** 统一管理所有版本与依赖别名，定义 `agp`、`kotlin`、`coreKtx`、`junit`、`espressoCore`、`appcompat`、`material` 等版本，并在 `[plugins]` 中暴露 `android-application` 与 `kotlin-android` 两个插件别名。
- `build.gradle.kts`（根）：仅通过 `alias(libs.plugins.*) apply false` 声明全局可用但延迟应用的插件，不引入任何业务逻辑。
- `app/build.gradle.kts`：应用模块的实际构建配置，包含 `namespace`、`compileSdk/targetSdk/minSdk`、`applicationId`、`versionCode/versionName`、`buildTypes.release`（启用 ProGuard）、`compileOptions`/`kotlinOptions`（JVM 11）、`buildFeatures.viewBinding = true` 以及依赖声明。
- `gradle.properties`：全局 Gradle 属性，设置 JVM 堆 `-Xmx2048m`、UTF-8 编码、启用 AndroidX、官方 Kotlin 代码风格、`android.nonTransitiveRClass=true` 以缩小 R 类体积。
- `gradle/wrapper/gradle-wrapper.properties`：锁定 Gradle 8.13，下载源切换至腾讯云镜像，启用 `validateDistributionUrl=true` 校验分发完整性。

## 3. 架构与约定

- **单一模块**：工程仅包含一个 `:app` 模块，无 library 模块拆分，所有源码、资源、测试均位于 `app/src/{main,test,androidTest}`。
- **版本集中化**：所有第三方库与插件版本集中在 `libs.versions.toml`，模块内通过 `alias(libs.plugins.* )` 和 `libs.*` 引用，避免硬编码版本号。
- **仓库白名单**：通过 `dependencyResolutionManagement.repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` 强制所有依赖来源必须经过根级仓库列表，子模块不得私自添加 Maven 源。
- **构建产物**：APK 由 Gradle 标准任务生成（如 `assembleRelease`），ProGuard 规则位于 `app/proguard-rules.pro`，release 构建默认关闭混淆（`isMinifyEnabled = false`）但仍加载优化模板与自定义规则。
- **编译目标**：Java/Kotlin 统一以 `VERSION_11` / `jvmTarget="11"` 为目标，确保字节码兼容性。

## 4. 约定与约束

- **命名空间**：每个 Android 模块必须在 `android { namespace = "..." }` 中声明包名（当前为 `com.example.demotest`），替代传统的 `package` 声明。
- **SDK 策略**：`minSdk = 24`、`targetSdk = 36`、`compileSdk` 通过 `release(36)` 动态解析，三者保持对齐。
- **测试框架**：单元测试使用 JUnit 4 (`testImplementation`)，仪器测试使用 `androidx.test.runner.AndroidJUnitRunner` 并依赖 `androidx.junit` 与 `espresso-core`。
- **资源绑定**：启用 `viewBinding = true`，禁止在 XML 中使用 `findViewById`，应通过生成的 Binding 类访问视图。
- **仓库访问**：生产环境依赖通过 Google/Maven Central/阿里云三源获取，CI 或离线场景需保证这些镜像可达。
- **无外部发布流程**：仓库未包含 CI 流水线、签名配置、自动化打包脚本或 Docker 构建，发布需手动执行 Gradle 任务或在 IDE 中导出 APK。