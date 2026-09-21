package com.example.demotest

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.example.demotest.databinding.FragmentFeedbackBinding

class FeedbackFragment : Fragment() {

    private var _binding: FragmentFeedbackBinding? = null
    private val binding get() = _binding!!
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/feedback.html")
        }

        val rootView = requireActivity().window.decorView.findViewById<View>(android.R.id.content)
        var originalHeight = 0

        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.height
            if (originalHeight == 0) {
                originalHeight = screenHeight
            }
            // windowSoftInputMode=adjustResize 时，根布局高度差即为键盘高度
            val keyboardHeight = originalHeight - rect.bottom
            val isKeyboardVisible = keyboardHeight > originalHeight * 0.15

            injectKeyboardSpace(isKeyboardVisible)
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener!!)
    }

    /**
     * 不修改 H5 文件，通过运行时注入 CSS 为表单底部增加留白，
     * 避免 H5 中 position:fixed 的提交按钮挡住输入框。
     */
    private fun injectKeyboardSpace(show: Boolean) {
        val padding = if (show) "100px" else "0px"
        val script = """
            (function() {
                var style = document.getElementById('android-keyboard-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'android-keyboard-style';
                    document.head.appendChild(style);
                }
                style.textContent = '.form { padding-bottom: $padding !important; }';
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
    }

    override fun onDestroyView() {
        globalLayoutListener?.let {
            requireActivity().window.decorView.findViewById<View>(android.R.id.content)
                ?.viewTreeObserver?.removeOnGlobalLayoutListener(it)
        }
        globalLayoutListener = null
        super.onDestroyView()
        _binding = null
    }
}
