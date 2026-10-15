package com.drtech.ledsync

import android.app.Application

/** Application entry point. Holds the device-configuration store used by the UI. */
class LedSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LedSyncApplication
            private set

        fun configStore() = LocalDeviceConfigStore(instance)

        /**
         * A local-network provider when a box has been set up, otherwise the
         * setup-required placeholder.
         */
        fun createProvider(): SyncBoxProvider {
            val config = configStore().load()
            return if (config != null) LocalSyncBoxProvider(config) else UnconfiguredProvider()
        }
    }
}
