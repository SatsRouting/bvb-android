package com.bvb.android.core

import android.util.Log
import com.bvb.android.BuildConfig

/**
 * Thin logging wrapper that is a no-op in release builds, so error details
 * (exception messages/stack traces from decryption, PGP unlock, network, etc.)
 * never reach logcat on users' devices. Use instead of android.util.Log.
 */
object AppLog {
    fun e(tag: String, message: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(tag, message, t)
    }
}
