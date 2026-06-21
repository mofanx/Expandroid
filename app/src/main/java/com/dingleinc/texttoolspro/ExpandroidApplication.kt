package com.dingleinc.texttoolspro

import android.app.Application
import com.dingleinc.texttoolspro.data.AppSettings

class ExpandroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
