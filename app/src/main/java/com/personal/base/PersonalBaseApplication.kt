package com.personal.base

import android.app.Application
import com.personal.base.di.PlatformContainer

class PersonalBaseApplication : Application() {

    lateinit var container: PlatformContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = PlatformContainer(this)
        container.initialize()
    }

    override fun onTerminate() {
        super.onTerminate()
        container.release()
    }
}
