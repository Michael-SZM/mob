---
kind: configuration_system
name: 基于 Gradle + Android Resources 的轻量级配置体系
category: configuration_system
scope:
    - '**'
source_files:
    - gradle.properties
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
    - gradle/libs.versions.toml
    - app/src/main/AndroidManifest.xml
    - app/src/main/res/values/strings.xml
    - app/src/main/res/values/colors.xml
    - app/src/main/res/values/themes.xml
---

## 1. 使用的系统/方法

该仓库是一个极简的 Android 单模块工程，没有引入任何第三方配置框架（如 Config4K、Hikari、Spring Boot Config 等）。运行时配置完全依赖 Android 平台原生能力与 Gradle 构建期配置：
- **构建期配置**：Gradle Kotlin DSL（`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`）集中声明插件版本、仓库源、JVM 参数、AndroidX 开关等。
- **应用运行期配置**：通过 `app/src/main/res/values/*.xml` 资源文件（`strings.xml`、`colors.xml`、`themes.xml`）以及 `AndroidManifest.xml` 中的 `<application>` / `<activity>` 标签声明应用名称、主题、Activity 导出策略等。
- **版本与依赖管理**：使用 Gradle Version Catalog（`gradle/libs.versions.toml`）集中声明 AGP、Kotlin、依赖库的版本号，并通过 `alias(libs.plugins.*)` 在子模块引用。

## 2. 关键文件

| 文件 | 作用 |
|---|---|
| `gradle.properties` | 全局 Gradle/JVM 参数（`org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`）、`android.useAndroidX=true`、`kotlin.code.style=official`、`android.nonTransitiveRClass=true` |
| `settings.gradle.kts` | 定义 `pluginManagement` 与 `dependencyResolutionManagement`，强制 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，聚合 `:app` 模块，并添加阿里云 Maven 镜像 |
| `build.gradle.kts`（根） | 仅声明 `apply false` 的顶层插件别名 |
| `app/build.gradle.kts` | 模块级构建配置：`namespace`、`compileSdk { version = release(36) }`、`defaultConfig`（`minSdk=24, targetSdk=36, versionCode=1, versionName="1.0"`）、`buildTypes.release`、`compileOptions`/`kotlinOptions`（Java 11）、`viewBinding=true`、依赖声明 |
| `gradle/libs.versions.toml` | Version Catalog：`[versions]`、`[libraries]`、`[plugins]` 三段式集中管理所有依赖与插件版本 |
| `app/src/main/AndroidManifest.xml` | 应用清单：`<application android:label="@string/app_name">`、`android:theme="@style/Theme.DemoTest"`、`MainActivity` 作为 LAUNCHER、`FeedbackActivity` 非 exported |
| `app/src/main/res/values/strings.xml` | 字符串资源（仅 `app_name`） |
| `app/src/main/res/values/colors.xml` | 颜色资源（purple/teal/black/white） |
| `app/src/main/res/values/themes.xml` | 主题样式，继承 `Theme.MaterialComponents.DayNight.NoActionBar` |

## 3. 架构与约定

- **分层清晰**：构建期配置与运行期配置严格分离。Gradle 负责编译产物与依赖解析；Android Resources 负责 APK 打包进应用的静态配置。
- **集中化版本管理**：所有外部依赖版本号集中在 `gradle/libs.versions.toml`，子模块通过 `libs.xxx` 间接引用，避免硬编码版本号。
- **仓库源集中管控**：根 `settings.gradle.kts` 启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，禁止子模块自行声明仓库，统一由根工程指定 Google、Maven Central 及阿里云镜像。
- **资源命名空间隔离**：开启 `android.nonTransitiveRClass=true`，每个 library 的 R 类仅包含自身声明的资源，减小 R 类体积。
- **无运行时动态配置加载逻辑**：代码中未发现 `SharedPreferences`、`PreferenceManager`、`BuildConfig` 读取、`.env` 解析或自定义配置文件的加载实现；应用行为主要通过 Manifest 与资源文件声明。

## 4. 约定与约束

- **Gradle 版本与语言风格**：`gradle.properties` 显式设置 `kotlin.code.style=official` 与 JVM 堆大小 `-Xmx2048m`，为团队构建环境提供基线。
- **仓库访问白名单**：`dependencyResolutionManagement.repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` 强制所有仓库声明必须位于根 `settings.gradle.kts`，子模块不得私自添加仓库。
- **AndroidX 与 Kotlin 版本锁定**：通过 Version Catalog 的 `[versions]` 段统一管理 AGP (`8.13.0`)、Kotlin (`2.0.21`) 及所有依赖版本，升级时只需修改一处。
- **应用标识集中声明**：`applicationId`、`versionCode`、`versionName` 均在 `app/build.gradle.kts` 的 `defaultConfig` 中声明，未分散到资源或 Manifest。
- **资源引用规范**：Manifest 通过 `@string/app_name`、`@style/Theme.DemoTest` 引用资源，而非硬编码字符串或主题名。
- **无环境变量/密钥注入机制**：当前工程未使用 `.env`、`local.properties` 中的变量注入，也未见 `BuildConfig` 字段生成逻辑；如需接入 CI/CD 注入的配置，需额外扩展 `build.gradle.kts`。

总体而言，这是一个“最小可用”的 Android 工程，配置体系完全依托 Android/Gradle 原生能力，没有引入额外的配置抽象层或运行时配置中心。