# Android 客户端风控检测 · NDK 编译配置
# 产物：libsecuritydetect.so（Native 核心安全层，防 Java 层 Hook）
cmake_minimum_required(VERSION 3.22.1)
project(securitydetect LANGUAGES C)

add_library(securitydetect SHARED security_detect.c)

# 纯 C 实现，不依赖任何 STL
target_compile_options(securitydetect PRIVATE
    -DANDROID
    -fvisibility=hidden      # 隐藏非导出符号，减小静态逆向面（JNIEXPORT 不受影响）
    -ffunction-sections
    -fdata-sections
)

target_link_options(securitydetect PRIVATE
    -Wl,--gc-sections        # 裁剪未引用代码段
    -Wl,--exclude-libs,ALL   # 不重新导出静态库符号
)

# 适配 Android 15+（targetSdk 36）16KB 内存页对齐要求
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(securitydetect PRIVATE "-Wl,-z,max-page-size=16384")
endif()
