package com.etmw.foldpc

import android.app.Application
import android.util.Log
import com.etmw.foldpc.adblock.AdBlockManager

class EtmwApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsRepository(this)
        try {
            AdBlockManager.initialize(this, settings.adBlockEnabled)
        } catch (t: Throwable) {
            Log.e("EtmwApp", "Ad blocking could not be initialized", t)
        }
    }
}
