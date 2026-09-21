package com.example.demotest.ad.api

import android.util.Log

/**
 * 广告 SDK 日志模块
 *
 * 统一收敛 :ad 模块的全部日志输出，记录关键链路信息方便调试：
 * 平台初始化结果、请求下发、各平台出价（ecpm）与耗时、竞价 × 瀑布流双管道比价结果、
 * 缓存读写与淘汰、自动重试、展示与补位等。
 *
 * 默认输出到 Logcat；业务方可替换 [printer] 把日志接入自己的日志系统（文件/上报等），
 * 或调整 [minLevel] 控制日志量：
 * ```
 * AdLogger.minLevel = AdLogger.Level.DEBUG                       // 查看更详细的链路日志
 * AdLogger.printer = AdLogger.Printer { level, tag, msg, t -> }   // 接入自定义日志通道
 * ```
 */
object AdLogger {

    /** 日志级别，数值越大越严重；低于 [minLevel] 的日志不会输出 */
    enum class Level(val value: Int, val label: String) {
        VERBOSE(0, "V"),
        DEBUG(1, "D"),
        INFO(2, "I"),
        WARN(3, "W"),
        ERROR(4, "E"),
        NONE(5, "N"),
    }

    /** 日志输出目标：默认 Logcat，可替换为业务自己的日志通道 */
    fun interface Printer {
        fun print(level: Level, tag: String, message: String, throwable: Throwable?)
    }

    /** 最低输出级别，默认 [Level.INFO]：关键流程与异常默认可见；竞价明细等高频日志看 DEBUG/VERBOSE */
    @Volatile
    var minLevel: Level = Level.INFO

    /** 日志输出目标，默认输出到 Logcat */
    @Volatile
    var printer: Printer = LogcatPrinter

    fun v(tag: String, message: String, throwable: Throwable? = null) =
        print(Level.VERBOSE, tag, message, throwable)

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        print(Level.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        print(Level.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        print(Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        print(Level.ERROR, tag, message, throwable)

    private fun print(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level.value < minLevel.value) {
            return
        }
        // 日志输出失败不影响广告主流程
        runCatching { printer.print(level, tag, message, throwable) }
    }

    /** 默认输出目标：Android Logcat */
    private object LogcatPrinter : Printer {
        override fun print(level: Level, tag: String, message: String, throwable: Throwable?) {
            when (level) {
                Level.VERBOSE -> Log.v(tag, message, throwable)
                Level.DEBUG -> Log.d(tag, message, throwable)
                Level.INFO -> Log.i(tag, message, throwable)
                Level.WARN -> Log.w(tag, message, throwable)
                Level.ERROR -> Log.e(tag, message, throwable)
                Level.NONE -> Unit
            }
        }
    }
}
