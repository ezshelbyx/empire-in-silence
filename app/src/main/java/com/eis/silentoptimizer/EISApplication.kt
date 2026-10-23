package com.eis.silentoptimizer

import android.app.Application

class EISApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        container.init()
    }

    override fun onTerminate() {
        container.shutdown()
        super.onTerminate()
    }
}
