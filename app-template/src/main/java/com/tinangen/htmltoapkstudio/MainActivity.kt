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
import android.webkit.*
import android.net.Uri
import android.content.Intent
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permissionCallback: String? = null
    private var webPermissionRequest: PermissionRequest? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var pendingPermission: String? = null

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
            databaseEnabled = true
            geolocationEnabled = true
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
                    startActivityForResult(
                        params.createIntent(),
                        FILE_REQUEST
                    )
                    true
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    askPermission("location", null)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val resources = request.resources
                    val permissions = mutableListOf<String>()

                    if (resources.contains(
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        )
                    ) {
                        permissions.add(Manifest.permission.CAMERA)
                    }

                    if (resources.contains(
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        )
                    ) {
                        permissions.add(Manifest.permission.RECORD_AUDIO)
                    }

                    if (permissions.isEmpty()) {
                        request.deny()
                        return@runOnUiThread
                    }

                    val missing = permissions.filter {
                        checkSelfPermission(it) !=
                            PackageManager.PERMISSION_GRANTED
                    }

                    if (missing.isEmpty()) {
                        request.grant(resources)
                    } else {
                        webPermissionRequest = request
                        pendingPermission = "web"
                        requestPermissions(
                            missing.toTypedArray(),
                            PERMISSION_REQUEST
                        )
                    }
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun askPermission(
        name: String,
        callback: String?
    ) {
        val permission = when (name) {
            "camera" -> Manifest.permission.CAMERA
            "microphone" -> Manifest.permission.RECORD_AUDIO
            "location" -> Manifest.permission.ACCESS_FINE_LOCATION
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= 33)
                    Manifest.permission.POST_NOTIFICATIONS
                else null
            }
            else -> null
        }

        if (permission == null) {
            callback?.let { callJs(it, true) }
            return
        }

        if (checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            callback?.let { callJs(it, true) }
            return
        }

        permissionCallback = callback
        pendingPermission = name

        requestPermissions(
            arrayOf(permission),
            PERMISSION_REQUEST
        )
    }

    private inner class AndroidBridge {

        @JavascriptInterface
        fun requestPermission(name: String, callback: String) {
            runOnUiThread {
                askPermission(name, callback)
            }
        }

        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            runOnUiThread {

                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        "Сначала разрешите уведомления",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }

                val manager =
                    getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager

                val builder =
                    if (Build.VERSION.SDK_INT >= 26) {
                        Notification.Builder(
                            this@MainActivity,
                            CHANNEL_ID
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        Notification.Builder(this@MainActivity)
                    }

                val notification = builder
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title.take(100))
                    .setContentText(message.take(240))
                    .setStyle(
                        Notification.BigTextStyle()
                            .bigText(message.take(1000))
                    )
                    .setAutoCancel(true)
                    .build()

                manager.notify(
                    (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
                    notification
                )
            }
        }
    }

    private fun callJs(callback: String, granted: Boolean) {
        if (!callback.matches(
                Regex("[A-Za-z_$][A-Za-z0-9_$]*")
            )
        ) return

        webView.evaluateJavascript(
            "$callback($granted);",
            null
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != PERMISSION_REQUEST) return

        val granted = grantResults.isNotEmpty() &&
            grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }

        when (pendingPermission) {

            "web" -> {
                val request = webPermissionRequest
                if (granted && request != null) {
                    request.grant(request.resources)
                } else {
                    request?.deny()
                }
                webPermissionRequest = null
            }

            "location" -> {
                geoCallback?.invoke(
                    geoOrigin ?: "",
                    granted,
                    false
                )
                geoCallback = null
                geoOrigin = null
            }

            else -> {
                permissionCallback?.let {
                    callJs(it, granted)
                }
            }
        }

        permissionCallback = null
        pendingPermission = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Уведомления приложения",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            channel.description =
                "Обычные уведомления HTML-приложения"

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_REQUEST) {
            val result =
                if (resultCode == Activity.RESULT_OK && data != null) {
                    WebChromeClient.FileChooserParams.parseResult(
                        resultCode,
                        data
                    )
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
