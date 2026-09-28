package com.bvb.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

@HiltAndroidApp
class BvbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down BouncyCastle; replace it with the full
        // provider so PGPainless has every algorithm it needs.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
