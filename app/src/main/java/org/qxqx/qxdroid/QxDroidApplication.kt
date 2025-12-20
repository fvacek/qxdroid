package org.qxqx.qxdroid

import android.app.Application
import timber.log.Timber

class QxDroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (org.qxqx.qxdroid.BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
