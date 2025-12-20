package org.qxqx.qxdroid

import android.app.Application
import com.hoho.android.usbserial.BuildConfig
import timber.log.Timber

class QxDroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
