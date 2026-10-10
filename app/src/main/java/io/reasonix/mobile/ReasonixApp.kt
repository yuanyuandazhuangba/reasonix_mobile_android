package io.reasonix.mobile

import android.app.Application

class ReasonixApp : Application() {
    companion object {
        lateinit var instance: ReasonixApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
