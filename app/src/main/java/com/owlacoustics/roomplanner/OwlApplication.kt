package com.owlacoustics.roomplanner

import android.app.Application
import com.owlacoustics.roomplanner.data.AppContainer

class OwlApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
