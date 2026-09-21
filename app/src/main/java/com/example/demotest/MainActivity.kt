package com.example.demotest

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.demotest.databinding.ActivityMainBinding
import com.example.demotest.security.NativeSecurityDetector
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 风控采集：App 启动即后台采集一次全量风险特征（含端口探测/遍历 /proc，须在后台线程）
        // 实际项目中此处将 report JSON 直接上报服务端，由服务端权重打分判定风险等级
        thread(name = "risk-collect-startup") {
            runCatching { NativeSecurityDetector.collectAllRiskData() }
                .onSuccess { Log.i(TAG, "启动风控采集上报数据: ${it.toJson()}") }
                .onFailure { Log.w(TAG, "启动风控采集失败", it) }
        }

        // 风控采集：关键业务前置手动触发演示
        binding.btnRiskDetect.setOnClickListener {
            it.isEnabled = false
            thread(name = "risk-collect-manual") {
                val resultText = runCatching {
                    val report = NativeSecurityDetector.collectAllRiskData()
                    val result = report.getDetectionResult()
                    buildString {
                        appendLine("本地检测结果：${result.riskLevel.label}")
                        appendLine("（Native 命中 ${result.nativeHitCount} 项 / 辅助命中 ${result.auxiliaryHitCount} 项）")
                        if (result.hitSummary.isNotEmpty()) {
                            appendLine()
                            result.hitSummary.forEach { appendLine("· $it") }
                        }
                        appendLine()
                        appendLine("待上报服务端完整 JSON：")
                        append(report.toJson())
                    }
                }.getOrElse { e -> "风控特征采集异常: ${e.message}" }
                Log.i(TAG, "手动风控检测: $resultText")
                runOnUiThread {
                    it.isEnabled = true
                    AlertDialog.Builder(this)
                        .setTitle("风控检测（仅采集不拦截）")
                        .setMessage(resultText)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }

        binding.btnFeedback.setOnClickListener {
            startActivity(Intent(this, FeedbackActivity::class.java))
        }

        binding.btnAdDemo.setOnClickListener {
            startActivity(Intent(this, AdDemoActivity::class.java))
        }
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
