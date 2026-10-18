package com.sessionclock

import android.app.Application
import com.sessionclock.core.di.AppContainer

/**
 * Application 入口：初始化全进程依赖容器。
 */
class SessionClockApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
