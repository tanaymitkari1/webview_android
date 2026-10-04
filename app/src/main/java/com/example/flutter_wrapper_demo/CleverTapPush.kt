package com.example.flutter_wrapper_demo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.clevertap.android.sdk.CleverTapAPI
import com.clevertap.android.sdk.pushnotification.CTPushNotificationListener

object CleverTapPush {

    const val DEFAULT_CHANNEL_ID = "clevertap_default"
    private const val DEFAULT_CHANNEL_NAME = "General"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DEFAULT_CHANNEL_ID,
            DEFAULT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun registerClickListener(
        context: Context,
        onClicked: (HashMap<String, Any>) -> Unit
    ) {
        val cleverTap = CleverTapAPI.getDefaultInstance(context) ?: return
        cleverTap.setCTPushNotificationListener(object : CTPushNotificationListener {
            override fun onNotificationClickedPayloadReceived(payload: HashMap<String, Any>?) {
                payload?.let(onClicked)
            }
        })
    }
}