---
kind: frontend_style
name: Android 原生主题与内嵌 WebView 页面样式体系
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/res/values/themes.xml
    - app/src/main/res/values-night/themes.xml
    - app/src/main/res/values/colors.xml
    - app/src/main/res/layout/activity_main.xml
    - app/src/main/res/layout/activity_feedback.xml
    - app/src/main/res/layout/fragment_feedback.xml
    - app/src/main/assets/feedback.html
---

## 1. 使用的系统与方法

本仓库是一个 Android 应用，前端样式由两部分组成：
- **原生 UI**：基于 Material Components（`Theme.MaterialComponents.DayNight.NoActionBar` / `DarkActionBar`），通过 Android XML `res/values/themes.xml`、`colors.xml` 等资源文件声明主题与颜色。
- **WebView 内嵌页面**：通过 `app/src/main/assets/feedback.html` 以单文件 HTML + 内联 `<style>` + 内联 `<script>` 的方式实现意见反馈页，使用原生 CSS（无外部框架）完成布局与交互。

没有发现任何第三方前端样式方案（如 Tailwind、CSS Modules、Styled Components、Jetpack Compose Theme 等），也没有独立的 `.css` 或 `.scss` 文件。样式完全集中在 Android `res` 资源目录和单个 HTML 文件中。

## 2. 关键文件

- `app/src/main/res/values/themes.xml`：日间模式应用主题，继承自 `Theme.MaterialComponents.DayNight.NoActionBar`，定义 primary / secondary / status bar 等属性。
- `app/src/main/res/values-night/themes.xml`：夜间模式主题，继承自 `Theme.MaterialComponents.DayNight.DarkActionBar`，覆盖 primary / secondary 色值。
- `app/src/main/res/values/colors.xml`：设计色板，集中声明 `purple_200/500/700`、`teal_200/700`、`black`、`white` 等颜色资源。
- `app/src/main/res/layout/activity_main.xml`、`activity_feedback.xml`、`fragment_feedback.xml`：ViewBinding 的布局文件，承载原生 View 层级。
- `app/src/main/assets/feedback.html`：内嵌于 WebView 的反馈页面，包含完整 HTML/CSS/JS，负责表单、上传占位、底部提交按钮及输入校验逻辑。

## 3. 架构与约定

### 3.1 原生主题体系（Material Design）
- 采用 **Day/Night 双主题**：`values/themes.xml` 对应日间，`values-night/themes.xml` 对应夜间，通过 `Theme.DemoTest` 统一入口。
- 颜色通过 `@color/purple_500`、`@color/teal_200` 等资源引用，而非硬编码十六进制值，便于后续替换。
- 状态栏颜色通过 `android:statusBarColor` 指向 `?attr/colorPrimaryVariant`，遵循 Material 规范。
- 未引入自定义 style 子类，仅对 Material 默认主题做最小覆盖。

### 3.2 WebView 内嵌页面样式
- 所有样式写在 `<style>` 中，无外部 CSS 依赖；字体栈为 `-apple-system, BlinkMacFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`，兼顾 iOS/Android/Web 一致性。
- 使用 BEM 风格的类名命名（`.header`、`.form`、`.card`、`.form-row`、`.label`、`.value`、`.textarea-box`、`.upload-grid`、`.input-box`、`.footer`、`.submit-btn`），结构清晰且可维护。
- 视觉风格偏向移动端 H5：圆角卡片（`border-radius: 12px`）、浅灰背景（`#F7F8FA`）、强调色 `#FF5A6E` 及其渐变（`linear-gradient(90deg, #FF7A8C 0%, #FF5A6E 100%)`）用于提交按钮。
- 响应式策略：通过 `viewport` 设置 `width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no`，并使用 flexbox + `flex-wrap` 实现自适应布局；底部固定区域使用 `env(safe-area-inset-bottom)` 适配刘海屏。
- 交互行为全部用原生 JavaScript 实现（字符计数、聚焦滚动、表单校验），无 jQuery 或其他库。

### 3.3 布局方式
- 原生界面使用 Android XML Layout + ViewBinding（从包名 `com.example.demotest` 下的 Activity/Fragment 推断）。
- 反馈页通过 `WebView` 加载本地 `assets/feedback.html`，将业务表单与原生界面解耦。

## 4. 约定与约束

- **主题必须通过 `Theme.DemoTest` 引用**：两个 `themes.xml` 均定义同名 style，确保 Day/Night 切换时主题一致。
- **颜色不得在布局中硬编码**：主题中统一使用 `@color/*` 引用，避免散落十六进制色值。
- **WebView 页面保持单文件内联**：当前反馈页将所有 CSS/JS 内联到 HTML，不拆分文件，便于通过 assets 直接加载。
- **移动端优先的 CSS 约定**：禁用缩放（`user-scalable=no`）、移除点击高亮（`-webkit-tap-highlight-color: transparent`）、使用 `box-sizing: border-box` 重置全局盒模型。
- **无障碍与可读性**：必填项通过 `.label.required::before` 动态插入 `*` 标记，提示色为强调红 `#FF5A6E`。

## 5. 未发现的部分

- 没有 Jetpack Compose 相关主题代码。
- 没有统一的 design token 系统（如 `dimens.xml`、`strings.xml` 外的独立 token 层）。
- 没有 CSS 预处理器、构建期样式处理或组件库集成。
- 没有多语言/多地区样式变体，仅支持 Day/Night 两套主题。

总体而言，该仓库的前端样式是典型的“Android 原生 Material 主题 + 单文件内嵌 WebView H5”组合，轻量、直观，适合小型 Demo 项目。