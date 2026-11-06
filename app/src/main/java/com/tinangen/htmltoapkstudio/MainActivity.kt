package com.tinangen.htmltoapkstudio

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.net.Uri
import android.content.Intent
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionCallback: String? = null

    companion object {
        private const val FILE_REQUEST = 1001
        private const val PERMISSION_REQUEST = 1002
        private const val CHANNEL_ID = "html_app_notifications"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST)
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
        setContentView(webView)
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun requestPermission(name: String, callbackName: String) {
            runOnUiThread {
                val permission = when (name.lowercase()) {
                    "camera" -> Manifest.permission.CAMERA
                    "microphone", "audio" -> Manifest.permission.RECORD_AUDIO
                    "location" -> Manifest.permission.ACCESS_FINE_LOCATION
                    "notifications" -> {
                        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
                    }
                    else -> null
                }

                if (permission == null) {
                    callJs(callbackName, name == "notifications" || name == "location")
                    return@runOnUiThread
                }

                if (checkSelfPermission(permission) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    callJs(callbackName, true)
                    return@runOnUiThread
                }

                pendingPermissionCallback = callbackName
                requestPermissions(arrayOf(permission), PERMISSION_REQUEST)
            }
        }

        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        "Сначала разрешите уведомления",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = if (Build.VERSION.SDK_INT >= 26) {
                    Notification.Builder(this@MainActivity, CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(this@MainActivity)
                }
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title.take(100))
                    .setContentText(message.take(240))
                    .setStyle(Notification.BigTextStyle().bigText(message.take(1000)))
                    .setAutoCancel(true)
                    .build()

                manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
            }
        }
    }

    private fun callJs(callbackName: String, granted: Boolean) {
        // Accept only a simple JavaScript function name to avoid injecting arbitrary script.
        if (!callbackName.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) return
        webView.evaluateJavascript("$callbackName(${granted});", null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermissionCallback?.let { callJs(it, granted) }
            pendingPermissionCallback = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Уведомления приложения",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Обычные уведомления от HTML-приложения"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST) {
            val result = if (resultCode == Activity.RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
