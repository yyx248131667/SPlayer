package com.example.fnoswebviewapp

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val TAG = "FnosWebViewApp"

    private val prefsName = "fnos_webview_app"
    private val keyLoginUrl = "login_url"
    private val keyUiUrl = "ui_url"

    private lateinit var prefs: SharedPreferences

    private var loginUrl: String = ""
    private var uiUrl: String = ""

    private var attemptedLogin = false
    private var navigatedToUi = false

    private var pollingStartedAtMs: Long? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val maxRetries = 5

    // SPlayer Control API（更稳定，避免 DOM 结构变化导致按钮点击失效）
    private fun jsControlApi(path: String): String {
        return """
            (function(){
              try {
                fetch('$path', { method: 'GET', credentials: 'include' })
                  .then(function(r){ return r.text(); })
                  .then(function(t){ return true; })
                  .catch(function(e){ return false; });
                return true;
              } catch(e) { return false; }
            })();
        """.trimIndent()
    }

    private val jsApiNext = jsControlApi("/api/control/next")
    private val jsApiPrev = jsControlApi("/api/control/prev")
    private val jsApiToggle = jsControlApi("/api/control/toggle")

    // DOM 点击兜底（你提供的 HTML）
    private val jsClickPlayPause = """
        (function(){
          try {
            var btn = document.querySelector('button.play-pause');
            if (btn) { btn.click(); return true; }
            return false;
          } catch(e) { return false; }
        })();
    """.trimIndent()

    private val jsClickNext = """
        (function(){
          try {
            var el = document.querySelector('.play-control > .play-icon:nth-child(4)');
            if (el) { el.click(); return true; }
            return false;
          } catch(e) { return false; }
        })();
    """.trimIndent()

    private val jsClickPrev = """
        (function(){
          try {
            var el = document.querySelector('.play-control > .play-icon:nth-child(2)');
            if (el) { el.click(); return true; }
            return false;
          } catch(e) { return false; }
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(prefsName, MODE_PRIVATE)

        // 车机上建议用“菜单键”或外接键盘触发重置：KEYCODE_MENU
        // 另外也可以用 adb: am start -n ... 重新安装来清空配置。

        if (!loadUrlsFromPrefs()) {
            showUrlConfigDialog()
            return
        }

        initWebView()
        loadUrlWithNetworkCheck()
    }

    private fun loadUrlsFromPrefs(): Boolean {
        val l = prefs.getString(keyLoginUrl, null)?.trim()
        val u = prefs.getString(keyUiUrl, null)?.trim()
        if (l.isNullOrBlank() || u.isNullOrBlank()) return false
        loginUrl = l
        uiUrl = u
        return true
    }

    private fun showUrlConfigDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(pad, pad, pad, pad)

        val loginEt = EditText(this)
        loginEt.hint = "登录URL (例如 https://example.com/)"
        loginEt.inputType = InputType.TYPE_TEXT_VARIATION_URI
        loginEt.setText("https://")

        val uiEt = EditText(this)
        uiEt.hint = "UI URL (例如 https://example.com/#/)"
        uiEt.inputType = InputType.TYPE_TEXT_VARIATION_URI
        uiEt.setText("https://")

        layout.addView(loginEt)
        layout.addView(uiEt)

        AlertDialog.Builder(this)
            .setTitle("配置URL")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("保存") { _, _ ->
                val l = loginEt.text?.toString()?.trim().orEmpty()
                val u = uiEt.text?.toString()?.trim().orEmpty()

                if (!l.startsWith("http") || !u.startsWith("http")) {
                    Toast.makeText(this, "URL格式不正确", Toast.LENGTH_LONG).show()
                    showUrlConfigDialog()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putString(keyLoginUrl, l)
                    .putString(keyUiUrl, u)
                    .apply()

                loginUrl = l
                uiUrl = u

                initWebView()
                loadUrlWithNetworkCheck()
            }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        Log.d(TAG, "initWebView")
        WebView.setWebContentsDebuggingEnabled(true)

        // 让系统把媒体按键优先派发给当前活动（部分车机有效）
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audioManager.registerMediaButtonEventReceiver(componentName)
        } catch (_: Throwable) {
        }

        webView = WebView(this)

        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.setBackgroundColor(Color.parseColor("#1a1a2e"))
        webView.visibility = View.VISIBLE

        setContentView(webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.blockNetworkLoads = false
        settings.defaultTextEncodingName = "UTF-8"
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // WebView 74/车机兼容：桌面 UA
        settings.userAgentString =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    TAG,
                    "WebView Console: ${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"
                )
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished: $url")

                retryCount = 0

                view.post {
                    view.visibility = View.VISIBLE
                    view.invalidate()
                    view.requestLayout()
                }

                if (!attemptedLogin && url.startsWith(loginUrl)) {
                    attemptedLogin = true
                    startLoginCheckPolling(view)
                    return
                }

                if (attemptedLogin && !navigatedToUi) {
                    startLoginCheckPolling(view)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "onReceivedError: ${error?.errorCode} ${error?.description} url=${request.url}")

                    if (retryCount < maxRetries) {
                        retryCount++
                        Log.d(TAG, "Retrying... attempt $retryCount of $maxRetries")
                        mainHandler.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                view?.reload()
                            }
                        }, 2000L * retryCount)
                    } else {
                        Toast.makeText(this@MainActivity, "页面加载失败: ${error?.description}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "HTTP Error: ${errorResponse?.statusCode} for ${request.url}")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.e(TAG, "onReceivedSslError: ${error?.primaryError}")
                // 自用车机环境：忽略SSL错误
                handler?.proceed()
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadUrlWithNetworkCheck() {
        if (isNetworkAvailable()) {
            Log.d(TAG, "Network available, loading URL: $loginUrl")
            webView.loadUrl(loginUrl)
        } else {
            Log.w(TAG, "Network not available, retrying in 2 seconds...")
            Toast.makeText(this, "等待网络连接...", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    loadUrlWithNetworkCheck()
                }
            }, 2000)
        }
    }

    private fun startLoginCheckPolling(view: WebView) {
        if (navigatedToUi) return

        if (pollingStartedAtMs == null) {
            pollingStartedAtMs = System.currentTimeMillis()
        }

        val elapsed = System.currentTimeMillis() - (pollingStartedAtMs ?: 0L)
        if (elapsed > 120_000) {
            Log.w(TAG, "Login polling timeout (120s).")
            return
        }

        val detectLoggedInJs = """
            (function(){
              try {
                var hasDesktop = !!document.querySelector('#root .desktop');
                var href = (window && window.location && window.location.href) ? window.location.href : '';
                var title = (document && document.title) ? document.title : '';

                var looksLoggedIn = hasDesktop ||
                  (href.indexOf('/#/') >= 0 && href.indexOf('login') < 0) ||
                  (title.indexOf('桌面') >= 0 || title.indexOf('控制台') >= 0 || title.indexOf('Desktop') >= 0 || title.indexOf('Dashboard') >= 0);

                return looksLoggedIn;
              } catch(e) { return false; }
            })();
        """.trimIndent()

        view.evaluateJavascript(detectLoggedInJs) { result ->
            if (result == "true" && !navigatedToUi) {
                navigatedToUi = true
                Log.d(TAG, "Login success detected, loading uiUrl in same WebView.")
                CookieManager.getInstance().flush()
                view.loadUrl(uiUrl)
            } else {
                view.postDelayed({ startLoginCheckPolling(view) }, 500)
            }
        }
    }

    private fun tryControlFromSteering(primaryJs: String, fallbackJs: String, actionName: String) {
        if (!navigatedToUi) {
            Log.d(TAG, "Ignore media key ($actionName) because UI not opened yet")
            return
        }
        webView.post {
            webView.evaluateJavascript(primaryJs) { result ->
                Log.d(TAG, "Media key action=$actionName primaryResult=$result")
                if (result != "true") {
                    webView.evaluateJavascript(fallbackJs) { r2 ->
                        Log.d(TAG, "Media key action=$actionName fallbackResult=$r2")
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    tryControlFromSteering(jsApiNext, jsClickNext, "NEXT")
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    tryControlFromSteering(jsApiPrev, jsClickPrev, "PREVIOUS")
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    tryControlFromSteering(jsApiToggle, jsClickPlayPause, "PLAY_PAUSE")
                    return true
                }

                // 长按 MENU 键重置 URL 配置
                KeyEvent.KEYCODE_MENU -> {
                    prefs.edit().remove(keyLoginUrl).remove(keyUiUrl).apply()
                    Toast.makeText(this, "已清空URL配置，请重新设置", Toast.LENGTH_LONG).show()
                    showUrlConfigDialog()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
