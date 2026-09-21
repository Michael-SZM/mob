---
kind: dependency_management
name: 基于 Gradle Version Catalog 的 Android 依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
---

## 1. 使用的系统与方案

该项目采用 **Gradle + Kotlin DSL** 构建，并通过 **Version Catalog（版本目录）** `gradle/libs.versions.toml` 统一管理所有第三方库与插件的版本。这是现代 Android 工程推荐的标准做法，将版本号集中声明、通过别名引用，避免在多个模块中散落硬编码版本。

仓库源配置集中在根级 `settings.gradle.kts` 的 `dependencyResolutionManagement` 块中，使用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 强制禁止子模块自行声明仓库，确保全仓仓库策略统一。

## 2. 关键文件

- `gradle/libs.versions.toml`：版本目录核心文件，定义 `[versions]`、`[libraries]`、`[plugins]` 三段。
- `settings.gradle.kts`：集中声明插件仓库（`pluginManagement.repositories`）与依赖仓库（`dependencyResolutionManagement.repositories`），并启用 FAIL_ON_PROJECT_REPOS 模式。
- `build.gradle.kts`（根）：仅通过 `alias(libs.plugins.*) apply false` 声明全局可用插件。
- `app/build.gradle.kts`：唯一业务模块，通过 `implementation(libs.xxx)` 引用版本目录中的库。

## 3. 架构与约定

### 版本集中化
所有依赖版本在 `libs.versions.toml` 的 `[versions]` 段以键值对声明（如 `agp = "8.13.0"`、`kotlin = "2.0.21"`、`coreKtx = "1.13.1"` 等），然后在 `[libraries]` 段通过 `version.ref = "xxx"` 引用，形成单一事实来源。新增库时只需在此处添加一行，其他模块无需改动。

### 插件版本集中化
Android Gradle Plugin (`com.android.application`) 与 Kotlin 插件 (`org.jetbrains.kotlin.android`) 同样通过 Version Catalog 的 `[plugins]` 段声明，并在根 build 文件中以 `apply false` 方式暴露给子模块，子模块通过 `plugins { alias(libs.plugins.android.application) }` 引用。

### 仓库源策略
- 插件仓库：`google()` → `mavenCentral()` → `gradlePluginPortal()`，按优先级顺序解析 AGP 和 Kotlin 插件。
- 依赖仓库：`google()` → `mavenCentral()` → 阿里云镜像 `https://maven.aliyun.com/repository/public`，为国内开发者提供加速。
- 通过 `includeGroupByRegex("com\.android.*"|"com\.google.*"|"androidx.*")` 限制 `pluginManagement` 中的 `google()` 仓库仅用于 Android/Google/AndroidX 相关插件，防止误用。

### 强制统一仓库
`repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` 会阻止任何子模块在自身 `build.gradle.kts` 中添加 `repositories {}` 块，违反此约定的构建会直接失败，从而保证全仓仓库来源一致、可审计。

### 依赖作用域
`app/build.gradle.kts` 中明确区分了不同作用域的依赖：
- `implementation`：运行时可见的库（core-ktx、appcompat、material）。
- `testImplementation` / `androidTestImplementation`：测试专用依赖（junit、espresso-core、androidx.junit）。

## 4. 约定与约束

- **版本必须通过 Version Catalog 声明**：所有依赖在 `app/build.gradle.kts` 中均以 `libs.xxx` 形式引用，未出现直接写死版本的 `implementation "group:artifact:version"` 写法。
- **禁止子模块自定义仓库**：`FAIL_ON_PROJECT_REPOS` 是硬性约束，子模块无法绕过根级仓库策略。
- **插件版本与依赖版本分离**：AGP、Kotlin 等构建期插件版本与运行时库版本分别维护在同一个 TOML 的不同段中，便于独立升级。
- **仓库顺序即优先级**：依赖解析按声明顺序查找，google() 优先于 mavenCentral()，再回退到阿里云镜像，这一顺序在 settings 中固定。
- **当前项目无私有仓库或 vendoring**：未发现 `.gitignore` 中排除 vendor 目录、也未见本地 Maven 仓库或私有 Nexus/Artifactory 配置；所有依赖均从远程仓库拉取。
- **无 lockfile 管理**：未见 `gradle.lockfile` 或 `gradle/resolution-cache` 锁定机制，依赖版本由 Version Catalog 控制但不会生成锁文件固化解析结果。