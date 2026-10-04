package com.example.flutter_wrapper_demo

import com.clevertap.android.sdk.CleverTapAPI
import com.clevertap.android.sdk.CleverTapAPI.LogLevel
import com.clevertap.android.sdk.Application as CleverTapApplication

class DemoApplication : CleverTapApplication() {

    override fun onCreate() {
        CleverTapAPI.setDebugLevel(
            if (BuildConfig.DEBUG) LogLevel.VERBOSE else LogLevel.INFO
        )
        super.onCreate()
    }
}