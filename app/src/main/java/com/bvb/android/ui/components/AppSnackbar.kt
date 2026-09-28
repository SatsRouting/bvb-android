package com.bvb.android.ui.components

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide snackbar bus: any screen can emit a transient message and the
 * host in MainActivity's Scaffold displays it, replacing the old
 * one-line AlertDialog "toasts".
 */
object AppSnackbar {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun show(message: String) {
        _messages.tryEmit(message)
    }
}
