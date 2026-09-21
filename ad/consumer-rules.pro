# 穿山甲 GroMore 融合 SDK 混淆规则（随 :ad 模块消费，宿主无需重复配置）
-keep class com.bytedance.sdk.openadsdk.** { *; }
-keep class com.bytedance.mobsec.metasec.ml.** { *; }
-keep class com.bytedance.dr.** { *; }
-keep class com.bytedance.bdaudittaskv2.** { *; }
-keep class com.bytedance.pangle.** { *; }
-keep class com.pangle.** { *; }
-keep class com.pgl.** { *; }
-keep class com.bykv.vk.** { *; }

-dontwarn com.bytedance.**
-dontwarn com.pangle.**
-dontwarn com.pgl.**
